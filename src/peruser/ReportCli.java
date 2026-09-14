package peruser;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.IMultiReportOutput;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 报告生成器（独立 main）：读 execdir 下所有 coverage-&lt;key&gt;.exec，配合原始 classfiles，
 * 为每个 key 产出一份独立 HTML 覆盖率报告。
 *
 * 用法：
 *   java -cp peruser-jacoco.jar peruser.ReportCli \
 *        --execdir coverage --classes target/out --classes lib/foo.jar \
 *        --sources src/main/java --out reports
 *
 * 报告写到 out/&lt;key&gt;/index.html
 * --sources 可传多个源码根目录；不传则只展示行覆盖率，不渲染源码。
 */
public final class ReportCli {

    public static void main(String[] args) throws Exception {
        String execDir = null;
        String outDir = null;
        String baselineOut = null;   // 非零：把该 exec 的基线（方法级覆盖）写到此文件
        String baselineIn = null;    // 非零：携带合并模式，读此基线文件
        List<String> classPaths = new ArrayList<>();
        List<String> sourcePaths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--execdir":
                    execDir = args[++i];
                    break;
                case "--out":
                    outDir = args[++i];
                    break;
                case "--classes":
                    classPaths.add(args[++i]);
                    break;
                case "--sources":
                    sourcePaths.add(args[++i]);
                    break;
                case "--baseline-out":
                    baselineOut = args[++i];
                    break;
                case "--baseline":
                    baselineIn = args[++i];
                    break;
                default:
                    System.err.println("[peruser-report] unknown arg: " + args[i]);
            }
        }
        if (execDir == null || classPaths.isEmpty()) {
            System.err.println("usage: ReportCli --execdir DIR --classes PATH... [--sources SRC...]"
                    + " [--out DIR] [--baseline-out FILE | --baseline FILE]");
            System.exit(2);
        }
        if (baselineIn != null && outDir == null) {
            System.err.println("[peruser-report] --baseline 模式需要 --out DIR");
            System.exit(2);
        }

        File outRoot = (outDir == null) ? null : new File(outDir);
        if (outRoot != null) {
            outRoot.mkdirs();
        }

        File[] execs = new File(execDir).listFiles((d, n) -> n.endsWith(".exec"));
        if (execs == null || execs.length == 0) {
            System.err.println("[peruser-report] no .exec found in " + execDir);
            System.exit(3);
        }

        for (File exec : execs) {
            String key = exec.getName().replaceAll("^coverage-", "").replaceAll("\\.exec$", "");
            ExecFileLoader loader = new ExecFileLoader();
            try (InputStream in = Files.newInputStream(exec.toPath())) {
                loader.load(in);
            }
            ExecutionDataStore store = loader.getExecutionDataStore();

            if (baselineOut != null) {
                // 仅采集基线（Build N 跑完后）：方法级覆盖 -> JSON
                BaselineStore bs = BaselineStore.build(store, classPaths);
                bs.build = key;
                bs.write(new File(baselineOut));
                System.out.println("[peruser-report] baseline key=" + key + " -> "
                        + new File(baselineOut).getAbsolutePath() + " (methods=" + bs.entries.size() + ")");
                continue;
            }

            if (baselineIn != null) {
                // 携带合并模式（Build N+1）：读基线 + 本次 exec，在探针层回填未变方法的覆盖，
                // 产出与官方一致的标准 JaCoCo HTML 报告
                BaselineStore baseline = BaselineStore.read(new File(baselineIn));
                File keyOut = new File(outRoot, key);
                BaselineReport.generate(store, classPaths, sourcePaths, baseline, keyOut, key);
                continue;
            }

            // 默认：标准 JaCoCo HTML 报告（无携带）
            CoverageBuilder cb = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(store, cb);
            for (String cp : classPaths) {
                analyzePathInto(analyzer, new File(cp));
            }

            File keyOut = new File(outRoot, key);
            keyOut.mkdirs();
            HTMLFormatter fmt = new HTMLFormatter();
            IMultiReportOutput mo = new FileMultiReportOutput(keyOut);
            IReportVisitor v = fmt.createVisitor(mo);
            v.visitInfo(Collections.emptyList(), store.getContents());
            v.visitBundle(cb.getBundle("key-" + key), createSourceLocator(sourcePaths));
            v.visitEnd();

            System.out.println("[peruser-report] key=" + key + " -> "
                    + keyOut.getAbsolutePath() + "/index.html"
                    + " (classes=" + cb.getClasses().size() + ")");
        }
    }

    static void analyzePathInto(Analyzer analyzer, File f) throws IOException {
        if (f.isDirectory()) {
            try (java.util.stream.Stream<Path> stream = Files.walk(f.toPath())) {
                List<Path> files = stream
                        .filter(x -> x.toString().endsWith(".class"))
                        .collect(java.util.stream.Collectors.toList());
                for (Path p : files) {
                    analyzer.analyzeClass(Files.readAllBytes(p), "");
                }
            }
        } else if (f.getName().endsWith(".jar")) {
            try (JarFile jf = new JarFile(f)) {
                for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                    JarEntry je = e.nextElement();
                    if (je.getName().endsWith(".class")) {
                        try (InputStream is = jf.getInputStream(je)) {
                            analyzer.analyzeClass(readAllBytes(is), je.getName());
                        }
                    }
                }
            }
        }
    }

    private static ISourceFileLocator createSourceLocator(List<String> sourcePaths) {
        if (sourcePaths.isEmpty()) {
            return NO_SOURCE;
        }
        MultiSourceFileLocator multi = new MultiSourceFileLocator(4);
        for (String sp : sourcePaths) {
            File dir = new File(sp);
            if (dir.isDirectory()) {
                multi.add(new DirectorySourceFileLocator(dir, "UTF-8", 4));
            } else {
                System.err.println("[peruser-report] warning: source path not a directory: " + sp);
            }
        }
        return multi;
    }

    /** Java 8 兼容的 InputStream -> byte[]（等价于 Java 9 的 InputStream.readAllBytes）。 */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static final ISourceFileLocator NO_SOURCE = new ISourceFileLocator() {
        @Override
        public Reader getSourceFile(String packageName, String fileName) {
            return null; // 不贴源码，仅展示覆盖率
        }

        @Override
        public int getTabWidth() {
            return 4;
        }
    };
}
