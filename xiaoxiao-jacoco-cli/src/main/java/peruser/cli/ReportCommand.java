package peruser.cli;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.data.ExecutionData;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * report 命令：读 exec 文件 + 原始 classfiles，产出 HTML / XML / CSV 报告（官方 jacococli report 的全部参数），
 * 并支持 xiaoxiao-jacoco 专有的「按 key 出报告」与「基线」能力。
 *
 * 官方用法：
 *   report [<execfiles> ...] --classfiles <path> [--sourcefiles <path>] [--html <dir>]
 *          [--xml <file>] [--csv <file>] [--encoding <charset>] [--name <name>] [--tabwidth <n>] [--quiet]
 *
 * 扩展用法（按 key 出报告 / 基线）：
 *   report --execdir <dir> --classfiles <path> [--sourcefiles <path>] [--html <dir>]
 *          [--perkey] [--merge] [--baseline-out <file> | --baseline <file>]
 */
public final class ReportCommand {

    private ReportCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean quiet = CliArgs.has(args, "quiet");
        CliArgs.warnUnknown(args, "classfiles", "sourcefiles", "html", "xml", "csv", "encoding",
                "name", "tabwidth", "execdir", "perkey", "merge", "baseline", "baseline-out",
                "quiet", "help");
        final String encoding = CliArgs.value(args, "encoding", "UTF-8");
        final String name = CliArgs.value(args, "name", "xiaoxiao-jacoco");
        final int tabWidth = parseInt(CliArgs.value(args, "tabwidth", "4"), 4);

        final List<String> classPaths = CliArgs.pathList(args, "classfiles");
        final List<String> sourcePaths = CliArgs.pathList(args, "sourcefiles");

        final List<File> execFiles = new ArrayList<>();
        for (String p : CliArgs.positional(args)) {
            File f = new File(p);
            if (f.exists()) execFiles.add(f);
        }
        final List<String> execDirs = CliArgs.pathList(args, "execdir");
        for (String dir : execDirs) {
            execFiles.addAll(findExecs(new File(dir)));
        }

        final String html = CliArgs.value(args, "html", null);
        final String xml = CliArgs.value(args, "xml", null);
        final String csv = CliArgs.value(args, "csv", null);
        final String baselineOut = CliArgs.value(args, "baseline-out", null);
        final String baselineIn = CliArgs.value(args, "baseline", null);

        if (execFiles.isEmpty()) {
            throw new CliUsageException("no exec files found (pass exec files directly or use --execdir <dir>)");
        }
        if (classPaths.isEmpty()) {
            throw new CliUsageException("missing --classfiles <path>");
        }

        final boolean perkey = CliArgs.has(args, "perkey") || !execDirs.isEmpty();
        final boolean merge = CliArgs.has(args, "merge");

        // 输出目录：--html 指定；未指定时默认 reports
        final String baseOut = (html != null) ? html : "reports";

