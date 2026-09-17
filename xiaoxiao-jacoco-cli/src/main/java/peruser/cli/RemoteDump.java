package peruser.cli;

import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 与 xiaoxiao-jacoco-agent 通信的远程 dump 客户端。
 *
 * <p>不指定 key 时走【官方协议】：发 BLOCK_CMDDUMP(0x40)，拿回「所有 key 的并集」，
 * 与官方 ExecDumpClient / jacococli dump 完全等价。
 *
 * <p>指定 key 时走【私有扩展】：发 BLOCK_CMDKEYDUMP(0x41)，只拿回该 key 的数据。
 * 该块只有 xiaoxiao-jacoco-agent 认识，对官方 agent 请勿使用。
 */
final class RemoteDump {

    /** 与 agent 端 PerKeyProtocol 保持一致。 */
    private static final byte BLOCK_CMDKEYDUMP = 0x41;
    private static final byte BLOCK_CMDKEYS = 0x42;
    private static final byte BLOCK_KEYS = 0x21;
    private static final byte BLOCK_CMDSTATS = 0x43;
    private static final byte BLOCK_STATS = 0x22;
    private static final byte BLOCK_CMDCLASSES = 0x44;
    private static final byte BLOCK_CLASSES = 0x23;
    private static final byte BLOCK_CMDSETKEY = 0x45;

    private static final int SO_TIMEOUT = 10_000;

    private RemoteDump() {
    }

    /** 写私有命令块的写出器（out 是 ExecutionDataWriter 的 protected 字段）。 */
    private static final class CmdWriter extends RemoteControlWriter {
        CmdWriter(OutputStream out) throws IOException {
            super(out);
        }

        void cmdKeyDump(String key, boolean reset) throws IOException {
            out.writeByte(BLOCK_CMDKEYDUMP);
            out.writeUTF(key);
            out.writeBoolean(reset);
            out.flush();
        }

        void cmdKeys() throws IOException {
            out.writeByte(BLOCK_CMDKEYS);
            out.flush();
        }

        void cmdStats(int limit) throws IOException {
            out.writeByte(BLOCK_CMDSTATS);
            out.writeInt(limit);
            out.flush();
        }

        void cmdClasses() throws IOException {
            out.writeByte(BLOCK_CMDCLASSES);
            out.flush();
        }

        /** 设定全局当前 key；key 为 null/空串表示清除。 */
        void cmdSetKey(String key) throws IOException {
            out.writeByte(BLOCK_CMDSETKEY);
            out.writeUTF(key == null ? "" : key);
            out.flush();
        }
    }

    /** 能识别 BLOCK_KEYS 响应的读入器（含运行期自检计数，老版本 agent 没有这些字段时 tolerant）。 */
    private static final class CmdReader extends RemoteControlReader {
        final Set<String> keys = new LinkedHashSet<>();
        final java.util.List<String> suggestedIncludes = new java.util.ArrayList<>();
        long classesSeen = -1;
        long classesInstrumented = -1;
        long requestsHooked = -1;
        long requestsTagged = -1;
        long classesNoLocation = -1;
        final StatsReport stats = new StatsReport();
        byte[] classesZip = null;     // dumpclasses 命令：被插桩类原始字节码的 zip 包

