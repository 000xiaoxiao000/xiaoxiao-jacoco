package peruser.cli;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * merge 命令：把多个 exec 文件按 classId OR 合并成一个（官方 jacococli merge 的全部参数）。
 *
 * Usage: merge [<execfiles> ...] --destfile <path> [--append true|false] [--quiet] [--help]
 */
public final class MergeCommand {

    private MergeCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final boolean quiet = CliArgs.has(args, "quiet");
        final String dest = CliArgs.value(args, "destfile", null);
        if (dest == null) {
            throw new CliUsageException("missing --destfile <path>");
        }
        final List<String> explicit = CliArgs.pathList(args, "execdir");
        List<File> files = new java.util.ArrayList<>();
        for (String p : CliArgs.positional(args)) {
            File f = new File(p);
            if (f.exists()) files.add(f);
        }
        for (String dir : explicit) {
            File[] top = new File(dir).listFiles((d, n) -> n.endsWith(".exec"));
            if (top != null) java.util.Collections.addAll(files, top);
        }
        if (files.isEmpty()) {
            throw new CliUsageException("no exec files given");
        }

        final boolean append = Boolean.parseBoolean(CliArgs.value(args, "append", "true"));
        final ExecutionDataWriter writer;
        final ExecutionDataStore store = new ExecutionDataStore();
        final org.jacoco.core.data.SessionInfoStore sessions = new org.jacoco.core.data.SessionInfoStore();
        loadAll(files, store, sessions, quiet);

        final File destFile = new File(dest);
        if (append && destFile.exists() && destFile.length() > 0) {
            try (InputStream in = new FileInputStream(destFile)) {
                final ExecFileLoader existing = new ExecFileLoader();
                existing.load(in);
                mergeInto(store, existing.getExecutionDataStore());
                for (org.jacoco.core.data.SessionInfo si : existing.getSessionInfoStore().getInfos()) {
                    sessions.visitSessionInfo(si);
                }
            } catch (IOException e) {
                System.err.println("[xiaoxiao-jacoco-cli] warning: existing destfile unreadable, overwriting: " + e);
            }
        }
        final File parent = destFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(destFile)) {
            writer = new ExecutionDataWriter(out);
            for (org.jacoco.core.data.SessionInfo si : sessions.getInfos()) {
                writer.visitSessionInfo(si);
            }
            store.accept(writer);
            writer.flush();
        }
        if (!quiet) {
            System.out.println("[xiaoxiao-jacoco-cli] merged " + files.size()
                    + " exec file(s) -> " + destFile.getAbsolutePath()
                    + " (classes=" + store.getContents().size() + ")");
        }
    }

    private static void loadAll(List<File> files, ExecutionDataStore store,
                                org.jacoco.core.data.SessionInfoStore sessions, boolean quiet) throws IOException {
        for (File f : files) {
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] loading exec: " + f.getPath());
            }
            final ExecFileLoader loader = new ExecFileLoader();
            loader.load(f);
            mergeInto(store, loader.getExecutionDataStore());
            for (org.jacoco.core.data.SessionInfo si : loader.getSessionInfoStore().getInfos()) {
                sessions.visitSessionInfo(si);
            }
        }
    }

    private static void mergeInto(ExecutionDataStore dst, ExecutionDataStore src) {
        for (ExecutionData d : src.getContents()) {
            try {
                dst.put(d);
            } catch (IllegalStateException ignored) {
                // 探针数不一致（类被重新插桩）：跳过陈旧条目
            }
        }
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar merge [<execfiles> ...] --destfile <path> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --destfile <path>   合并结果写入的 exec 文件（必填）");
        System.out.println("  --append <bool>     是否把已有 destfile 的内容按 classId OR 合并进来，默认 true");
        System.out.println("  --execdir <dir>     额外读该目录下所有 *.exec");
        System.out.println("  --quiet             减少输出");
    }
}
