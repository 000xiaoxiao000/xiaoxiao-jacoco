package peruser.cli;

import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * instrument 命令（离线插桩）：把 class/jar 插桩后写到 dest 目录，官方 jacococli instrument 的全部参数。
 *
 * Usage: instrument [<sourcefiles> ...] --dest <dir> [--quiet] [--help]
 *
 * 注意：离线插桩会得到「官方式」静态 $jacocoData 探针（所有线程共享），无法按用户/用例分离；
 * 需要按 key 分离请使用 agent 的 javaagent 方式。
 */
public final class InstrumentCommand {

    private InstrumentCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean quiet = CliArgs.has(args, "quiet");
        final String dest = CliArgs.value(args, "dest", null);
        if (dest == null) {
            throw new CliUsageException("missing --dest <dir>");
        }
        final List<String> sources = CliArgs.positional(args);
        if (sources.isEmpty()) {
            throw new CliUsageException("no source files given");
        }
        final File destDir = new File(dest);
        destDir.mkdirs();

        final Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());
        int count = 0;
        for (String src : sources) {
            final File f = new File(src);
            if (!f.exists()) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: not found: " + src);
                continue;
            }
            if (f.isFile() && f.getName().endsWith(".jar")) {
                File target = new File(destDir, f.getName());
                try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(target)) {
                    count += instrumenter.instrumentAll(in, out, f.getName());
                }
            } else if (f.isDirectory()) {
                count += instrumentDir(instrumenter, f.toPath(), destDir.toPath(), quiet);
            } else {
                File target = new File(destDir, f.getName());
                try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(target)) {
                    instrumenter.instrument(in, out, f.getPath());
                }
                count++;
            }
        }
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] instrumented " + count + " class(es) -> " + destDir.getAbsolutePath());
        }
    }

    private static int instrumentDir(Instrumenter instrumenter, Path srcRoot, Path destRoot, boolean quiet) throws IOException {
        int count = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(srcRoot)) {
            List<Path> classes = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .collect(java.util.stream.Collectors.toList());
            for (Path p : classes) {
                Path rel = srcRoot.relativize(p);
                Path target = destRoot.resolve(rel);
                File tf = target.toFile();
                File parent = tf.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = new FileInputStream(p.toFile());
                     OutputStream out = new FileOutputStream(tf)) {
                    instrumenter.instrument(in, out, p.toString());
                }
                count++;
            }
        }
        return count;
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar instrument [<sourcefiles> ...] --dest <dir> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dest <dir>    插桩结果输出的目录（必填）");
        System.out.println("  --quiet         减少输出");
        System.out.println();
        System.out.println("注意：离线插桩产出的是官方式静态探针，按 key 分离请用 javaagent 方式。");
    }
}
