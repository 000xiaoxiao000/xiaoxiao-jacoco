package peruser;

/**
 * JMX bean 实现：把运行时 dump / reset 能力暴露给 JMX 客户端（jconsole / jmxterm / 脚本）。
 */
public final class JacocoRuntime implements JacocoRuntimeMBean {

    private final Options options;
    private final IAgentOutput output;

    JacocoRuntime(Options options, IAgentOutput output) {
        this.options = options;
        this.output = output;
    }

    @Override
    public String getVersion() {
        return "xiaoxiao-jacoco-agent 1.0.0 (JaCoCo compatible)";
    }

    @Override
    public String getSessionId() {
        return options.sessionId();
    }

    @Override
    public void setSessionId(String id) {
        options.agentOptions().setSessionId(id);
    }

    @Override
    public String dump(boolean reset) {
        try {
            PerUserAgent.dumpPerKeyFiles(options, reset);   // 按 key 落盘到 outdir
            output.writeExecutionData(reset);               // 官方 output 通道
            return "dump ok (reset=" + reset + ", output=" + options.output() + ")";
        } catch (Exception e) {
            return "dump failed: " + e;
        }
    }

    @Override
    public void reset() {
        ThreadProbeStore.resetAll();
        ThreadProbeStore.resetMerge();
    }
}
