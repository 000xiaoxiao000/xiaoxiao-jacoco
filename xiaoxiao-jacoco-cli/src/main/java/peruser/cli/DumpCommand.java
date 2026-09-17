package peruser.cli;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * dump 命令：从运行在目标 JVM 上的 agent（output=tcpserver）抓取 exec 数据，官方 jacococli dump 的全部参数，
 * 外加 xiaoxiao-jacoco 专有的【按 key 抓取】。
 *
 * <pre>
 *   dump [--address &lt;address&gt;] [--port &lt;port&gt;] [--destfile &lt;path&gt;] [--reset] [--retry &lt;count&gt;] [--quiet]
 *        [--key &lt;k&gt;]...      # 只抓某个 key；可重复，也可逗号分隔（1,2）
 * </pre>
 *
 * 输出文件：
 *
 * <pre>
 *   --destfile <path>   写到该路径；父目录不存在会自动创建（无法创建时给出明确报错）
 *   不给 --destfile     默认 ./jacoco.exec
 *   多 key（--key 1,2）  拆成 <path 去掉 .exec>-<key>.exec，每个 key 一个文件
 * </pre>
 *
 * 文件名不以 `.exec` 结尾时会自动补上；指定 --key 时是覆盖写（文件即该 key 的完整数据），
 * 不与同名旧文件 OR 合并，保证各 key 严格分离。
 */
public final class DumpCommand {

    private DumpCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean quiet = CliArgs.has(args, "quiet");
        CliArgs.warnUnknown(args, "address", "port", "destfile", "reset", "retry", "quiet", "key", "help");
        final String address = CliArgs.value(args, "address", "127.0.0.1");
        final int port = Integer.parseInt(CliArgs.value(args, "port", "6300"));
        final boolean reset = CliArgs.has(args, "reset");
        final int retry = Integer.parseInt(CliArgs.value(args, "retry", "0"));

        final String destfile = withExecSuffix(CliArgs.value(args, "destfile", "jacoco.exec"));