        CmdReader(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected boolean readBlock(byte blocktype) throws IOException {
            if (blocktype == BLOCK_KEYS) {
                final int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    keys.add(in.readUTF());
                }
                try {
                    classesSeen = in.readLong();
                    classesInstrumented = in.readLong();
                    requestsHooked = in.readLong();
                    requestsTagged = in.readLong();
                    classesNoLocation = in.readLong();
                    final int m = in.readInt();
                    for (int i = 0; i < m; i++) {
                        suggestedIncludes.add(in.readUTF());
                    }
                } catch (IOException ignore) {
                    // 老版本 agent：没有自检字段，读到流尾，忽略即可
                    classesSeen = -1;
                }
                return false;
            }
            if (blocktype == BLOCK_CLASSES) {
                final long size = in.readLong();
                final byte[] buf = new byte[(int) size];
                in.readFully(buf, 0, (int) size);
                classesZip = buf;
                return false;
            }
            if (blocktype == BLOCK_STATS) {
                stats.classesSeen = in.readLong();
                stats.classesInstrumented = in.readLong();
                stats.classesNoLocation = in.readLong();
                stats.requestsHooked = in.readLong();
                stats.requestsTagged = in.readLong();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    stats.instrumented.add(in.readUTF());
                }
                n = in.readInt();
                for (int i = 0; i < n; i++) {
                    stats.skippedSamples.add(in.readUTF());
                }
                n = in.readInt();
                for (int i = 0; i < n; i++) {
                    stats.suggestedIncludes.add(in.readUTF());
                }
                n = in.readInt();
                for (int i = 0; i < n; i++) {
                    stats.keyStats.add(new KeyStatLine(in.readUTF(), in.readInt(), in.readInt(), in.readInt()));
                }
                return false;
            }
            return super.readBlock(blocktype);
        }
    }

    /** stats 命令里一行「某 key 的采集概况」。 */
    static final class KeyStatLine {
        final String key;
        final int classes;
        final int probes;
        final int covered;

        KeyStatLine(String key, int classes, int probes, int covered) {
            this.key = key;
            this.classes = classes;
            this.probes = probes;
            this.covered = covered;
        }
    }

    /** stats 命令的完整结果。 */
    static final class StatsReport {
        long classesSeen = -1;
        long classesInstrumented = -1;
        long classesNoLocation = -1;
        long requestsHooked = -1;
        long requestsTagged = -1;
        final java.util.List<String> instrumented = new java.util.ArrayList<>();
        final java.util.List<String> skippedSamples = new java.util.ArrayList<>();
        final java.util.List<String> suggestedIncludes = new java.util.ArrayList<>();
        final java.util.List<KeyStatLine> keyStats = new java.util.ArrayList<>();
    }

    /** keys 命令的完整结果（key 列表 + 自检计数 + includes 建议）。 */
    static final class KeyReport {
        final Set<String> keys;
        final java.util.List<String> suggestedIncludes;
        final long classesSeen;
        final long classesInstrumented;
        final long requestsHooked;
        final long requestsTagged;
        final long classesNoLocation;

        KeyReport(CmdReader r) {
            this.keys = r.keys;
            this.suggestedIncludes = r.suggestedIncludes;
            this.classesSeen = r.classesSeen;
            this.classesInstrumented = r.classesInstrumented;
            this.requestsHooked = r.requestsHooked;
            this.requestsTagged = r.requestsTagged;
            this.classesNoLocation = r.classesNoLocation;
        }
    }

    /**
     * 抓取 exec 数据。
     *
     * @param key null 表示走官方协议拿所有 key 的并集；非 null 表示只拿该 key
     */
    static ExecFileLoader dump(String address, int port, String key, boolean reset, int retry, long retryDelay)
            throws IOException {
        final ExecFileLoader loader = new ExecFileLoader();
        final Socket socket = connect(address, port, retry, retryDelay);
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            final CmdWriter writer = new CmdWriter(socket.getOutputStream());
            final CmdReader reader = new CmdReader(socket.getInputStream());
            reader.setSessionInfoVisitor(loader.getSessionInfoStore());
            reader.setExecutionDataVisitor(loader.getExecutionDataStore());

            if (key == null) {
                writer.visitDumpCommand(true, reset);   // 官方块，兼容官方 agent
            } else {
                writer.cmdKeyDump(key, reset);          // 私有块，仅 xiaoxiao-jacoco-agent
            }
            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
        } finally {
            socket.close();
        }
        return loader;
    }

    /** 列出 agent 当前已采集到的所有 key（私有扩展，仅 xiaoxiao-jacoco-agent 支持）。 */
    static KeyReport listKeys(String address, int port, int retry, long retryDelay) throws IOException {
        final Socket socket = connect(address, port, retry, retryDelay);
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            final CmdWriter writer = new CmdWriter(socket.getOutputStream());
            final CmdReader reader = new CmdReader(socket.getInputStream());
            writer.cmdKeys();
            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
            return new KeyReport(reader);
        } finally {
            socket.close();
        }
    }

    /** 取 agent 运行期概况（私有扩展，仅 xiaoxiao-jacoco-agent 支持）。 */
    static StatsReport fetchStats(String address, int port, int limit, int retry, long retryDelay)
            throws IOException {
        final Socket socket = connect(address, port, retry, retryDelay);
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            final CmdWriter writer = new CmdWriter(socket.getOutputStream());
            final CmdReader reader = new CmdReader(socket.getInputStream());
            writer.cmdStats(limit);
            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
            return reader.stats;
        } finally {
            socket.close();
        }
    }

    /** 取 agent 内存里被插桩类的原始字节码 zip 包（私有扩展，仅 xiaoxiao-jacoco-agent 支持）。 */
    static byte[] fetchClasses(String address, int port, int retry, long retryDelay) throws IOException {
        final Socket socket = connect(address, port, retry, retryDelay);
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            final CmdWriter writer = new CmdWriter(socket.getOutputStream());
            final CmdReader reader = new CmdReader(socket.getInputStream());
            writer.cmdClasses();
            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
            if (reader.classesZip == null) {
                throw new IOException("agent 未返回 classes 数据（可能版本过旧不支持 dumpclasses，请升级 agent）");
            }
            return reader.classesZip;
        } finally {
            socket.close();
        }
    }

    /**
     * 设定 agent 的【全局当前 key】（私有扩展，仅 xiaoxiao-jacoco-agent 支持）。
     *
     * <p>这是「零改业务代码」的按 key 分离手段：归属判定发生在进程级，不依赖线程传递，
     * 因此 parallelStream / 自研线程池等场景也能采到。适合「单实例 + 时间窗口」式分离：
     * 设 key=A → 跑 A → dump --key A --reset → 设 key=B → 跑 B → dump --key B。
     *
     * @param key null 或空串表示清除全局 key
     */
    static void setCurrentKey(String address, int port, String key, int retry, long retryDelay)
            throws IOException {
        final Socket socket = connect(address, port, retry, retryDelay);
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            final CmdWriter writer = new CmdWriter(socket.getOutputStream());
            final CmdReader reader = new CmdReader(socket.getInputStream());
            writer.cmdSetKey(key);
            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
        } finally {
            socket.close();
        }
    }

    private static Socket connect(String address, int port, int retry, long retryDelay) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                return new Socket(InetAddress.getByName(address), port);
            } catch (IOException e) {
                if (++attempt > retry) {
                    final String msg = String.valueOf(e.getMessage());
                    if (msg.contains("Connection refused")) {
                        System.err.println("[xiaoxiao-jacoco-cli] 连不上 " + address + ":" + port + "（Connection refused）");
                        System.err.println("  -> 被测机上先确认端口在监听：netstat -anp | grep " + port
                                + "  或  lsof -i :" + port);
                        System.err.println("  -> 常见原因：agent 启动参数没写 output=tcpserver；"
                                + "或 address=<被测机IP> 绑的不是本机网卡（看被测机日志有没有 "
                                + "'tcpserver listening on ...' 这行）");
                    } else if (msg.contains("timed out") || msg.contains("No route")) {
                        System.err.println("[xiaoxiao-jacoco-cli] 连不上 " + address + ":" + port + "（超时/无路由）");
                        System.err.println("  -> 网络不通或防火墙拦了：先 ping " + address
                                + "，再让运维放通 TCP " + port + "（云主机还要看安全组）");
                    }
                    throw e;
                }
                System.err.println("[xiaoxiao-jacoco-cli] connection failed: " + e + " (retry " + attempt + "/" + retry + ")");
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while retrying", ie);
                }
            }
        }
    }
}
