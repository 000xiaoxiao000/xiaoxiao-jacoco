package peruser;

import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * output=tcpserver：启动一个 TCP 服务端等待外部 dump 请求，
 * 协议与官方 JaCoCo agent 完全一致，可被 jacococli dump 或 xiaoxiao-jacoco-cli dump 直接抓取。
 *
 * <p>官方交互流程（见 org.jacoco.core.tools.ExecDumpClient）：
 * <ol>
 *   <li>客户端连上后立刻写 exec 头（5 字节）再写一个 dump 命令块（3 字节），然后只读不写；</li>
 *   <li>服务端回 exec 头（构造 RemoteControlWriter 时即写出）+ session info + 各类 ExecutionData；</li>
 *   <li>服务端最后必须写 {@code BLOCK_CMDOK}（0x20）——客户端的 read() 只有读到它才返回 true，
 *       否则收流即返回 false 并抛 "Socket closed unexpectedly"。</li>
 * </ol>
 *
 * <p>注意：官方 {@code RemoteControlReader.read()} 读完 CMDDUMP 后会继续阻塞等下一个块，
 * 而客户端不会再发字节，所以这里子类化并让 CMDDUMP 直接结束读取，避免每次 dump 都白等一个读超时。
 *
 * <p>本输出属于官方二进制 TCP 协议（非 HTTP）；exec 内容为【所有 key 的并集】，
 * 按 key 分离的数据仍以 outdir 下的 &lt;prefix&gt;-&lt;key&gt;.exec 文件形式产出（配 output=file 时）。
 */
final class TcpServerOutput implements IAgentOutput {

    private static final int READ_TIMEOUT = 5000;

    private final Options options;
    private final long startTime = System.currentTimeMillis();
    private volatile ServerSocket serverSocket;

    TcpServerOutput(Options options) {
        this.options = options;
    }

    @Override
    public void startup() throws IOException {
        final ServerSocket socket;
        try {
            socket = new ServerSocket(options.port(), 1, bindAddress(options.address()));
        } catch (java.net.BindException e) {
            // 跨机抓取出问题时最常见的一类：address 写的不是本机任何网卡的 IP，
            // 或端口已被占用。这里直接给出可照抄的修法，免得只看到一句 Cannot assign requested address。
            final String msg = String.valueOf(e.getMessage());
            System.err.println("[xiaoxiao-jacoco] tcpserver 启动失败: " + msg);
            System.err.println("  当前配置: address=" + (options.address() == null ? "<未指定>" : options.address())
                    + "  port=" + options.port());
            System.err.println("  本机可用 IPv4 地址: " + localAddresses());
            if (msg.contains("already in use")) {
                System.err.println("  -> 端口 " + options.port() + " 已被占用（或上一次的进程没退干净）："
                        + "换一个 port=，或 lsof -i :" + options.port() + " 找到并杀掉旧进程");
            } else {
                System.err.println("  -> address 不是本机网卡地址，无法绑定。"
                        + "请改成 address=0.0.0.0（监听所有网卡，推荐）或上面列出的某个本机 IP");
                System.err.println("  -> 注意 address 指的是【被测机自己的 IP】，不是你本地电脑的 IP；"
                        + "你本地 dump 时才用被测机的 IP 去连");
            }
            throw e;
        }
        this.serverSocket = socket;
        final Thread worker = new Thread(new ConnectionHandler(socket), "xiaoxiao-jacoco-tcpserver");
        worker.setDaemon(true);
        worker.start();
        final String bound = socket.getInetAddress().getHostAddress();
        System.out.println("[xiaoxiao-jacoco] tcpserver listening on " + bound + ":" + socket.getLocalPort());
        if (socket.getInetAddress().isLoopbackAddress()) {
            // 跨机抓取时最常见的坑：address 留空 -> 只绑 127.0.0.1 -> 别的机器连上来 Connection refused
            System.err.println("[xiaoxiao-jacoco] warning: tcpserver 只绑定了回环地址，其它机器无法连接；"
                    + "跨机抓取请显式指定 address=<本机对外 IP>（如 address=" + guessOutboundAddress() + "）或 address=0.0.0.0");
        }
    }

    @Override
    public void writeExecutionData(boolean reset) throws Exception {
        // 无连接：仅按 reset 语义清理内存累计
        if (reset) {
            ThreadProbeStore.resetAll();
            ThreadProbeStore.resetMerge();
        }
    }

    @Override
    public void shutdown() throws IOException {
        final ServerSocket s = serverSocket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }

    /** 猜一个本机对外 IP（用于告警文案里给出可直接抄的地址），失败就退回占位文案。 */
    /** 列出本机所有可用（已启用、非回环）的 IPv4 地址，供绑定失败时对照。 */
    private static String localAddresses() {
        final java.util.List<String> ips = new java.util.ArrayList<>();
        try {
            final java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                final java.net.NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                        continue;
                    }
                } catch (SocketException ignore) {
                    continue;
                }
                final java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    final InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignore) {
            // 取不到就算了，不影响主流程
        }
        return ips.isEmpty() ? "(取不到，请手动 ifconfig / ip addr)" : ips.toString();
    }

    private static String guessOutboundAddress() {
        try {
            java.net.DatagramSocket s = new java.net.DatagramSocket();
            try {
                s.connect(java.net.InetAddress.getByName("8.8.8.8"), 80);
                return s.getLocalAddress().getHostAddress();
            } finally {
                s.close();
            }
        } catch (Exception e) {
            return "你的本机IP";
        }
    }

    private static InetAddress bindAddress(String address) throws IOException {
        if (address == null || address.isEmpty()) {
            return InetAddress.getLoopbackAddress();
        }
        return InetAddress.getByName(address);
    }

    private final class ConnectionHandler implements Runnable {
        private final ServerSocket socket;

        ConnectionHandler(ServerSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            while (!socket.isClosed()) {
                Socket s = null;
                try {
                    s = socket.accept();
                    s.setSoTimeout(READ_TIMEOUT);
                    handle(s);
                } catch (SocketException ignore) {
                    // socket closed -> 退出循环
                    break;
                } catch (IOException e) {
                    System.err.println("[xiaoxiao-jacoco] tcpserver connection error: " + e);
                } catch (Throwable t) {
                    System.err.println("[xiaoxiao-jacoco] tcpserver handler error: " + t);
                    t.printStackTrace();
                } finally {
                    close(s);
                }
            }
        }

        private void handle(final Socket s) throws IOException {
            final boolean[] dump = {true};
            final boolean[] reset = {false};
            final String[] key = {null};          // null = 所有 key 的并集
            final boolean[] listKeys = {false};
            final boolean[] wantStats = {false};
            final int[] statsLimit = {50};

            // 1) exec 头：RemoteControlWriter 构造时即写出，客户端读到的首块必须是 BLOCK_HEADER
            final PerKeyProtocol.ServerWriter writer = new PerKeyProtocol.ServerWriter(s.getOutputStream());
            try {
                // 2) 读命令：子类化让命令块读完即停，避免阻塞到读超时
                final RemoteControlReader reader = new RemoteControlReader(s.getInputStream()) {
                    @Override
                    protected boolean readBlock(final byte blocktype) throws IOException {
                        switch (blocktype) {
                            case RemoteControlWriter.BLOCK_CMDDUMP:          // 官方：并集 dump
                                dump[0] = in.readBoolean();
                                reset[0] = in.readBoolean();
                                return false;
                            case PerKeyProtocol.BLOCK_CMDKEYDUMP:            // 扩展：只 dump 某个 key
                                key[0] = in.readUTF();
                                dump[0] = true;
                                reset[0] = in.readBoolean();
                                return false;
                            case PerKeyProtocol.BLOCK_CMDKEYS:               // 扩展：列出所有 key
                                listKeys[0] = true;
                                return false;
                            case PerKeyProtocol.BLOCK_CMDSTATS:              // 扩展：运行期概况
                                wantStats[0] = true;
                                statsLimit[0] = in.readInt();
                                return false;
                            default:
                                return super.readBlock(blocktype);
                        }
                    }
                };
                reader.read();
            } catch (SocketTimeoutException e) {
                // 客户端连上后不发命令（nc、健康检查）：按默认 dump 处理
            } catch (IOException e) {
                // 连接被关闭 / 协议不兼容：仍尽力回一次 dump
                System.err.println("[xiaoxiao-jacoco] tcpserver: command read failed: " + e);
            }

            // 3) 回 key 列表（`cli keys` 命令）
            if (listKeys[0]) {
                writer.sendKeys(CoverageStore.keys());
                writer.sendCmdOk();
                writer.flush();
                return;
            }

            // 3.1) 回运行期概况（`cli stats` 命令）
            if (wantStats[0]) {
                writer.sendStats(statsLimit[0]);
                writer.sendCmdOk();
                writer.flush();
                return;
            }

            // 4) 写数据
            if (dump[0]) {
                writer.visitSessionInfo(new SessionInfo(
                        key[0] == null ? options.sessionId() : options.sessionId() + "#" + key[0],
                        startTime, System.currentTimeMillis()));
                if (key[0] == null) {
                    CoverageStore.mergedStore().accept(writer);          // 官方语义：并集
                } else {
                    CoverageStore.getStore(key[0]).accept(writer);       // 扩展：单 key
                }
            }
            if (reset[0]) {
                if (key[0] == null) {
                    ThreadProbeStore.resetAll();
                    ThreadProbeStore.resetMerge();
                } else {
                    ThreadProbeStore.resetKey(key[0]);
                }
            }
            // 5) 命令确认：客户端 ExecDumpClient 靠它判定成功
            writer.sendCmdOk();
            writer.flush();

            System.out.println("[xiaoxiao-jacoco] tcpserver: dump to " + s.getRemoteSocketAddress()
                    + " (dump=" + dump[0] + ", reset=" + reset[0]
                    + ", key=" + (key[0] == null ? "<all>" : key[0]) + ")  "
                    + Diagnostics.summary());
            if (dump[0] && CoverageStore.keys().isEmpty()) {
                // 抓到空数据是用户最常遇到的故障，这里立刻给出最可能的原因
                Diagnostics.checkAndHint(options.headerKey);
            }
        }

        private void close(Socket s) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }
}
