package peruser.cli;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * report 命令：读 exec 文件 + 原始 classfiles，产出覆盖率报告。
 *
 * 支持官方 jacococli report 的【全部原生格式】，且每种格式相互独立、按需生成：
 *   --html <dir>   HTML 报告（原生 jacococli 完全一致的结构，含 index.html + 各类页）
 *   --xml  <file>  XML 报告（原生 JaCoCo report.dtd 格式，可被 SonarQube / Jenkins 插件等直接消费）
 *   --csv  <file>  CSV 报告（原生 JaCoCo 列格式）
 *
 * 与原生差异点（xiaoxiao-jacoco 扩展）：
 *   --execdir <dir>   读该目录下所有 *.exec，默认【合并成一份】报告（与原生多 exec 并集一致）；
 *                      想按 key 拆分出多份报告需显式加 --perkey。
 *   --perkey          每个 key 一份报告（按各自 exec 文件名命名，如 coverage-1.exec -> coverage-1.xml）
 *   --merge           所有 exec 按 classId OR 合并，额外出一份 all/ 并集报告
 *   报告文件名：--xml/--csv 传【目录】时，单文件默认按输入 exec 文件名命名（coverage-1.exec -> coverage-1.xml；
 *              --perkey 下每份按各自 exec 文件名；多 exec 合并（无 perkey）用 jacoco.xml）。显式传 .xml/.csv 文件则原样。
 *   --baseline-out/-in 方法级基线采集 / 增量携带（仅影响 HTML）
 *
 * 官方用法：
 *   report [<execfiles> ...] --classfiles <path> [--classfiles <path> ...]
 *          [--sourcefiles <path> ...] [--html <dir>] [--xml <file>] [--csv <file>]
 *          [--encoding <enc>] [--name <name>] [--tabwidth <n>] [--quiet]
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

        // ----- 各原生格式独立解析 -----
        // xmlArg/csvArg 保留原始参数；最终文件名在调用点按「输入 exec 文件名」派生（目录 -> <dir>/<base>.<ext>）
        final String htmlDir = CliArgs.value(args, "html", null);
        final String xmlArg = CliArgs.value(args, "xml", null);
        final String csvArg = CliArgs.value(args, "csv", null);
        final String baselineOut = CliArgs.value(args, "baseline-out", null);
        final String baselineIn = CliArgs.value(args, "baseline", null);

        if (execFiles.isEmpty()) {
            throw new CliUsageException("no exec files found (pass exec files directly or use --execdir <dir>)");
        }
        if (classPaths.isEmpty()) {
            throw new CliUsageException("missing --classfiles <path>");
        }
        // 原生行为：至少指定一种输出格式
        if (htmlDir == null && xmlArg == null && csvArg == null && baselineOut == null) {
            throw new CliUsageException("at least one output format must be specified: "
                    + "--html <dir> / --xml <file> / --csv <file>");
        }

        // perkey 默认关闭（与原生一致）；仅 --execdir 收集文件、默认合并成一份；显式 --perkey 才拆分
        final boolean perkey = CliArgs.has(args, "perkey");
        final boolean merge = CliArgs.has(args, "merge");

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
            final File htmlAll = htmlDir != null ? new File(htmlDir, "all") : null;
            if (baselineIn != null) {
                BaselineReport.generate(store, classPaths, sourcePaths,
                        BaselineStore.read(new File(baselineIn)), dirOrNull(htmlAll, "all"), "all");
            } else {
                writeReport(store, loader.getSessionInfoStore(), classPaths, sourcePaths,
                        htmlAll, resolveReportFile(xmlArg, ".xml", "all"), resolveReportFile(csvArg, ".csv", "all"),
                        "all", encoding, tabWidth, quiet, "merge");
            }
            // --merge 是【额外的】并集报告：多 key 时继续往下出每个 key 各自的报告
            if (!(perkey && execFiles.size() > 1)) {
                return;
            }
        }

        // ===== 3) 按 key / 按文件拆分报告（每 exec 一份，文件名 = exec 文件名） =====
        if (perkey && execFiles.size() > 1) {
            for (File exec : execFiles) {
                final String stem = stemOf(exec);
                final ExecFileLoader loader = new ExecFileLoader();
                loader.load(exec);
                final ExecutionDataStore store = loader.getExecutionDataStore();
                final File htmlKeyDir = htmlDir != null ? new File(htmlDir, stem) : null;
                if (baselineIn != null) {
                    BaselineReport.generate(store, classPaths, sourcePaths,
                            BaselineStore.read(new File(baselineIn)), dirOrNull(htmlKeyDir, stem), stem);
                } else {
                    writeReport(store, loader.getSessionInfoStore(), classPaths, sourcePaths,
                            htmlKeyDir,
                            resolveReportFile(xmlArg, ".xml", stem),
                            resolveReportFile(csvArg, ".csv", stem),
                            stem, encoding, tabWidth, quiet, "exec=" + exec.getName());
                }
            }
            return;
        }

        // 单个 exec（或官方默认：多个 exec 合并成一份并集报告）
        final ExecFileLoader loader = loadExecFiles(execFiles, quiet);
        // 目录型 --xml/--csv 文件名默认 = 输入 exec 文件名（去掉 .exec）；多 exec 合并（无 perkey）则用 jacoco
        final String singleBase = (execFiles.size() == 1) ? stemOf(execFiles.get(0)) : "jacoco";
        final File htmlUse = htmlDir != null ? new File(htmlDir) : null;
        if (baselineIn != null) {
            BaselineReport.generate(loader.getExecutionDataStore(), classPaths, sourcePaths,
                    BaselineStore.read(new File(baselineIn)), dirOrNull(htmlUse, singleBase), singleBase);
        } else {
            writeReport(loader.getExecutionDataStore(), loader.getSessionInfoStore(), classPaths, sourcePaths,
                    htmlUse,
                    resolveReportFile(xmlArg, ".xml", singleBase),
                    resolveReportFile(csvArg, ".csv", singleBase),
                    name, encoding, tabWidth, quiet, "report");
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar report [<execfiles> ...] --classfiles <path> [options]");
        System.out.println();
        System.out.println("Output formats (native JaCoCo, each independent — specify only what you need):");
        System.out.println("  --html <dir>            HTML 报告输出目录（原生 jacococli 结构）");
        System.out.println("  --xml <file>            XML 报告；传【目录】则写入 <dir>/<exec名>.xml（按输入 exec 文件名命名）");
        System.out.println("  --csv <file>            CSV 报告；传【目录】则写入 <dir>/<exec名>.csv（按输入 exec 文件名命名）");
        System.out.println("  --classfiles <path>     原始 class 目录 / jar（可重复传参）");
        System.out.println("  --sourcefiles <path>    源码根目录（可重复传参）");
        System.out.println("  --encoding <charset>    源码 / 输出编码，默认 UTF-8");
        System.out.println("  --name <name>           bundle 名称，默认 xiaoxiao-jacoco");
        System.out.println("  --tabwidth <n>          制表符宽度，默认 4");
        System.out.println("  --quiet                 减少输出");
        System.out.println();
        System.out.println("Options (xiaoxiao-jacoco extension):");
        System.out.println("  --execdir <dir>         收集该目录下所有 *.exec，默认合并成一份报告（原生并集）");
        System.out.println("  --perkey                每个 key 一份报告（使用 --execdir 时默认开启）");
        System.out.println("  --merge                 所有 exec 按 classId OR 合并，额外出一份 all/ 并集报告");
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

    /**
     * 生成报告。HTML 仅在 htmlDir != null 时产出；XML/CSV 仅在对应文件 != null 时产出。
     * 三种格式都交给原生 JaCoCo Formatter，因此输出与官方 jacococli report 完全一致。
     */
    private static void writeReport(ExecutionDataStore store, SessionInfoStore sessions,
                                    List<String> classPaths, List<String> sourcePaths,
                                    File htmlDir, String xmlFile, String csvFile,
                                    String bundleName, String encoding, int tabWidth, boolean quiet,
                                    String label) throws IOException {
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

        final List<IReportVisitor> visitors = new ArrayList<>();
        final List<OutputStream> outputs = new ArrayList<>();
        try {
            if (htmlDir != null) {
                htmlDir.mkdirs();
                final HTMLFormatter html = new HTMLFormatter();
                html.setOutputEncoding(encoding);
                visitors.add(html.createVisitor(new FileMultiReportOutput(htmlDir)));
            }
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
            visitor.visitBundle(builder.getBundle(bundleName), sourceLocator(sourcePaths, encoding, tabWidth));
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
            final StringBuilder msg = new StringBuilder("[xiaoxiao-jacoco-cli] " + label
                    + " analyzed classes=" + builder.getClasses().size());
            if (htmlDir != null) msg.append("; html=").append(htmlDir.getAbsolutePath());
            if (xmlFile != null) msg.append("; xml=").append(xmlFile);
            if (csvFile != null) msg.append("; csv=").append(csvFile);
            System.out.println(msg);
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

    /**
     * 解析 --xml / --csv 的目标：
     *   - null              -> null（不生成该格式）
     *   - 以 .xml/.csv 结尾  -> 视为显式文件，原样返回
     *   - 其他（目录或裸名）  -> 视为目录，写入 <arg>/<baseName>.<ext>
     * baseName 由调用方按输入 exec 文件名派生（coverage-1.exec -> coverage-1）。
     */
    private static String resolveReportFile(String arg, String ext, String baseName) {
        if (arg == null) return null;
        if (arg.endsWith(ext)) return arg;
        return new File(arg, baseName + ext).getPath();
    }

    /** exec 文件名 -> 报告文件名基名（去掉 .exec 后缀）；空名兜底为 jacoco。 */
    private static String stemOf(File exec) {
        String n = exec.getName();
        if (n.endsWith(".exec")) {
            n = n.substring(0, n.length() - ".exec".length());
        }
        return n.isEmpty() ? "jacoco" : n;
    }

    /** baseline 模式只出 HTML：htmlDir 为 null 时退化为一个临时目录（避免 NPE）。 */
    private static File dirOrNull(File htmlDir, String fallbackName) {
        if (htmlDir != null) return htmlDir;
        final File tmp = new File("reports-" + fallbackName);
        tmp.mkdirs();
        return tmp;
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
}
