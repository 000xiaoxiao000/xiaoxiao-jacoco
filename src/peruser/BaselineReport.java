package peruser;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 携带合并报告（标准 JaCoCo 格式）：
 *
 * 思路：JaCoCo 的 .exec 用 classId 做 key，类字节码一旦改变 classId 就变，旧 exec 无法合并，
 * 导致「同类里 /login 没动、/query 改了」时 /login 的历史覆盖被静默丢弃。
 *
 * 解决办法是在【探针层】做增量携带，使最终报告是 JaCoCo 原生 HTML（与官方 jacococli report 一致）：
 *   1. 对每个原始 class，用 JaCoCo 自身的控制流探针适配器（ClassProbesAdapter/MethodProbesVisitor）
 *      扫描出「方法 -> 探针id -> 行号」映射，以及该类的总探针数。这一步与 JaCoCo Analyzer 内部
 *      使用的探针编号完全一致（同一 ClassProbesAdapter(cv, false) + accept(flags=0)），
 *      因此探针 id 既能对上本次 exec，也能对上合成类的探针数组长度，绝不会出现数组越界。
 *   2. 对 methodHash 与基线一致（方法体未变）的方法，按相对行偏移把基线里记录的覆盖行，
 *      映射成新 class 里的绝对行，再把对应探针置 true。
 *   3. 把修改后的 ExecutionDataStore 交给 JaCoCo 原生 Analyzer + HTMLFormatter，
 *      产出与官方完全一致的标准覆盖率报告（携带的行显示为绿色 covered）。
 */
public final class BaselineReport {

    private BaselineReport() {
    }

    public static void generate(ExecutionDataStore store, List<String> classPaths,
                                List<String> sourcePaths, BaselineStore baseline,
                                File keyOut, String key) throws Exception {
        keyOut.mkdirs();

        // 1) 原始 class 字节 + 方法 hash
        Map<String, byte[]> classBytes = readClassBytes(classPaths);
        Map<String, String> hashes = MethodHasher.hashAll(new ArrayList<>(toFiles(classPaths)));

        // 3) 扫描原始 class 的控制流探针布局（与 JaCoCo Analyzer 完全一致），
        //    对未变方法把基线覆盖行的探针置 true
        //
        // 注意：用 classId 直接从 store 取 ExecutionData，而不是用类名索引。
        // 原因：exec 里记录的类名是「点号」形式（web3Server.controller.Web301Controller），
        // 而 ClassReader.getClassName() 返回的是「斜杠」形式（web3Server/controller/Web301Controller），
        // 按名索引会永远 miss，导致真实 exec 被误判为「无覆盖」而走合成分支、又因 classId 冲突被跳过。
        // 而 Analyzer 内部正是用 CRC64.classId(orig) 在 store 里查 ExecutionData，
        // 所以我们也用同一个 id 取，保证修改的是 Analyzer 将要读取的那一份探针数组。
        int carriedMethods = 0, carriedProbes = 0;
        for (Map.Entry<String, byte[]> e : classBytes.entrySet()) {
            String name = e.getKey();
            byte[] orig = e.getValue();
            ClassProbes cp = scanProbes(orig);

            long classId = CRC64.classId(orig);
            ExecutionData ed = store.get(classId);
            boolean synthetic = false;
            if (ed == null) {
                int count = cp.totalProbeCount < 0 ? 0 : cp.totalProbeCount;
                ed = new ExecutionData(classId, name, count);
                try {
                    store.put(ed);
                } catch (IllegalStateException ex) {
                    System.err.println("[peruser-report] warn: classId 冲突，跳过合成 " + name);
                    continue;
                }
                synthetic = true;
            }
            boolean[] probes = ed.getProbes();

            for (MethodProbes mp : cp.methods.values()) {
                if (mp.firstLine < 0) {
                    continue; // 无行号信息，无法做相对偏移携带
                }
                String mk = name + "#" + mp.name + "#" + mp.desc;
                String hash = hashes.get(mk);
                if (hash == null || !baseline.byHash.containsKey(name + "#" + hash)) {
                    continue; // 方法体变了、跨类碰撞、或基线无记录 -> 不携带（用本次真实覆盖）
                }
                BaselineStore.Entry be = baseline.byHash.get(name + "#" + hash);
                int baseFirst = be.firstLine;
                for (int oldLine : be.covered) {
                    int newLine = mp.firstLine + (oldLine - baseFirst);
                    for (ProbeInfo pi : mp.probes) {
                        if (pi.line == newLine && pi.id < probes.length) {
                            if (!probes[pi.id]) {
                                probes[pi.id] = true;
                                carriedProbes++;
                            }
                        }
                    }
                }
                carriedMethods++;
            }
            if (synthetic) {
                // 合成类：已放入 store，供下方原生 Analyzer 按 classId 命中
            }
        }

        // 4) 标准 JaCoCo HTML 报告（与官方 jacococli report 格式一致）
        CoverageBuilder cb = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, cb);
        for (String cp : classPaths) {
            ReportCli.analyzePathInto(analyzer, new File(cp));
        }
        HTMLFormatter fmt = new HTMLFormatter();
        FileMultiReportOutput mo = new FileMultiReportOutput(keyOut);
        IReportVisitor v = fmt.createVisitor(mo);
        v.visitInfo(java.util.Collections.emptyList(), store.getContents());
        v.visitBundle(cb.getBundle("key-" + key), createSourceLocator(sourcePaths));
        v.visitEnd();

