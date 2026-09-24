package peruser.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.MultiReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.csv.CSVFormatter;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.xml.XMLFormatter;

/**
 * incremental 子命令：两版 classfiles 比对差异，报告里只保留变更位置的覆盖率与代码着色。
 *
 * 行级精度有两条路径（见 ClassDiff）：
 *   1. 传了 --sourcefiles + --old-sourcefiles → 源码文本 LCS，最准；
 *   2. 只传 --sourcefiles（或都不传）→ 自动改用两版字节码的指令序列 LCS，同样是行级，
 *      只是没有源码页/着色的情况下看不到高亮。
 */
public final class IncrementalCommand {

    private static final ISourceFileLocator NO_SOURCE = new ISourceFileLocator() {

        public Reader getSourceFile(String packageName, String fileName) {
            return null;
        }

        public int getTabWidth() {
            return 4;
        }
    };

    private IncrementalCommand() {
    }

    static void execute(String[] argv) throws Exception {
        if (argv.length == 0 || CliArgs.has(argv, new String[]{"help"})) {
            usage();
            return;
        }
        boolean quiet = CliArgs.has(argv, new String[]{"quiet"});
        CliArgs.warnUnknown(argv, new String[]{"classfiles", "old-classfiles", "sourcefiles",
                "old-sourcefiles", "html", "xml", "csv", "json", "encoding", "name", "tabwidth",
                "execdir", "quiet", "help"});
        String encoding = CliArgs.value(argv, "encoding", "UTF-8");
        String bundleName = CliArgs.value(argv, "name", "xiaoxiao-jacoco");
        int tabWidth = parseInt(CliArgs.value(argv, "tabwidth", "4"), 4);

        List<String> newClassPaths = CliArgs.pathList(argv, new String[]{"classfiles"});
        List<String> newSourcePaths = CliArgs.pathList(argv, new String[]{"sourcefiles"});
        List<String> oldClassPaths = CliArgs.pathList(argv, new String[]{"old-classfiles"});
        List<String> oldSourcePaths = CliArgs.pathList(argv, new String[]{"old-sourcefiles"});

        List<File> execFiles = new ArrayList<File>();
        for (String raw : CliArgs.positional(argv)) {
            File f = new File(raw);
            if (!f.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: 找不到文件: " + raw);
                continue;
            }
            if (!f.isFile() || !raw.endsWith(".exec")) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: 忽略非 exec 的位置参数: " + raw);
                continue;
            }
            execFiles.add(f);
        }
        for (String dir : CliArgs.pathList(argv, new String[]{"execdir"})) {
            execFiles.addAll(findExecs(new File(dir)));
        }

        String html = CliArgs.value(argv, "html", null);
        String xml = CliArgs.value(argv, "xml", null);
        String csv = CliArgs.value(argv, "csv", null);
        String json = CliArgs.value(argv, "json", null);

        if (execFiles.isEmpty()) {
            throw new CliUsageException("no exec files found (pass exec files directly or use --execdir <dir>)");
        }
        if (newClassPaths.isEmpty()) {
            throw new CliUsageException("missing --classfiles <path>（新版本 classfiles）");
        }
        if (oldClassPaths.isEmpty()) {
            throw new CliUsageException("missing --old-classfiles <path>（旧版本 classfiles，用于比对）");
        }
        if (html == null && xml == null && csv == null && json == null) {
            throw new CliUsageException(
                    "at least one output must be specified: --html <dir> / --xml <file> / --csv <file> / --json <file>");
        }

