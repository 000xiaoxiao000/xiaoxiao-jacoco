package peruser.cli;

import java.util.List;

/**
 * cli 主入口（xiaoxiao-jacoco-cli），命令与官方 jacococli 保持一致：
 *
 *   java -jar xiaoxiao-jacoco-cli.jar report [<execfiles> ...] --classfiles <path> ...
 *   java -jar xiaoxiao-jacoco-cli.jar merge  [<execfiles> ...] --destfile <path>
 *   java -jar xiaoxiao-jacoco-cli.jar dump   [--address <addr>] [--port <port>] [--destfile <path>]
 *   java -jar xiaoxiao-jacoco-cli.jar instrument [<sourcefiles> ...] --dest <dir>
 *   java -jar xiaoxiao-jacoco-cli.jar classinfo [<classlocations> ...]
 *   java -jar xiaoxiao-jacoco-cli.jar execinfo  [<execfiles> ...]
 *   java -jar xiaoxiao-jacoco-cli.jar version
 *   java -jar xiaoxiao-jacoco-cli.jar help
 *
 * 兼容旧写法：不带命令名、直接以 --execdir 开头时，等价于 report（按 key 逐个出报告）。
 */
public final class CommandLine {

    public static final String VERSION = "1.0.0";

    private CommandLine() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printHelp();
            System.exit(1);
            return;
        }
        String head = args[0];
        String cmd;
        String[] rest;
        if (head.startsWith("-")) {
            // 旧写法（无命令名）：默认 report
            cmd = "report";
            rest = args;
        } else {
            cmd = head;
            rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
        }
        try {
            execute(cmd, rest);
        } catch (CliUsageException e) {
            System.err.println("[xiaoxiao-jacoco-cli] " + e.getMessage());
            System.err.println();
            printCommandUsage(cmd);
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[xiaoxiao-jacoco-cli] error: " + e);
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("    caused by: " + cause);
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }

    private static void execute(String cmd, String[] rest) throws Exception {
        switch (cmd) {
            case "report":
                ReportCommand.execute(rest);
                break;
            case "merge":
                MergeCommand.execute(rest);
                break;
            case "dump":
                DumpCommand.execute(rest);
                break;
            case "dumpclasses":
                DumpClassesCommand.execute(rest);
                break;
            case "instrument":
                InstrumentCommand.execute(rest);
                break;
            case "keys":
                KeysCommand.execute(rest);
                break;
            case "stats":
                StatsCommand.execute(rest);
                break;
            case "setkey":
                SetKeyCommand.execute(rest);
                break;
            case "classinfo":
                ClassInfoCommand.execute(rest);
                break;
            case "execinfo":
                ExecInfoCommand.execute(rest);
                break;
            case "version":
                VersionCommand.execute(rest);
                break;
            case "help":
                printHelp();
                break;
            default:
                throw new CliUsageException("unknown command: " + cmd);
        }
    }

    private static void printCommandUsage(String cmd) {
        if ("report".equals(cmd)) {
            ReportCommand.usage();
        } else if ("dump".equals(cmd)) {
            DumpCommand.usage();
        } else if ("keys".equals(cmd)) {
            KeysCommand.usage();
        } else if ("stats".equals(cmd)) {
            StatsCommand.usage();
        } else if ("dumpclasses".equals(cmd)) {
            DumpClassesCommand.usage();
        } else if ("setkey".equals(cmd)) {
            SetKeyCommand.usage();
        } else {
            System.err.println("usage: " + usageLine(cmd));
        }
    }

    private static String usageLine(String cmd) {
        switch (cmd) {
            case "merge":
                return "xiaoxiao-jacoco-cli merge [<execfiles> ...] --destfile <path> [--append true|false]";
            case "dump":
                return "xiaoxiao-jacoco-cli dump [--address <address>] [--port <port>] [--destfile <path>] [--reset] [--retry <count>] [--key <k>]...";
            case "keys":
                return "xiaoxiao-jacoco-cli keys [--address <address>] [--port <port>] [--retry <count>]";
            case "stats":
                return "xiaoxiao-jacoco-cli stats [--address <address>] [--port <port>] [--limit <n>]";
            case "dumpclasses":
                return "xiaoxiao-jacoco-cli dumpclasses [--address <address>] [--port <port>] [--outdir <dir>] [--zip <file>]";
            case "setkey":
                return "xiaoxiao-jacoco-cli setkey --key <k> | --clear  [--address <address>] [--port <port>]";
            case "instrument":
                return "xiaoxiao-jacoco-cli instrument [<sourcefiles> ...] --dest <dir>";
            case "classinfo":
                return "xiaoxiao-jacoco-cli classinfo [<classlocations> ...] [--verbose]";
            case "execinfo":
                return "xiaoxiao-jacoco-cli execinfo [<execfiles> ...] [--verbose]";
            default:
                return "xiaoxiao-jacoco-cli <command> [options]  (try: help)";
        }
    }

    static void printHelp() {
        System.out.println("xiaoxiao-jacoco-cli " + VERSION + " (JaCoCo compatible command line interface)");
        System.out.println();
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  report [<execfiles> ...] --classfiles <path> [options]   生成覆盖率报告（HTML/XML/CSV）");
        System.out.println("  merge  [<execfiles> ...] --destfile <path>               合并多个 exec 文件");
        System.out.println("  dump   [--address <addr>] [--port <port>] [--destfile <path>]   从 agent(tcpserver) 抓取 exec");
        System.out.println("  keys   [--address <addr>] [--port <port>]                        列出 agent 已采集的所有 key");
        System.out.println("  stats  [--address <addr>] [--port <port>] [--limit <n>]          查看插桩了什么/多少、各 key 采到多少");
        System.out.println("  dumpclasses [--address <addr>] [--port <port>] [--outdir <dir>] [--zip <file>]   从 agent 内存拉回被插桩类的原始字节码");
        System.out.println("  setkey --key <k> [--address <addr>] [--port <port>]   远程设定全局当前 key（零改业务代码）");
        System.out.println("  instrument [<sourcefiles> ...] --dest <dir>               离线插桩 class/jar");
        System.out.println("  classinfo [<classlocations> ...]                          查看 class/jar 里的类与探针信息");
        System.out.println("  execinfo [<execfiles> ...]                                查看 exec 文件里的 session 与类信息");
        System.out.println("  version                                                   版本信息");
        System.out.println("  help                                                      本帮助");
        System.out.println();
        System.out.println("report [options]:");
        System.out.println("  --classfiles <path>     原始 class 目录 / jar（可重复传参）");
        System.out.println("  --sourcefiles <path>    源码根目录（可重复传参），用于渲染源码");
        System.out.println("  --html <dir>            HTML 报告输出目录");
        System.out.println("  --xml <file>            XML 报告（原生 JaCoCo 格式）；传目录则写 <dir>/jacoco.xml");
        System.out.println("  --csv <file>            CSV 报告（原生 JaCoCo 格式）；传目录则写 <dir>/jacoco.csv");
        System.out.println("  --encoding <charset>    源码/输出编码，默认 UTF-8");
        System.out.println("  --name <name>           bundle 名称，默认 xiaoxiao-jacoco");
        System.out.println("  --tabwidth <n>          制表符宽度，默认 4");
        System.out.println("  --quiet                 减少输出");
        System.out.println();
        System.out.println("report 扩展（xiaoxiao-jacoco 专有）:");
        System.out.println("  --execdir <dir>         收集该目录下所有 *.exec，默认【合并成一份】报告（原生并集）");
        System.out.println("  --perkey                每个 key 一份报告（需显式开启；key 取自文件名 <key> 段）");
        System.out.println("  --merge                 所有 exec 按 classId OR 合并，额外出一份 all/ 并集报告");
        System.out.println("  --baseline-out <file>   采集方法级基线 JSON（Build N 跑完后）");
        System.out.println("  --baseline <file>       携带基线（Build N+1），回填未变方法的覆盖");
        System.out.println();
        System.out.println("dump 扩展（按 key 分离，xiaoxiao-jacoco 专有）:");
        System.out.println("  --key <k>              只抓该 key（X-Coverage-Key 的值）；可重复或逗号分隔（1,2）");
        System.out.println("                         多个 key 时分别写 <destfile 去掉 .exec>-<k>.exec");
        System.out.println("  keys 命令              先列出 agent 当前有哪些 key");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar xiaoxiao-jacoco-cli.jar report coverage/coverage-1.exec \\");
        System.out.println("       --classfiles target/classes --sourcefiles src/main/java --html reports");
        System.out.println("  java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage --classfiles target/classes \\");
        System.out.println("       --sourcefiles src/main/java --xml reports       # 合并成一份 jacoco.xml");
    }

    /** 是否静默输出（--quiet）。 */
    static boolean quiet(String[] args, List<String> ignored) {
        return CliArgs.has(args, "quiet");
    }
}
