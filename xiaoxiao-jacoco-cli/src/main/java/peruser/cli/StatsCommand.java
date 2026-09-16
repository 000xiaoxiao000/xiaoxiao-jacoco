package peruser.cli;

import java.util.List;

/**
 * stats 命令：实时查看 agent 的插桩与采集概况 —— 「到底插桩了没有 / 插了哪些类 / 各 key 采到多少」。
 *
 * <pre>
 *   stats [--address &lt;address&gt;] [--port &lt;port&gt;] [--limit &lt;n&gt;] [--retry &lt;count&gt;] [--quiet]
 * </pre>
 *
 * 这是 xiaoxiao-jacoco 的专有扩展（私有块 0x43/0x22）。官方 agent 不认识该块，
 * 会退化成一次普通 dump，此时本命令会提示「agent 不支持 stats，请升级 xiaoxiao-jacoco-agent」。
 */
public final class StatsCommand {

    private StatsCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final String address = CliArgs.value(args, "address", "127.0.0.1");
        final int port = Integer.parseInt(CliArgs.value(args, "port", "6300"));
        final int limit = Integer.parseInt(CliArgs.value(args, "limit", "50"));
        final int retry = Integer.parseInt(CliArgs.value(args, "retry", "0"));
        final boolean quiet = CliArgs.has(args, "quiet");
        CliArgs.warnUnknown(args, "address", "port", "limit", "retry", "quiet", "help");

        final RemoteDump.StatsReport rep = RemoteDump.fetchStats(address, port, limit, retry, 1000L);

        if (rep.classesInstrumented < 0) {
            System.out.println("[xiaoxiao-jacoco-cli] agent on " + address + ":" + port
                    + " 不支持 stats（没返回概况数据）");
            System.out.println("  -> 请换成最新版的 xiaoxiao-jacoco-agent.jar 并重启被测应用");
            return;
        }

        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] stats on " + address + ":" + port);
            System.out.println();
        }

        System.out.println("插桩情况");
        System.out.println("  transformer 看到的类        " + rep.classesSeen);
        System.out.println("  已插桩的类                  " + rep.classesInstrumented
                + (rep.classesInstrumented == 0 ? "   <-- 一个类都没插桩，覆盖率必然为空" : ""));
        System.out.println("  无 source location 被跳过   " + rep.classesNoLocation
                + (rep.classesNoLocation > 0 ? "   <-- Spring Boot 可执行 jar 常见，加 inclnolocationclasses=true" : ""));
        System.out.println();

        System.out.println("请求归属");
        System.out.println("  HTTP 入口钩子触发次数       " + rep.requestsHooked);
        System.out.println("  成功读到 key 的次数         " + rep.requestsTagged);
        System.out.println();

        System.out.println("各 key 采集到的覆盖");
        if (rep.keyStats.isEmpty()) {
            System.out.println("  (还没有任何 key 的数据)");
        } else {
            System.out.println("  " + pad("key", 24) + pad("classes", 9) + pad("probes", 9)
                    + pad("covered", 9) + "覆盖率");
            for (RemoteDump.KeyStatLine k : rep.keyStats) {
                System.out.println("  " + pad(k.key, 24) + pad(String.valueOf(k.classes), 9)
                        + pad(String.valueOf(k.probes), 9) + pad(String.valueOf(k.covered), 9)
                        + pct(k.covered, k.probes));
            }
        }
        System.out.println();

        final int shown = rep.instrumented.size();
        final long total = rep.classesInstrumented;
        System.out.println("已插桩的类" + (shown < total ? "（前 " + shown + " / 共 " + total + " 个；"
                + "想看全部加 --limit 0，想少看加 --limit 20）" : "（共 " + total + " 个）"));
        if (rep.instrumented.isEmpty()) {
            System.out.println("  (空)");
        } else {
            for (String n : rep.instrumented) {
                System.out.println("  " + n);
            }
        }

        if (rep.classesInstrumented == 0) {
            System.out.println();
            System.out.println("诊断：一个类都没插桩（最常见原因是 includes 没匹配上）");
            System.out.println("  -> includes 匹配的是【VM 类名】(com/foo/Bar)，是全匹配（不是前缀匹配）；"
                    + "不是 URL 路径、不是模块名、不是包名简写");
            if (!rep.suggestedIncludes.isEmpty()) {
                System.out.println("  -> 该进程里已加载的类，包名样例：" + rep.suggestedIncludes);
                System.out.println("     建议改成 includes=" + rep.suggestedIncludes.get(0)
                        + "（或先 includes=* 验证链路，再收窄）");
            }
            if (!rep.skippedSamples.isEmpty()) {
                System.out.println("  -> 被 includes 过滤掉的类样例：" + rep.skippedSamples);
            }
            if (rep.classesNoLocation > 0) {
                System.out.println("  -> 有 " + rep.classesNoLocation + " 个类没有 source location，"
                        + "会被默认跳过：给 agent 加 inclnolocationclasses=true 后重启");
            }
            System.out.println("  -> 改完 agent 参数必须重启被测应用才生效");
        } else if (rep.requestsTagged == 0) {
            System.out.println();
            System.out.println("诊断：类已插桩，但一次请求都没读到 key，覆盖率会被丢弃");
            System.out.println("  -> " + (rep.requestsHooked == 0
                    ? "HTTP 入口钩子一次都没触发（请求没经过 HttpServlet.service？非 Servlet 栈如 WebFlux？）"
                    : "钩子触发了 " + rep.requestsHooked + " 次，但 headerkey 指定的请求头一次都没读到"
                      + "（头名写错 / 请求没带上？）"));
        }
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) {
            return s.substring(0, Math.max(0, w - 1)) + " ";
        }
        final StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String pct(int covered, int total) {
        if (total <= 0) {
            return "-";
        }
        return String.format("%.1f%%", covered * 100.0 / total);
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar stats [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --address <address>   agent tcpserver 地址，默认 127.0.0.1");
        System.out.println("  --port <port>         agent tcpserver 端口，默认 6300");
        System.out.println("  --limit <n>           最多列出多少个已插桩类名，默认 50；0 表示全部");
        System.out.println("  --retry <count>       连接失败重试次数，默认 0");
        System.out.println("  --quiet               减少输出");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  stats --address 172.16.11.13 --port 6300");
        System.out.println("  stats --address 172.16.11.13 --port 6300 --limit 200");
        System.out.println("  # 看有没有插桩、插了哪些类、每个 key 各采到多少探针");
    }
}
