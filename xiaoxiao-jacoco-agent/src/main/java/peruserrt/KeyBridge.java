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
 *   - 线程池线程是复用的、不重新继承，靠 {@link #wrap(Runnable)} / {@link #wrap(Callable)}
 *     在【提交时刻】捕获 key、在【执行时刻】设置并在 finally 恢复。
 */
public final class KeyBridge {

    /** 当前线程归属的 key。InheritableThreadLocal 使 new Thread() 自动继承。 */
    private static final InheritableThreadLocal<String> KEY = new InheritableThreadLocal<String>();
    /** 上一层 key，用于 begin/end 嵌套时正确回退。 */
    private static final InheritableThreadLocal<String> PREV = new InheritableThreadLocal<String>();

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
}
