package peruser.cli;

/**
 * setkey 命令：远程设定 agent 的【全局当前 key】，实现「零改业务代码」的按 key 覆盖率分离。
 *
 * <pre>
 *   setkey --key &lt;k&gt; [--address &lt;address&gt;] [--port &lt;port&gt;] [--retry &lt;count&gt;]
 *   setkey --clear [--address &lt;address&gt;] [--port &lt;port&gt;]
 * </pre>
 *
 * <p>为什么需要它：按 key 分离覆盖率，常规手段是让业务代码调 CoverageTracer / 带请求头，
 * 这都要求改目标系统。全局 key 的归属判定是【进程级】的（不依赖线程传递），
 * 所以 parallelStream、自研线程池、@Async 等"key 传不过去"的场景也能采到，
 * 而且完全不用碰业务代码 —— 由测试平台/CI 在进程外驱动即可。
 *
 * <p>典型用法（单实例 + 时间窗口式 A/B 分离）：
 * <pre>
 *   setkey --key case-A            # 1) 设定归属
 *   ... 跑用例 A ...
 *   dump  --key case-A --reset     # 2) 抓走并清空
 *   setkey --key case-B            # 3) 切换
 *   ... 跑用例 B ...
 *   dump  --key case-B --reset
 *   setkey --clear                 # 4) 收工
 * </pre>
 *
 * <p>注意：全局 key 是【整个进程生效】的，并发跑不同用例会混在一起。
 * 需要真正并发按请求/用例分离，请用 headerkey（也是零改代码）或业务侧 CoverageTracer。
 */
public final class SetKeyCommand {

    private SetKeyCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final String address = CliArgs.value(args, "address", "127.0.0.1");
        final int port = Integer.parseInt(CliArgs.value(args, "port", "6300"));
        final int retry = Integer.parseInt(CliArgs.value(args, "retry", "0"));
        final boolean quiet = CliArgs.has(args, "quiet");
        final boolean clear = CliArgs.has(args, "clear");
        final String key = CliArgs.value(args, "key", null);
        CliArgs.warnUnknown(args, "address", "port", "retry", "quiet", "clear", "key", "help");

        if (!clear && (key == null || key.trim().isEmpty())) {
            throw new CliUsageException("必须指定 --key <k>，或用 --clear 清除全局 key");
        }
        final String effective = clear ? null : key.trim();

        RemoteDump.setCurrentKey(address, port, effective, retry, 1000L);

        if (!quiet) {
            if (effective == null) {
                System.out.println("[xiaoxiao-jacoco-cli] global key cleared on " + address + ":" + port);
            } else {
                System.out.println("[xiaoxiao-jacoco-cli] global key set to '" + effective + "' on "
                        + address + ":" + port);
                System.out.println("  -> 之后该进程里所有无线程 key 的覆盖都会归到 '" + effective + "'");
                System.out.println("  -> 抓数据：dump --key " + effective + " --destfile coverage/"
                        + effective + ".exec --reset");
            }
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar setkey [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --key <k>             设定全局当前 key（必填，与 --clear 二选一）");
        System.out.println("  --clear               清除全局 key");
        System.out.println("  --address <address>   agent tcpserver 地址，默认 127.0.0.1");
        System.out.println("  --port <port>         agent tcpserver 端口，默认 6300");
        System.out.println("  --retry <count>       连接失败重试次数，默认 0");
        System.out.println("  --quiet               减少输出");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  setkey --key case-A --address 172.16.11.13 --port 6300");
        System.out.println("  # 跑用例 A，然后：dump --key case-A --destfile a.exec --reset");
        System.out.println("  setkey --clear");
    }
}
