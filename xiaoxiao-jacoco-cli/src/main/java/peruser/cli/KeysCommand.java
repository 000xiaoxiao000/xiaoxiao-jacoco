package peruser.cli;

import java.util.Set;

/**
 * keys 命令：列出 agent（output=tcpserver）当前已采集到的所有 key（X-Coverage-Key 的值）。
 *
 * <pre>
 *   keys [--address &lt;address&gt;] [--port &lt;port&gt;] [--retry &lt;count&gt;] [--quiet]
 * </pre>
 *
 * 这是 xiaoxiao-jacoco 的专有扩展；官方 agent 不支持该私有块，会按「连上不发命令」处理并回一次全量 dump。
 */
public final class KeysCommand {

    private KeysCommand() {
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
        CliArgs.warnUnknown(args, "address", "port", "retry", "quiet", "help");

        final RemoteDump.KeyReport rep = RemoteDump.listKeys(address, port, retry, 1000L);
        final Set<String> keys = rep.keys;
        if (keys.isEmpty()) {
            System.out.println("[xiaoxiao-jacoco-cli] no key collected yet on " + address + ":" + port);
            System.out.println();
            // 光说「没有 key」没用：把「没插桩」还是「没读到 header」分开讲，一眼能定位
            if (rep.classesInstrumented == 0) {
                System.out.println("  ! agent 到目前为止【一个类都没有插桩】(classes instrumented=0)");
                System.out.println("    -> 根因：includes/excludes 没匹配上。includes 匹配的是【VM 类名】(com/foo/Bar)，");
                System.out.println("       不是 URL 路径（/web/testWeb）、不是模块名（webServer）、不是包名简写。");
                if (!rep.suggestedIncludes.isEmpty()) {
                    System.out.println("    -> 该进程里已加载的类，包名样例：" + rep.suggestedIncludes);
                    System.out.println("       建议把 agent 参数改成 includes=" + rep.suggestedIncludes.get(0)
                            + "（或先 includes=* 验证链路，再收窄）");
                }
                if (rep.classesNoLocation > 0) {
                    System.out.println("    -> 另检测到 " + rep.classesNoLocation
                            + " 个类【没有 source location】（Spring Boot 可执行 jar 常见），"
                            + "这类默认会被跳过：给 agent 加 inclnolocationclasses=true 后重启。");
                }
                System.out.println("    -> 改完必须重启被测应用才生效。");
            } else if (rep.requestsTagged == 0) {
                System.out.println("  ! 类已插桩 " + rep.classesInstrumented + " 个，但一次请求都没读到 key"
                        + "（请求头 X-Coverage-Key / headerkey 指定的头名）");
                System.out.println("    -> 根因：" + (rep.requestsHooked == 0
                        ? "HTTP 入口钩子一次都没触发 —— 请求没经过 HttpServlet.service（非 Servlet 栈？如 WebFlux/Netty）"
                        : "钩子触发了 " + rep.requestsHooked + " 次，但请求头一次都没读到 —— 头名写错/请求没带该 header"));
                System.out.println("    -> 注意：没读到 key 的请求，其覆盖率会被丢弃，不会出现在任何 .exec 里。");
            } else {
                System.out.println("  -> 已有归属但数据已被 reset 清掉？确认 dump 时没带 --reset。");
            }
            return;
        }
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] " + keys.size() + " key(s) on " + address + ":" + port
                    + "   [classes instrumented=" + rep.classesInstrumented
                    + ", requests hooked=" + rep.requestsHooked + ", tagged=" + rep.requestsTagged + "]");
        }
        for (String k : keys) {
            System.out.println(k);
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar keys [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --address <address>   agent tcpserver 地址，默认 127.0.0.1");
        System.out.println("  --port <port>         agent tcpserver 端口，默认 6300");
        System.out.println("  --retry <count>       连接失败重试次数，默认 0");
        System.out.println("  --quiet               减少输出");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  keys --address 172.16.11.13 --port 6300");
        System.out.println("  # 列出所有 X-Coverage-Key 的取值，然后用 dump --key <k> 逐个抓取");
    }
}
