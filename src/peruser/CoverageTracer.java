package peruser;

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
}
