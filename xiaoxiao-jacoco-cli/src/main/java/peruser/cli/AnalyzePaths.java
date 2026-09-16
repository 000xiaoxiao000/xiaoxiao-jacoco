package peruser.cli;

import org.jacoco.core.analysis.Analyzer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 把 --classfiles 指定的路径喂给 Analyzer 的统一工具：
 * 目录递归扫描 *.class，jar/zip 逐个 entry 分析（等价于官方 jacococli 的 classfiles 处理）。
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