        System.out.println("[peruser-report] key=" + key + " -> " + keyOut.getAbsolutePath()
                + "/index.html  (classes=" + cb.getClasses().size()
                + ", carriedMethods=" + carriedMethods + ", carriedProbes=" + carriedProbes + ")");
    }

    // ---------- class 字节读取 ----------

    private static Map<String, byte[]> readClassBytes(List<String> classPaths) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String cp : classPaths) {
            File f = new File(cp);
            if (f.isDirectory()) {
                try (java.util.stream.Stream<Path> s = Files.walk(f.toPath())) {
                    List<Path> files = s.filter(x -> x.toString().endsWith(".class"))
                            .collect(java.util.stream.Collectors.toList());
                    for (Path p : files) {
                        byte[] b = Files.readAllBytes(p);
                        out.putIfAbsent(new ClassReader(b).getClassName(), b);
                    }
                }
            } else if (f.getName().endsWith(".jar")) {
                try (JarFile jf = new JarFile(f)) {
                    for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                        JarEntry je = en.nextElement();
                        if (je.getName().endsWith(".class")) {
                            try (InputStream is = jf.getInputStream(je)) {
                                byte[] b = readAll(is);
                                out.putIfAbsent(new ClassReader(b).getClassName(), b);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<File> toFiles(List<String> classPaths) {
        List<File> fs = new ArrayList<>();
        for (String s : classPaths) {
            fs.add(new File(s));
        }
        return fs;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ---------- 探针布局扫描（与 JaCoCo Analyzer 同算法） ----------

    private static final class ProbeInfo {
        final int id;
        final int line;

        ProbeInfo(int id, int line) {
            this.id = id;
            this.line = line;
        }
    }

    private static final class MethodProbes {
        String name;
        String desc;
        int firstLine = -1;
        int lastLine = -1;
        final List<ProbeInfo> probes = new ArrayList<>();
    }

    private static final class ClassProbes {
        final Map<String, MethodProbes> methods = new LinkedHashMap<>();
        int totalProbeCount = -1;

        int maxProbeId() {
            int max = -1;
            for (MethodProbes mp : methods.values()) {
                for (ProbeInfo pi : mp.probes) {
                    if (pi.id > max) max = pi.id;
                }
            }
            return max;
        }
    }

    /**
     * 用 JaCoCo 自身的控制流探针适配器扫描原始 class 字节，得到：
     *   - 每个方法的探针 id -> 源码行 映射（用于按相对行偏移回填基线覆盖）；
     *   - 该类的总探针数 totalProbeCount（用于合成 ExecutionData 时给定正确的数组长度）。
     *
     * 关键点：必须与 JaCoCo Analyzer 用完全相同的参数，否则探针编号/数量对不上会数组越界。
     * Analyzer 内部是 new ClassProbesAdapter(visitor, false) + ClassReader.accept(visitor, 0)。
     */
    private static ClassProbes scanProbes(byte[] orig) {
        final ClassProbes cp = new ClassProbes();
        final ClassProbesVisitor cpv = new ClassProbesVisitor() {
            @Override
            public MethodProbesVisitor visitMethod(int access, String name, String desc,
                                                   String signature, String[] exceptions) {
                final MethodProbes mp = new MethodProbes();
                mp.name = name;
                mp.desc = desc;
                cp.methods.put(name + "#" + desc, mp);
                return new MethodProbesVisitor() {
                    int curLine = -1;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        curLine = line;
                        if (mp.firstLine < 0) mp.firstLine = line;
                        if (line > mp.lastLine) mp.lastLine = line;
                    }

                    @Override
                    public void visitProbe(int id) {
                        mp.probes.add(new ProbeInfo(id, curLine));
                    }

                    @Override
                    public void visitInsnWithProbe(int opcode, int id) {
                        mp.probes.add(new ProbeInfo(id, curLine));
                    }

                    @Override
                    public void visitJumpInsnWithProbe(int opcode, Label target, int id, IFrame frame) {
                        mp.probes.add(new ProbeInfo(id, curLine));
                    }

                    // 多路 switch 的探针没有显式 id 参数，其数量已计入 visitTotalProbeCount；
                    // 覆盖行携带为次要场景，此处不单独记录（pi.id < probes.length 已兜底）。
                };
            }

            @Override
            public void visitTotalProbeCount(int total) {
                cp.totalProbeCount = total;
            }
        };
        // 与 JaCoCo Analyzer 完全一致：ClassProbesAdapter(cv, false) + accept(flags=0)
        new ClassReader(orig).accept(new ClassProbesAdapter(cpv, false), 0);
        return cp;
    }

    // ---------- 源码定位器（与 ReportCli 一致） ----------

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
}
