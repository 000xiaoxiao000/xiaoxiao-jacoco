package peruser;

/**
 * agent 输出方式工厂：把 output=file|tcpserver|tcpclient|none 映射到对应实现。
 */
final class Outputs {

    private Outputs() {
    }

    static IAgentOutput create(Options options) {
        switch (options.output()) {
            case tcpserver:
                return new TcpServerOutput(options);
            case tcpclient:
                return new TcpClientOutput(options);
            case none:
                return new NoneOutput();
            case file:
            default:
                return new FileOutput(options);
        }
    }
}