        final Set<String> keys = parseKeys(args);

        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] connecting to " + address + ":" + port);
        }

        if (keys.isEmpty()) {
            // ===== 官方语义：所有 key 的并集 =====
            final ExecFileLoader loader = RemoteDump.dump(address, port, null, reset, retry, 1000L);
            final File out = writeMerged(new File(destfile), loader);
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] dump -> " + out.getAbsolutePath()
                        + " (classes=" + loader.getExecutionDataStore().getContents().size() + ", reset=" + reset + ")");
            }
            return;
        }

        // ===== 扩展：按 key 分别抓 =====
        final boolean multi = keys.size() > 1;
        boolean emptySeen = false;
        for (String key : keys) {
            final ExecFileLoader loader = RemoteDump.dump(address, port, key, reset, retry, 1000L);
            final File out = new File(!multi ? destfile : stripExec(destfile) + "-" + sanitize(key) + ".exec");
            writeFresh(out, loader);
            final int n = loader.getExecutionDataStore().getContents().size();
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] key=" + key + " -> " + out.getAbsolutePath()
                        + " (classes=" + n + ", probes=" + countProbes(loader) + ", reset=" + reset + ")");
            }
            if (n == 0) {
                emptySeen = true;
                warnEmptyKey(address, port, key, retry);
            }
        }
        if (emptySeen && reset) {
            System.err.println("[xiaoxiao-jacoco-cli] hint: 本次带了 --reset，抓完即清空；"
                    + "若想累积多次请求的数据，去掉 --reset。");
        }
    }

    /** 抓到空数据时，直接把 agent 端的自检数字拉出来说明原因，避免用户干瞪眼。 */
    private static void warnEmptyKey(String address, int port, String key, int retry) {
        System.err.println("[xiaoxiao-jacoco-cli] warning: key=" + key + " 抓到 0 个类（exec 是空的）");
        try {
            final RemoteDump.KeyReport rep = RemoteDump.listKeys(address, port, retry, 1000L);
            if (rep.keys.isEmpty()) {
                System.err.println("  agent 当前还没有任何 key。已插桩类数=" + rep.classesInstrumented
                        + "，HTTP 钩子触发=" + rep.requestsHooked + "，成功归属=" + rep.requestsTagged);
                if (rep.classesInstrumented == 0) {
                    System.err.println("  -> 一个类都没插桩：检查 agent 的 includes（要写 VM 类名 com/foo/Bar，"
                            + "不是 URL 路径 /web/testWeb）；"
                            + (rep.suggestedIncludes.isEmpty() ? "" : "建议 includes=" + rep.suggestedIncludes.get(0)));
                } else if (rep.classesNoLocation > 0) {
                    System.err.println("  -> " + rep.classesNoLocation
                            + " 个类没有 source location（Spring Boot 可执行 jar 常见）被跳过："
                            + "给 agent 加 inclnolocationclasses=true 后重启。");
                } else if (rep.requestsTagged == 0) {
                    System.err.println("  -> 请求没读到 key：检查 headerkey 头名 / 请求是否带了该 header；"
                            + "没有 key 的请求覆盖率会被丢弃。");
                }
            } else {
                System.err.println("  agent 上现存 key: " + rep.keys + " —— 你要的 key=" + key + " 不在其中，"
                        + "确认请求头的值与之完全一致（注意空格/大小写）。");
            }
        } catch (IOException e) {
            System.err.println("  (无法读取 agent 自检信息: " + e + ")");
        }
    }

    /** --key 支持重复传参与逗号分隔。 */
    private static Set<String> parseKeys(String[] args) {
        final Set<String> keys = new LinkedHashSet<>();
        for (String v : CliArgs.values(args, "key")) {
            for (String part : v.split(",")) {
                String k = part.trim();
                if (!k.isEmpty()) keys.add(k);
            }
        }
        return keys;
    }

    private static long countProbes(ExecFileLoader loader) {
        long n = 0;
        for (ExecutionData d : loader.getExecutionDataStore().getContents()) {
            n += d.getProbes().length;
        }
        return n;
    }

    /** 与已存在的 destfile 按 classId OR 合并（jacococli dump 的追加语义）。 */
    private static File writeMerged(File out, ExecFileLoader loader) throws IOException {
        final org.jacoco.core.data.ExecutionDataStore merged = new org.jacoco.core.data.ExecutionDataStore();
        final org.jacoco.core.data.SessionInfoStore sessions = new org.jacoco.core.data.SessionInfoStore();
        if (out.exists() && out.length() > 0) {
            try {
                final ExecFileLoader existing = new ExecFileLoader();
                existing.load(out);
                for (ExecutionData d : existing.getExecutionDataStore().getContents()) merged.put(d);
                for (SessionInfo si : existing.getSessionInfoStore().getInfos()) sessions.visitSessionInfo(si);
            } catch (IOException e) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: existing destfile unreadable, overwriting: " + e);
            }
        }
        for (ExecutionData d : loader.getExecutionDataStore().getContents()) merged.put(d);
        for (SessionInfo si : loader.getSessionInfoStore().getInfos()) sessions.visitSessionInfo(si);
        write(out, merged, sessions);
        return out;
    }

    /** 覆盖写：文件里只有这一次抓到的内容。 */
    private static void writeFresh(File out, ExecFileLoader loader) throws IOException {
        final org.jacoco.core.data.ExecutionDataStore store = new org.jacoco.core.data.ExecutionDataStore();
        final org.jacoco.core.data.SessionInfoStore sessions = new org.jacoco.core.data.SessionInfoStore();
        for (ExecutionData d : loader.getExecutionDataStore().getContents()) store.put(d);
        for (SessionInfo si : loader.getSessionInfoStore().getInfos()) sessions.visitSessionInfo(si);
        write(out, store, sessions);
    }

    private static void write(File out, org.jacoco.core.data.ExecutionDataStore store,
                              org.jacoco.core.data.SessionInfoStore sessions) throws IOException {
        final File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            // mkdirs 失败时不要等到 FileOutputStream 抛 FileNotFoundException（信息太难定位）
            throw new IOException("无法创建输出目录 " + parent.getAbsolutePath()
                    + "（权限不足？还是把它当成了已存在的文件？）"
                    + " —— 换一个有写权限的目录，或先手动 mkdir -p " + parent.getAbsolutePath());
        }
        try (OutputStream os = new FileOutputStream(out)) {
            final ExecutionDataWriter writer = new ExecutionDataWriter(os);
            for (SessionInfo si : sessions.getInfos()) writer.visitSessionInfo(si);
            store.accept(writer);
            writer.flush();
        }
    }

    /** 文件名不以 .exec 结尾时自动补上，避免写出无法被 report/execinfo 识别的文件。 */
    private static String withExecSuffix(String destfile) {
        return destfile.endsWith(".exec") ? destfile : destfile + ".exec";
    }

    private static String stripExec(String destfile) {
        return destfile.endsWith(".exec")
                ? destfile.substring(0, destfile.length() - ".exec".length())
                : destfile;
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar dump [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --address <address>   agent tcpserver 地址，默认 127.0.0.1");
        System.out.println("  --port <port>         agent tcpserver 端口，默认 6300");
        System.out.println("  --destfile <path>     写出到该 exec 文件，默认 ./jacoco.exec");
        System.out.println("                        父目录不存在会自动创建；文件名不以 .exec 结尾会自动补上");
        System.out.println("  --reset               抓取后让 agent 清空内存累计");
        System.out.println("  --retry <count>       连接失败重试次数，默认 0");
        System.out.println("  --key <key>           只抓该 key（X-Coverage-Key 的值）；可重复或逗号分隔");
        System.out.println("                        多个 key 时写成 <destfile 去掉 .exec>-<key>.exec");
        System.out.println("  --quiet               减少输出");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # 所有 key 的并集（与官方 jacococli dump 等价）");
        System.out.println("  dump --address 172.16.11.13 --port 6300 --destfile all.exec");
        System.out.println();
        System.out.println("  # 只抓 X-Coverage-Key=1");
        System.out.println("  dump --address 172.16.11.13 --port 6300 --key 1 --destfile cov-1.exec");
        System.out.println();
        System.out.println("  # 一次抓 1 和 2 两个 key，分别落盘");
        System.out.println("  dump --address 172.16.11.13 --port 6300 --key 1,2 --destfile dumped.exec");
        System.out.println("  # -> dumped-1.exec  dumped-2.exec");
        System.out.println();
        System.out.println("  # 落进 coverage 目录（目录不存在会自动创建），命名与 agent 端一致");
        System.out.println("  dump --address 172.16.11.13 --port 6300 --key 1 --destfile coverage/coverage-1.exec");
        System.out.println("  dump --address 172.16.11.13 --port 6300 --key 2 --destfile coverage/coverage-2.exec");
        System.out.println("  # 然后：report --execdir coverage --classfiles <classes> --html reports");
        System.out.println();
        System.out.println("  # 不知道有哪些 key 时先列一下：keys --address <addr> --port <port>");
    }
}
