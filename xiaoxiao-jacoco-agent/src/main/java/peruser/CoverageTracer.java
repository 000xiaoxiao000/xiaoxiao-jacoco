package peruser;

import peruserrt.KeyBridge;

import java.util.concurrent.Callable;

/**
 * 公共 API：把一个工作单元（一次用例 / 一次请求 / 一次任务）的覆盖率归到某个 key。
 *
 * 用法（推荐 try-with-resources，自动 begin/end）：
 * <pre>
 *   try (CoverageTracer t = CoverageTracer.start("case-login-error")) {
 *       targetSystem.doWork();
 *   }
 * </pre>
 * 或显式：
 * <pre>
 *   CoverageTracer.begin("user-A");
 *   try { targetSystem.doWork(); } finally { CoverageTracer.end(); }
 * </pre>
 *
 * 被插桩的类在本区间内执行的探针，都会落入当前线程的 ThreadLocal，并在 end 时按 key 合并。
 * 这样无论目标系统是 HTTP 服务、消息消费者还是定时任务，都能用同一套机制做按 key 的精准覆盖率。
 */
public final class CoverageTracer implements AutoCloseable {

    private CoverageTracer() {
    }

    /** 开始一个归属单元，返回 AutoCloseable，close 时自动 end。 */
    public static CoverageTracer start(String key) {
        ThreadProbeStore.begin(key);
        return new CoverageTracer();
    }

    public static void begin(String key) {
        ThreadProbeStore.begin(key);
    }

    public static void end() {
        ThreadProbeStore.end();
    }

    @Override
    public void close() {
        ThreadProbeStore.end();
    }

    // ===== 异步传递（自研线程池 / 不走 JDK 标准提交入口的场景手动用） =====

    /**
     * 把任务包一层，使它在提交时刻捕获当前 key、执行期间带着 key 跑。
     *
     * 常见的 @Async / CompletableFuture / ThreadPoolTaskExecutor 已由 agent 自动织入
     * （JDK 线程池提交入口），无需手动调用；只有在【自研线程池】或【任务被二次转交】
     * （例如先放进自己的队列、再由别的线程取出执行）时才需要手动包一层。
     */
    public static Runnable wrap(Runnable task) {
        return KeyBridge.wrap(task);
    }

    @SuppressWarnings("unchecked")
    public static <T> Callable<T> wrap(Callable<T> task) {
        return (Callable<T>) KeyBridge.wrapCallable((Callable<Object>) task);
    }
}
