package peruser;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * output=tcpclient：agent 启动时连上远端采集端（address:port），
 * JVM 退出（或 JMX 触发 dump）时把 ExecData 流写过去，与官方 JaCoCo 的 tcpclient 语义一致。
 *
 * 注意：TCP 通道里是【所有 key 的并集】数据；按 key 分离的数据请以 output=file 的 exec 文件为准。
 */
final class TcpClientOutput implements IAgentOutput {

    private final Options options;
    private final long startTime = System.currentTimeMillis();
    private volatile Socket socket;

    TcpClientOutput(Options options) {
        this.options = options;
    }

    @Override
    public void startup() throws IOException {
        final InetAddress addr = InetAddress.getByName(
                (options.address() == null || options.address().isEmpty())
                        ? "127.0.0.1" : options.address());
        this.socket = new Socket(addr, options.port());
        System.out.println("[xiaoxiao-jacoco] tcpclient connected to " + addr + ":" + options.port());
    }

    @Override
    public void writeExecutionData(boolean reset) throws IOException {
        final Socket s = socket;
        if (s == null || s.isClosed()) {
            System.err.println("[xiaoxiao-jacoco] tcpclient: no connection, skip dump");
            return;
        }
        final RemoteControlWriter writer = new RemoteControlWriter(s.getOutputStream());
        writer.visitSessionInfo(new SessionInfo(options.sessionId(), startTime, System.currentTimeMillis()));
        final ExecutionDataStore store = CoverageStore.mergedStore();
        store.accept(writer);
        writer.flush();
        if (reset) {
            ThreadProbeStore.resetAll();
            ThreadProbeStore.resetMerge();
        }
    }

    @Override
    public void shutdown() throws IOException {
        final Socket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}