        Charset charset = Charset.forName(encoding);
        ClassDiff.Result diff = ClassDiff.diff(filesOf(oldClassPaths), filesOf(newClassPaths),
                filesOf(oldSourcePaths), filesOf(newSourcePaths), charset);
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] diff: 新增类 " + diff.addedClasses
                    + "，修改类 " + diff.modifiedClasses
                    + "，删除类 " + diff.removedClasses
                    + "，变更行 " + diff.changedLineCount
                    + "（" + diff.levelNote + "）");
        }

        ExecFileLoader loader = new ExecFileLoader();
        for (File exec : execFiles) {
            loader.load(exec);
        }
        ExecutionDataStore store = loader.getExecutionDataStore();
        SessionInfoStore sessions = loader.getSessionInfoStore();

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, (ICoverageVisitor) builder);
        for (String path : newClassPaths) {
            File file = new File(path);
            if (!file.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: class path not found: " + path);
                continue;
            }
            AnalyzePaths.analyzePathInto(analyzer, file);
        }
        AnalyzePaths.warnIfNoExecData(builder, store);

        IBundleCoverage bundle = builder.getBundle(bundleName);
        IBundleCoverage filtered = IncrementalCoverage.filter(bundle, diff);

        writeReports(filtered, sessions, store, newSourcePaths, html, xml, csv, encoding, tabWidth, quiet);

        File jsonFile = json != null ? new File(json)
                : (html != null ? new File(html, "incremental-diff.json") : null);
        IncrementalSummary.write(diff, filtered, filesOf(newSourcePaths), jsonFile,
                html != null ? new File(html, "incremental-summary.html") : null, charset, quiet);
    }

    private static void writeReports(IBundleCoverage bundle, SessionInfoStore sessions,
                                     ExecutionDataStore store, List<String> sourcePaths,
                                     String html, String xml, String csv, String encoding,
                                     int tabWidth, boolean quiet) throws IOException {
        List<IReportVisitor> visitors = new ArrayList<IReportVisitor>();
        List<FileOutputStream> streams = new ArrayList<FileOutputStream>();
        try {
            if (html != null) {
                File dir = new File(html);
                dir.mkdirs();
                HTMLFormatter formatter = new HTMLFormatter();
                formatter.setOutputEncoding(encoding);
                visitors.add(formatter.createVisitor(new FileMultiReportOutput(dir)));
            }
            if (xml != null) {
                File out = resolveFile(xml, "jacoco.xml");
                XMLFormatter formatter = new XMLFormatter();
                formatter.setOutputEncoding(encoding);
                FileOutputStream fos = new FileOutputStream(out);
                streams.add(fos);
                visitors.add(formatter.createVisitor(fos));
            }
            if (csv != null) {
                File out = resolveFile(csv, "jacoco.csv");
                CSVFormatter formatter = new CSVFormatter();
                formatter.setOutputEncoding(encoding);
                FileOutputStream fos = new FileOutputStream(out);
                streams.add(fos);
                visitors.add(formatter.createVisitor(fos));
            }
            IReportVisitor visitor = new MultiReportVisitor(visitors);
            visitor.visitInfo(sessions.getInfos(), store.getContents());
            visitor.visitBundle(bundle, sourceLocator(sourcePaths, encoding, tabWidth));
            visitor.visitEnd();
        } finally {
            for (OutputStream os : streams) {
                try {
                    os.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        if (!quiet) {
            StringBuilder sb = new StringBuilder("[xiaoxiao-jacoco-cli] incremental report:");
            if (html != null) {
                sb.append(" html=").append(new File(html).getAbsolutePath());
            }
            if (xml != null) {
                sb.append(" xml=").append(resolveFile(xml, "jacoco.xml").getAbsolutePath());
            }
            if (csv != null) {
                sb.append(" csv=").append(resolveFile(csv, "jacoco.csv").getAbsolutePath());
            }
            System.out.println(sb);
        }
    }

    private static File resolveFile(String path, String defaultName) {
        File file = new File(path);
        if (file.isDirectory()) {
            return new File(file, defaultName);
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return file;
    }

    private static ISourceFileLocator sourceLocator(List<String> sourcePaths, String encoding, int tabWidth) {
        if (sourcePaths.isEmpty()) {
            return NO_SOURCE;
        }
        MultiSourceFileLocator locator = new MultiSourceFileLocator(tabWidth);
        for (String path : sourcePaths) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                continue;
            }
            locator.add(new DirectorySourceFileLocator(dir, encoding, tabWidth));
        }
        return locator;
    }

    private static List<File> findExecs(File dir) {
        List<File> out = new ArrayList<File>();
        collectRecursively(dir, out, 0);
        return out;
    }

    private static void collectRecursively(File dir, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 8) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectRecursively(child, out, depth + 1);
            } else if (child.getName().endsWith(".exec")) {
                out.add(child);
            }
        }
    }

    static List<File> filesOf(List<String> paths) {
        List<File> out = new ArrayList<File>();
        for (String path : paths) {
            out.add(new File(path));
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    static void usage() {
        System.out.println("usage: xiaoxiao-jacoco-cli incremental [<execfiles> ...] --classfiles <path> [options]");
        System.out.println();
        System.out.println("  两个版本的 classfiles 做差异比对，报告里只保留变更位置的覆盖率与代码着色");
        System.out.println("  （报告仍是原生 JaCoCo 报告；未变更的行不着色、也不计入统计）");
        System.out.println();
        System.out.println("  --classfiles <path>      新版本 class 目录 / jar（可重复）");
        System.out.println("  --old-classfiles <path>  旧版本 class 目录 / jar（可重复，用于比对）");
        System.out.println("  --sourcefiles <path>     新版本源码根目录（可重复）：HTML 代码着色必须");
        System.out.println("  --old-sourcefiles <path> 旧版本源码根目录（可重复）：可选");
        System.out.println();
        System.out.println("  【--old-sourcefiles 可以省略】");
        System.out.println("    有两版源码      → 源码文本 LCS 对齐，精度最高（注释行也能识别）");
        System.out.println("    只有 --sourcefiles → 自动改用两版字节码的指令序列 LCS，同样是行级精度，");
        System.out.println("                        新版源码仍正常用于 HTML 着色");
        System.out.println("    两者都不传      → 同样是行级精度，但没有源码页（无法着色）");
        System.out.println("  --execdir <dir>          收集该目录下所有 *.exec");
        System.out.println("  --html <dir>             HTML 报告输出目录（另出 incremental-summary.html）");
        System.out.println("  --xml <file>             XML 报告；传目录则写 <dir>/jacoco.xml");
        System.out.println("  --csv <file>             CSV 报告；传目录则写 <dir>/jacoco.csv");
        System.out.println("  --json <file>            差异明细 JSON（默认 <html>/incremental-diff.json）");
        System.out.println("  --encoding <charset>     默认 UTF-8");
        System.out.println("  --name <name>            bundle 名称，默认 xiaoxiao-jacoco");
        System.out.println("  --tabwidth <n>           默认 4");
        System.out.println("  --quiet                  减少输出");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar xiaoxiao-jacoco-cli.jar incremental coverage/exec-1.exec \\");
        System.out.println("       --classfiles build2/classes --sourcefiles build2/src/main/java \\");
        System.out.println("       --old-classfiles build1/classes \\");
        System.out.println("       --html reports-incr");
        System.out.println();
        System.out.println("  # 有旧版源码时补上 --old-sourcefiles，精度更准（注释/格式改动也能识别）");
        System.out.println("  java -jar xiaoxiao-jacoco-cli.jar incremental coverage/exec-1.exec \\");
        System.out.println("       --classfiles build2/classes --sourcefiles build2/src/main/java \\");
        System.out.println("       --old-classfiles build1/classes --old-sourcefiles build1/src/main/java \\");
        System.out.println("       --html reports-incr");
    }
}