        // ===== 1) 采集基线 =====
        if (baselineOut != null) {
            final ExecFileLoader loader = loadExecFiles(execFiles, quiet);
            final BaselineStore bs = BaselineStore.build(loader.getExecutionDataStore(), classPaths);
            bs.build = keyOf(execFiles.get(0));
            bs.write(new File(baselineOut));
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] baseline -> "
                        + new File(baselineOut).getAbsolutePath() + " (methods=" + bs.entries.size() + ")");
            }
            return;
        }

        // ===== 2) 合并（并集）报告 =====
        if (merge) {
            final ExecFileLoader loader = loadExecFiles(execFiles, quiet);
            final ExecutionDataStore store = loader.getExecutionDataStore();
            final File dir = new File(baseOut, "all");
            if (baselineIn != null) {
                BaselineReport.generate(store, classPaths, sourcePaths,
                        BaselineStore.read(new File(baselineIn)), dir, "all");
            } else {
                writeReport(store, loader.getSessionInfoStore(), classPaths, sourcePaths,
                        dir, xmlOf(baseOut, "all", xml), csvOf(baseOut, "all", csv),
                        "all", encoding, tabWidth, quiet);
            }
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] merged report -> " + dir.getAbsolutePath() + "/index.html");
            }
            // --merge 是【额外的】并集报告：多 key 时继续往下出每个 key 各自的报告
            if (!(perkey && execFiles.size() > 1)) {
                return;
            }
        }

        // ===== 3) 按 key / 单份报告 =====
        if (perkey && execFiles.size() > 1) {
            for (File exec : execFiles) {
                final String key = keyOf(exec);
                final ExecFileLoader loader = new ExecFileLoader();
                loader.load(exec);
                final ExecutionDataStore store = loader.getExecutionDataStore();
                final File dir = new File(baseOut, key);
                if (baselineIn != null) {
                    BaselineReport.generate(store, classPaths, sourcePaths,
                            BaselineStore.read(new File(baselineIn)), dir, key);
                } else {
                    writeReport(store, loader.getSessionInfoStore(), classPaths, sourcePaths,
                            dir, xmlOf(baseOut, key, xml), csvOf(baseOut, key, csv),
                            name, encoding, tabWidth, quiet);
                }
                if (!quiet) {
                    System.out.println("[xiaoxiao-jacoco-cli] key=" + key + " -> " + dir.getAbsolutePath() + "/index.html");
                }
            }
            return;
        }

        // 单个 exec（或官方默认：多个 exec 合并成一份）
        final ExecFileLoader loader = loadExecFiles(execFiles, quiet);
        final String key = (execFiles.size() == 1) ? keyOf(execFiles.get(0)) : name;
        final File dir = new File(baseOut, perkey ? key : "");
        if (baselineIn != null) {
            BaselineReport.generate(loader.getExecutionDataStore(), classPaths, sourcePaths,
                    BaselineStore.read(new File(baselineIn)), dir, key);
        } else {
            writeReport(loader.getExecutionDataStore(), loader.getSessionInfoStore(), classPaths, sourcePaths,
                    dir, xml, csv, key, encoding, tabWidth, quiet);
        }
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] report -> " + dir.getAbsolutePath() + "/index.html");
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar report [<execfiles> ...] --classfiles <path> [options]");
        System.out.println();
        System.out.println("Options (JaCoCo official):");
        System.out.println("  --classfiles <path>     原始 class 目录 / jar（可重复传参）");
        System.out.println("  --sourcefiles <path>    源码根目录（可重复传参）");
        System.out.println("  --html <dir>            HTML 报告输出目录");
        System.out.println("  --xml <file>            XML 报告输出文件");
        System.out.println("  --csv <file>            CSV 报告输出文件");
        System.out.println("  --encoding <charset>    源码 / 输出编码，默认 UTF-8");
        System.out.println("  --name <name>           bundle 名称，默认 xiaoxiao-jacoco");
        System.out.println("  --tabwidth <n>          制表符宽度，默认 4");
        System.out.println("  --quiet                 减少输出");
        System.out.println();
        System.out.println("Options (xiaoxiao-jacoco extension):");
        System.out.println("  --execdir <dir>         读该目录下所有 coverage-<key>.exec");
        System.out.println("  --perkey                每个 key 一份报告（使用 --execdir 时默认开启）");
        System.out.println("  --merge                 所有 exec 按 classId OR 合并出一份并集报告");
        System.out.println("  --baseline-out <file>   采集方法级基线 JSON");
        System.out.println("  --baseline <file>       携带基线，回填未变方法的覆盖");
    }

    // ===== 内部实现 =====

    private static ExecFileLoader loadExecFiles(List<File> execFiles, boolean quiet) throws IOException {
        final ExecFileLoader loader = new ExecFileLoader();
        for (File f : execFiles) {
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] loading exec: " + f.getPath());
            }
            loader.load(f);
        }
        return loader;
    }

    /** 生成（HTML 必Always + 可选 XML/CSV）reportDir 下的报告。 */
    private static void writeReport(ExecutionDataStore store, SessionInfoStore sessions,
                                    List<String> classPaths, List<String> sourcePaths,
                                    File reportDir, String xmlFile, String csvFile,
                                    String name, String encoding, int tabWidth, boolean quiet) throws IOException {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(store, builder);
        for (String cp : classPaths) {
            File f = new File(cp);
            if (!f.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: class path not found: " + cp);
                continue;
            }
            AnalyzePaths.analyzePathInto(analyzer, f);
        }
        reportDir.mkdirs();

        final List<IReportVisitor> visitors = new ArrayList<>();
        final List<OutputStream> outputs = new ArrayList<>();
        try {
            final HTMLFormatter html = new HTMLFormatter();
            html.setOutputEncoding(encoding);
            visitors.add(html.createVisitor(new FileMultiReportOutput(reportDir)));

            if (xmlFile != null) {
                File xf = new File(xmlFile);
                mkdirsForFile(xf);
                final XMLFormatter xml = new XMLFormatter();
                xml.setOutputEncoding(encoding);
                OutputStream os = new FileOutputStream(xf);
                outputs.add(os);
                visitors.add(xml.createVisitor(os));
            }
            if (csvFile != null) {
                File cf = new File(csvFile);
                mkdirsForFile(cf);
                final CSVFormatter csv = new CSVFormatter();
                csv.setOutputEncoding(encoding);
                OutputStream os = new FileOutputStream(cf);
                outputs.add(os);
                visitors.add(csv.createVisitor(os));
            }

            final IReportVisitor visitor = new MultiReportVisitor(visitors);
            visitor.visitInfo(sessions.getInfos(), store.getContents());
            visitor.visitBundle(builder.getBundle(name), sourceLocator(sourcePaths, encoding, tabWidth));
            visitor.visitEnd();
        } finally {
            for (OutputStream os : outputs) {
                try {
                    os.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }

        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] analyzed classes=" + builder.getClasses().size());
        }
    }

    private static ISourceFileLocator sourceLocator(List<String> sourcePaths, String encoding, int tabWidth) {
        if (sourcePaths.isEmpty()) {
            return NO_SOURCE;
        }
        final MultiSourceFileLocator multi = new MultiSourceFileLocator(tabWidth);
        for (String sp : sourcePaths) {
            File dir = new File(sp);
            if (dir.isDirectory()) {
                multi.add(new DirectorySourceFileLocator(dir, encoding, tabWidth));
            } else {
                System.err.println("[xiaoxiao-jacoco-cli] warning: source path not a directory: " + sp);
            }
        }
        return multi;
    }

    private static final ISourceFileLocator NO_SOURCE = new ISourceFileLocator() {
        @Override
        public java.io.Reader getSourceFile(String packageName, String fileName) {
            return null;
        }

        @Override
        public int getTabWidth() {
            return 4;
        }
    };

    /** 找 execdir 下的 *.exec（优先顶层，没有则递归兜底）。 */
    private static List<File> findExecs(File dir) {
        final List<File> found = new ArrayList<>();
        File[] top = dir.listFiles((d, n) -> n.endsWith(".exec"));
        if (top != null && top.length > 0) {
            Collections.addAll(found, top);
        } else {
            collectRecursively(dir, found, 0);
        }
        java.util.Collections.sort(found);
        return found;
    }

    private static void collectRecursively(File dir, List<File> found, int depth) {
        if (depth > 6) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) collectRecursively(c, found, depth + 1);
            else if (c.getName().endsWith(".exec")) found.add(c);
        }
    }

    /**
     * exec 文件名 -> key。
     * agent 的文件名规则是 &lt;prefix&gt;-&lt;key&gt;.exec，其中 prefix 默认 coverage、
     * 但会随 outdir/destfile 变化（outdir=cov -> cov-smoke.exec）。
     * 所以剥到【第一个 '-'】为止，而不是写死 coverage-；文件名里没有 '-' 就整名当 key。
     *   coverage-1.exec -> 1 ；cov-smoke.exec -> smoke ；x-user-A.exec -> user-A ；jacoco.exec -> jacoco
     */
    private static String keyOf(File exec) {
        String n = exec.getName();
        if (n.endsWith(".exec")) {
            n = n.substring(0, n.length() - ".exec".length());
        }
        final int dash = n.indexOf('-');
        return dash >= 0 ? n.substring(dash + 1) : n;
    }

    /** perkey 模式下给每个 key 生成独立的 xml 文件名。 */
    private static String xmlOf(String baseOut, String key, String xml) {
        if (xml == null) return null;
        if (xml.endsWith(".xml")) {
            return new File(baseOut, key + ".xml").getPath();
        }
        return new File(xml, key + ".xml").getPath();
    }

    private static String csvOf(String baseOut, String key, String csv) {
        if (csv == null) return null;
        if (csv.endsWith(".csv")) {
            return new File(baseOut, key + ".csv").getPath();
        }
        return new File(csv, key + ".csv").getPath();
    }

    private static void mkdirsForFile(File f) {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 判断 store 是否为空（没有 ExecutionData 条目）。 */
    private static boolean isEmpty(ExecutionDataStore store) {
        for (ExecutionData ignored : store.getContents()) {
            return false;
        }
        return true;
    }
}
