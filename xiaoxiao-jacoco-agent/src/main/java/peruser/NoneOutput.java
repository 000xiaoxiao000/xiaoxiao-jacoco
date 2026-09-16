package peruser;

/**
 * output=none：不产出任何 exec 文件（探针仍在内存里累计），
 * 与官方 JaCoCo 的 none 语义一致——通常配合其它收集方式使用。
 */
final class NoneOutput implements IAgentOutput {

    @Override
    public void startup() {
        // no-op
    }

    @Override
    public void writeExecutionData(boolean reset) throws Exception {
        if (reset) {
            ThreadProbeStore.resetAll();
            ThreadProbeStore.resetMerge();
        }
    }

    @Override
    public void shutdown() {
        // no-op
    }
}
