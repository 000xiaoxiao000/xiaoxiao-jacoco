package peruser;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 覆盖率基线：Build N 跑完后，记录「每个方法 hash -> 哪些行被覆盖」。
 * 后续 Build N+1 若某方法未改动（hash 相同），则把这里记录的覆盖行按相对行偏移携带回去，
 * 实现「未变接口（如 /login）跨构建保留覆盖率」。
 */
public final class BaselineStore {

    /** 单个方法的基线条目。covered 为 Build N 中的【绝对】源码行号。 */
    public static final class Entry {
        public String hash;
        public String className;
        public String method;
        public String desc;
        public int firstLine;
        public int[] covered;
    }

    public final List<Entry> entries = new ArrayList<>();
    public final Map<String, Entry> byHash = new LinkedHashMap<>();
    public String build = "?";

    public void add(Entry e) {
        entries.add(e);
        // 按 className#hash 索引：同一类内方法体未变才携带，避免不同类里「同体方法」
        // （如大量 toString/equals/getter、空构造）因 methodHash 碰撞而跨类误携带。
        byHash.put(e.className + "#" + e.hash, e);
    }

    // ---- 采集：分析 exec + classfiles，结合 MethodHasher 写基线 ----

    public static BaselineStore build(ExecutionDataStore store, List<String> classPaths) throws IOException {
        BaselineStore bs = new BaselineStore();
        List<File> cps = new ArrayList<>();
        for (String s : classPaths) {
            cps.add(new File(s));
        }
        Map<String, String> hashes = MethodHasher.hashAll(cps);

        CoverageBuilder cb = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, cb);
        for (String cp : classPaths) {
            ReportCli.analyzePathInto(analyzer, new File(cp));
        }
        for (IClassCoverage c : cb.getClasses()) {
            String className = c.getName();
            for (IMethodCoverage m : c.getMethods()) {
                String key = className + "#" + m.getName() + "#" + m.getDesc();
                String hash = hashes.get(key);
                if (hash == null) {
                    continue; // 合成方法等无对应方法体
                }
                List<Integer> cov = new ArrayList<>();
                int fl = m.getFirstLine();
                int ll = m.getLastLine();
                if (fl > 0 && ll >= fl) {
                    for (int line = fl; line <= ll; line++) {
                        int st = m.getLine(line).getStatus();
                        if (st == org.jacoco.core.analysis.ICounter.FULLY_COVERED
                                || st == org.jacoco.core.analysis.ICounter.PARTLY_COVERED) {
                            cov.add(line);
                        }
                    }
                }
                if (cov.isEmpty()) {
                    continue; // 没覆盖到的方法不进基线（携带时无意义）
                }
                Entry e = new Entry();
                e.hash = hash;
                e.className = className;
                e.method = m.getName();
                e.desc = m.getDesc();
                e.firstLine = fl;
                e.covered = new int[cov.size()];
                for (int i = 0; i < cov.size(); i++) {
                    e.covered[i] = cov.get(i);
                }
                bs.add(e);
            }
        }
        return bs;
    }

    // ---- 序列化 ----

    public void write(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"build\": ").append(MiniJson.str(build)).append(",\n  \"methods\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append("    {\"hash\": ").append(MiniJson.str(e.hash))
                    .append(", \"className\": ").append(MiniJson.str(e.className))
                    .append(", \"method\": ").append(MiniJson.str(e.method))
                    .append(", \"desc\": ").append(MiniJson.str(e.desc))
                    .append(", \"firstLine\": ").append(e.firstLine)
                    .append(", \"covered\": [");
            for (int k = 0; k < e.covered.length; k++) {
                if (k > 0) sb.append(',');
                sb.append(e.covered[k]);
            }
            sb.append("]}");
            if (i + 1 < entries.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static BaselineStore read(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Object root = MiniJson.parse(text);
        Map<String, Object> obj = (Map<String, Object>) root;
        BaselineStore bs = new BaselineStore();
        Object b = obj.get("build");
        if (b != null) {
            bs.build = String.valueOf(b);
        }
        List<Object> methods = (List<Object>) obj.get("methods");
        for (Object mo : methods) {
            Map<String, Object> m = (Map<String, Object>) mo;
            Entry e = new Entry();
            e.hash = (String) m.get("hash");
            e.className = (String) m.get("className");
            e.method = (String) m.get("method");
            e.desc = (String) m.get("desc");
            e.firstLine = (int) toLong(m.get("firstLine"));
            List<Object> cov = (List<Object>) m.get("covered");
            e.covered = new int[cov.size()];
            for (int i = 0; i < cov.size(); i++) {
                e.covered[i] = (int) toLong(cov.get(i));
            }
            bs.add(e);
        }
        return bs;
    }

    private static long toLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return (long) (double) (Double) o;
        if (o instanceof Integer) return (Integer) o;
        return 0;
    }
}
