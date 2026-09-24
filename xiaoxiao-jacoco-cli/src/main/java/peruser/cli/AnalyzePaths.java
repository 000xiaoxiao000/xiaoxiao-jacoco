package peruser.cli;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 把 --classfiles 指定的路径喂给 Analyzer 的统一工具：
 * 目录递归扫描 *.class，jar/zip 逐个 entry 分析（等价于官方 jacococli 的 classfiles 处理）。
 *
 * 另提供 warnIfNoExecData()：exec 与 classfiles 版本不一致时，覆盖率会静默全 0，
 * 这里用 classId 集合比对把问题显式告警出来（IClassCoverage.isNoMatch() 不可靠——
 * 未被插桩的类不会被置位，拿错版本的 classfiles 它照样返回 false）。
 */
final class AnalyzePaths {

    private AnalyzePaths() {
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
        } else if (f.getName().endsWith(".jar") || f.getName().endsWith(".zip")) {
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
        } else if (f.getName().endsWith(".class")) {
            analyzer.analyzeClass(Files.readAllBytes(f.toPath()), "");
        }
    }

    /**
     * exec 里记录的 classId 与分析出来的类做集合比对：全部对不上说明拿错了版本的 classfiles，
     * 部分对不上说明可能是类没被加载、也可能是版本混杂。
     */
    static void warnIfNoExecData(CoverageBuilder builder, ExecutionDataStore store) {
        Set<Long> ids = new HashSet<Long>();
        for (ExecutionData data : store.getContents()) {
            ids.add(Long.valueOf(data.getId()));
        }
        int total = 0;
        int matched = 0;
        for (IClassCoverage cls : builder.getClasses()) {
            total++;
            if (ids.contains(Long.valueOf(cls.getId()))) {
                matched++;
            }
        }
        if (total == 0) {
            return;
        }
        if (matched == total) {
            return;
        }
        if (matched == 0) {
            System.err.println("[xiaoxiao-jacoco-cli] warning: " + total + "/" + total
                    + " 个类在 exec 里没有对应的探针数据 —— exec 与 --classfiles 很可能【不是同一个构建产物】"
                    + "（classId 全不匹配），覆盖率会全部显示为 0；请确认 exec 是不是这批 classfiles 跑出来的");
        } else {
            System.err.println("[xiaoxiao-jacoco-cli] warning: " + (total - matched) + "/" + total
                    + " 个类在 exec 里没有对应的探针数据（可能是这些类压根没被加载执行，"
                    + "也可能是 exec 与 --classfiles 版本不一致）");
        }
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
