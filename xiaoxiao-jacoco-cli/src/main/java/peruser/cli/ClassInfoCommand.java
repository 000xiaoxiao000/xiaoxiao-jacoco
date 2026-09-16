package peruser.cli;

import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * classinfo 命令：列出 class 目录 / jar 里的类及其探针信息（官方 jacococli classinfo 的全部参数）。
 *
 * Usage: classinfo [<classlocations> ...] [--verbose] [--quiet] [--help]
 */
public final class ClassInfoCommand {

    private ClassInfoCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean verbose = CliArgs.has(args, "verbose");
        final boolean quiet = CliArgs.has(args, "quiet");
        final List<String> locations = CliArgs.positional(args);
        if (locations.isEmpty()) {
            throw new CliUsageException("no class locations given (directory or jar)");
        }
        int total = 0;
        for (String loc : locations) {
            final File f = new File(loc);
            if (!f.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: not found: " + loc);
                continue;
            }
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] " + f.getPath());
            }
            List<byte[]> classes = loadAll(f);
            Collections.sort(classes, (a, b) -> className(a).compareTo(className(b)));
            for (byte[] bytes : classes) {
                total++;
                print(bytes, verbose);
            }
        }
        System.out.println("Classes found: " + total);
    }

    private static void print(byte[] bytes, boolean verbose) throws IOException {
        final ClassReader reader = new ClassReader(bytes);
        final String name = reader.getClassName();
        final int major = InstrSupport.getMajorVersion(reader);
        final ProbeCounter counter = new ProbeCounter();
        final ClassProbesAdapter adapter = new ClassProbesAdapter(
                counter, InstrSupport.needsFrames(major));
        reader.accept(adapter, ClassReader.EXPAND_FRAMES);
        System.out.println("  " + name);
        System.out.println("    Class file format version: " + major + " (" + javaName(major) + ")");
        System.out.println("    Total probes: " + counter.count);
        if (verbose) {
            System.out.println("    VM class name: " + name.replace('.', '/'));
            System.out.println("    Size: " + bytes.length + " bytes");
        }
    }

    private static List<byte[]> loadAll(File f) throws IOException {
        final List<byte[]> list = new ArrayList<>();
        if (f.isDirectory()) {
            try (java.util.stream.Stream<Path> stream = Files.walk(f.toPath())) {
                List<Path> paths = stream
                        .filter(p -> p.toString().endsWith(".class"))
                        .collect(java.util.stream.Collectors.toList());
                for (Path p : paths) {
                    list.add(Files.readAllBytes(p));
                }
            }
        } else if (f.getName().endsWith(".jar")) {
            try (JarFile jf = new JarFile(f)) {
                for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                    JarEntry je = e.nextElement();
                    if (je.getName().endsWith(".class")) {
                        try (InputStream is = jf.getInputStream(je)) {
                            list.add(readAll(is));
                        }
                    }
                }
            }
        } else if (f.getName().endsWith(".class")) {
            list.add(Files.readAllBytes(f.toPath()));
        }
        return list;
    }

    private static String className(byte[] bytes) {
        return new ClassReader(bytes).getClassName();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String javaName(int major) {
        if (major >= 65) return "Java 21+";
        switch (major) {
            case 52: return "Java 8";
            case 53: return "Java 9";
            case 54: return "Java 10";
            case 55: return "Java 11";
            case 56: return "Java 12";
            case 57: return "Java 13";
            case 58: return "Java 14";
            case 59: return "Java 15";
            case 60: return "Java 16";
            case 61: return "Java 17";
            case 62: return "Java 18";
            case 63: return "Java 19";
            case 64: return "Java 20";
            default: return "Java " + (major - 44);
        }
    }

    /** 预扫描类探针总数（JaCoCo 的 ProbeCounter 是包私有，这里自己实现）。 */
    static final class ProbeCounter extends ClassProbesVisitor {
        int count = 0;

        @Override
        public MethodProbesVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
            return new MethodProbesVisitor() {
            };
        }

        @Override
        public void visitTotalProbeCount(int probeCount) {
            this.count = probeCount;
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar classinfo [<classlocations> ...] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --verbose   输出每个类的额外信息（VM 名 / 字节数）");
        System.out.println("  --quiet     减少输出");
    }
}
