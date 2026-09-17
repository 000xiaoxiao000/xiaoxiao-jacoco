package peruserrt;

import java.util.concurrent.Callable;

/**
 * 异步场景下的 key 传递桥接（bootstrap 可见）。
 *
 * 为什么单独一个包：给 JDK 线程池（java.util.concurrent.*，由 bootstrap 类加载器加载）织入
 * 「提交任务时捕获当前 key、执行任务时恢复 key」的逻辑后，被织入的 JDK 类只能引用 bootstrap
 * 可见的类。因此本包会被 {@code Instrumentation.appendToBootstrapClassLoaderSearch} 注入 bootstrap。
 *
 * 设计约束（重要）：
 *   - 本包必须【自包含】，不能引用 peruser.* —— 否则 bootstrap 解析不到（父加载器看不到子加载器的类）；
 *   - 反过来 peruser.*（在系统类路径上）可以引用本包：系统类加载器向上委派到 bootstrap，能找到本类；
 *   - 只把本包注入 bootstrap、不注入 peruser.*，是为了避免出现两份持静态状态的类
 *     （Premain-Class 已由系统类加载器加载，若 peruser.* 也被 bootstrap 加载就会状态分裂、覆盖率全空）。
 *
 * key 存于 InheritableThreadLocal：
 *   - new Thread() 创建的子线程自动继承（无需织入）；
 *   - 线程池线程是复用的、不重新继承，靠 {@link #wrap(Runnable)} / {@link # wrap(Callable)}
 *     在【提交时刻】捕获 key、在【执行时刻】设置并在 finally 恢复。
 */
public final class KeyBridge {

    /** 当前线程归属的 key。InheritableThreadLocal 使 new Thread() 自动继承。 */
    private static final InheritableThreadLocal<String> KEY = new InheritableThreadLocal<String>();
    /** 上一层 key，用于 begin/end 嵌套时正确回退。 */
    private static final InheritableThreadLocal<String> PREV = new InheritableThreadLocal<String>();

    /**
     * parallelStream 专用：ForkJoinTask 的 key 暂存表。
     *
     * <p>parallelStream 走 {@code ForkJoinTask.fork()}，不经过线程池的提交入口，
     * 因此无法像普通线程池那样在提交时刻直接包装任务。这里在 fork()（提交线程）把 key 挂到
     * task 对象上，在 doExec()（工作线程）取出并设进当前线程，执行完立刻移除。
     *
     * <p>只有【当前线程有 key】时才写入 —— 绝大多数应用线程没有 key，表永远是空的，
     * 对目标系统零开销。任务执行完必然 remove，不会泄漏；异常取消的残留由 SIZE 上限兜底清理。
     */
    private static final java.util.Map<Object, String> TASK_KEYS =
            new java.util.concurrent.ConcurrentHashMap<Object, String>(64);
    private static final int TASK_KEYS_MAX = 20000;

    private KeyBridge() {
    }

    public static String get() {
        return KEY.get();
    }

    /** 进入一个归属单元：压栈（支持嵌套）。 */
    public static void push(String key) {
        if (key == null) {
            return;
        }
        PREV.set(KEY.get());
        KEY.set(key);
    }

    /** 退出一个归属单元：出栈回退到上一层（没有则清空）。 */
    public static void pop() {
        String prev = PREV.get();
        PREV.remove();
        if (prev == null) {
            KEY.remove();
        } else {
            KEY.set(prev);
        }
    }

    /** 强制设定（异步任务执行前用），不参与 begin/end 栈。 */
    public static void set(String key) {
        if (key == null) {
            KEY.remove();
        } else {
            KEY.set(key);
        }
    }

    public static void clear() {
        KEY.remove();
        PREV.remove();
    }

    // ===== 异步任务包装：由织入后的 JDK 线程池在提交任务时调用 =====

    /** 无 key 或已包装时原样返回（零开销）；否则包一层，执行期间把 key 设进去。 */
    public static Runnable wrap(Runnable r) {
        if (r == null || r instanceof KeyRunnable) {
            return r;
        }
        final String key = KEY.get();
        if (key == null) {
            return r;
        }
        return new KeyRunnable(r, key);
    }

    public static Callable<Object> wrapCallable(Callable<Object> c) {
        if (c == null || c instanceof KeyCallable) {
            return c;
        }
        final String key = KEY.get();
        if (key == null) {
            return c;
        }
        return new KeyCallable(c, key);
    }

    // ===== parallelStream（ForkJoinTask.fork / doExec）专用 =====

    /**
     * 在【提交线程】调用（织入 ForkJoinTask.fork()）：把当前 key 挂到 task 上。
     * 无 key 时直接返回，不写表、不加锁之外的任何开销。
     */
    public static void markFork(Object task) {
        if (task == null) {
            return;
        }
        final String key = KEY.get();
        if (key == null) {
            return;
        }
        if (TASK_KEYS.size() > TASK_KEYS_MAX) {
            // 兜底：任务被取消而未执行时的残留，超阈值整体清一次，避免无界增长
            TASK_KEYS.clear();
        }
        TASK_KEYS.put(task, key);
    }

    /**
     * 在【工作线程】调用（织入 ForkJoinTask.doExec() 入口）：取出挂在该 task 上的 key 并设进当前线程。
     * 没挂过 key 的任务什么也不做（工作线程保持原样，不会被上一次任务的 key 污染）。
     */
    public static void forkEnter(Object task) {
        if (task == null) {
            return;
        }
        final String key = TASK_KEYS.get(task);
        if (key == null) {
            return;
        }
        PREV.set(KEY.get());
        KEY.set(key);
    }

    /**
     * 在【工作线程】调用（织入 ForkJoinTask.doExec() 的所有出口）：回退 key 并清掉表里该 task 的记录。
     * 与 {@link #forkEnter(Object)} 成对，只有真正设过 key 时才需要清理。
     */
    public static void forkExit(Object task) {
        if (task == null) {
            return;
        }
        if (TASK_KEYS.remove(task) == null) {
            return;
        }
        String prev = PREV.get();
        PREV.remove();
        if (prev == null) {
            KEY.remove();
        } else {
            KEY.set(prev);
        }
    }
}
