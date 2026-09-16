package peruser.cli;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * execinfo 命令：查看 exec 文件里的 session 与各类探针覆盖情况（官方 jacococli execinfo 的全部参数）。
 *
 * Usage: execinfo [<execfiles> ...] [--verbose] [--quiet] [--help]
 */
public final class ExecInfoCommand {

    private ExecInfoCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean verbose = CliArgs.has(args, "verbose");
        final boolean quiet = CliArgs.has(args, "quiet");
        List<String> files = CliArgs.positional(args);
        for (String dir : CliArgs.pathList(args, "execdir")) {
            File[] top = new File(dir).listFiles((d, n) -> n.endsWith(".exec"));
            if (top != null) {
                for (File f : top) files.add(f.getPath());
            }
        }
        if (files.isEmpty()) {
            throw new CliUsageException("no exec files given");
        }
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (String file : files) {
            final File f = new File(file);
            if (!f.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: not found: " + file);
                continue;
            }
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] exec: " + f.getPath() + " (" + f.length() + " bytes)");
            }
            final ExecFileLoader loader = new ExecFileLoader();
            try {
                loader.load(f);
            } catch (IOException e) {
                // nc/telnet 直连 tcpserver 抓到的是【裸协议流】，末尾多一个 CMDOK(0x20) 块，
                // 它不是 exec 文件格式的一部分，官方 jacococli 由客户端剥离后才落盘。
                throw new IOException("failed to read " + f.getPath() + ": " + e.getMessage()
                        + " (若是 nc 直连 tcpserver 抓的裸流，请用 `dump` 命令抓取)", e);
            }
            final List<SessionInfo> sessions = loader.getSessionInfoStore().getInfos();
            System.out.println("  Sessions: " + sessions.size());
            for (SessionInfo info : sessions) {
                System.out.println("    " + info.getId() + "  start=" + fmt.format(info.getStartTimeStamp())
                        + "  dump=" + fmt.format(info.getDumpTimeStamp()));
            }
            int coveredClasses = 0;
            long totalProbes = 0;
            long coveredProbes = 0;
            for (ExecutionData d : loader.getExecutionDataStore().getContents()) {
                int hits = 0;
                for (boolean b : d.getProbes()) {
                    if (b) hits++;
                }
                totalProbes += d.getProbes().length;
                coveredProbes += hits;
                if (hits > 0) coveredClasses++;
                if (verbose) {
                    System.out.println("    " + d.getName() + "  probes=" + d.getProbes().length
                            + " covered=" + hits + " id=" + Long.toHexString(d.getId()));
                }
            }
            int classes = loader.getExecutionDataStore().getContents().size();
            System.out.println("  Classes: " + classes + " (covered " + coveredClasses + ")");
            System.out.println("  Probes: " + totalProbes + " (covered " + coveredProbes + ")");
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar execinfo [<execfiles> ...] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --verbose   逐类输出探针明细");
        System.out.println("  --execdir   额外读该目录下所有 *.exec");
        System.out.println("  --quiet     减少输出");
    }
}
