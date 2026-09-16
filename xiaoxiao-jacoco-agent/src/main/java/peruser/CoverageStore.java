package peruser;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 覆盖率数据仓库：从 ThreadProbeStore 取某 key 的 ExecutionDataStore，或序列化成标准 .exec 文件。
 *
 * .exec 为 JaCoCo 标准二进制格式，可被官方 jacococli / xiaoxiao-jacoco-cli 二次处理。
 *
 * 关键语义：默认的写出是「合并写（merge-on-dump）」——
 * 若目标 .exec 已存在，当前内存探针会按位 OR 合并进已有数据，而非从零覆盖。
 * 只有在官方 append=false 时才做覆盖写。
 */
public final class CoverageStore {

    /** 所有 .exec 写出串行化，避免并发 dump 同一文件时读到半截内容。 */
    private static final Object WRITE_LOCK = new Object();

    /** agent 启动时刻（作为 session 的 start 时间戳）。 */
    private static final long START_TIME = System.currentTimeMillis();

    /** 未显式指定 sessionid 时的默认标识。 */
    public static final String DEFAULT_SESSION_ID = "xiaoxiao-jacoco";

    private CoverageStore() {
    }

    public static ExecutionDataStore getStore(String key) {
        if (ThreadProbeStore.isMerge()) {
            return ThreadProbeStore.getMergeStore();
        }
        return ThreadProbeStore.getStore(key);
    }

    public static boolean hasKey(String key) {
        if (ThreadProbeStore.isMerge()) {
            return ThreadProbeStore.hasMergeData();
        }
        return ThreadProbeStore.hasKey(key);
    }

    /** 把某 key 的内存覆盖率序列化成标准 jacoco .exec 字节。 */
    public static byte[] toBytes(String key) throws IOException {
        ExecutionDataStore store = getStore(key);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ExecutionDataWriter w = new ExecutionDataWriter(bos);
        store.accept(w);
        w.flush();
        return bos.toByteArray();
    }

    public static Set<String> keys() {
        if (ThreadProbeStore.isMerge()) {
            return ThreadProbeStore.hasMergeData()
                    ? java.util.Collections.singleton(ThreadProbeStore.mergeKey())
                    : java.util.Collections.<String>emptySet();
        }
        return ThreadProbeStore.keys();
    }

    /**
     * 所有 key 的【并集】ExecutionDataStore（按 classId OR 合并），
     * 供 output=tcpserver / tcpclient 这种「单文件流」输出模式使用（官方只有一个整体 exec）。
     */
    public static ExecutionDataStore mergedStore() {
        ExecutionDataStore merged = new ExecutionDataStore();
        for (String key : keys()) {
            mergeInto(merged, getStore(key));
        }
        return merged;
    }

    /** 把 src 的探针按 OR 合并进 dst（忽略探针数/类名冲突的陈旧条目，按新的为准）。 */
    public static void mergeInto(ExecutionDataStore dst, ExecutionDataStore src) {
        for (ExecutionData d : src.getContents()) {
            try {
                dst.put(d);
            } catch (IllegalStateException ignored) {
                // 类被重新插桩导致探针数不一致：跳过该条目，避免整体 dump 失败
            }
        }
    }

    /**
     * 把 store 写成标准 .exec 文件（覆盖写），并写入 session 元信息
     * （与官方 agent 产出一致：jacococli execinfo / report 的 Sessions 页都能看到）。
     */
    public static void writeExec(ExecutionDataStore store, File file, String sessionId) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ExecutionDataWriter w = new ExecutionDataWriter(fos);
            w.visitSessionInfo(new org.jacoco.core.data.SessionInfo(
                    sessionId == null || sessionId.isEmpty() ? DEFAULT_SESSION_ID : sessionId,
                    START_TIME, System.currentTimeMillis()));
            store.accept(w);
            w.flush();
        }
    }

    /** 兼容旧签名：不写 session 信息。 */
    public static void writeExec(ExecutionDataStore store, File file) throws IOException {
        writeExec(store, file, DEFAULT_SESSION_ID);
    }

    /** 把某 key 的覆盖率【覆盖式】写成标准 jacoco .exec（不合并）。 */
    public static void writeExec(String key, File file, String sessionId) throws IOException {
        writeExec(getStore(key), file, sessionId);
    }

    /** 把某 key 的覆盖率【覆盖式】写成标准 jacoco .exec（不合并）。 */
    public static void writeExec(String key, File file) throws IOException {
        writeExec(getStore(key), file, DEFAULT_SESSION_ID);
    }

    /** 把字节内容按 classId OR 合并进已存在的 .exec 文件（不存在则直接写入）。 */
    public static void mergeExec(File file, byte[] data, String sessionId) throws IOException {
        synchronized (WRITE_LOCK) {
            ExecutionDataStore merged = new ExecutionDataStore();
            if (file.exists() && file.length() > 0) {
                try (InputStream in = new java.io.FileInputStream(file)) {
                    ExecFileLoader loader = new ExecFileLoader();
                    loader.load(in);
                    mergeInto(merged, loader.getExecutionDataStore());
                } catch (IOException e) {
                    merged = new ExecutionDataStore(); // 文件损坏：用新数据覆盖写
                }
            }
            if (data != null && data.length > 0) {
                try (InputStream in = new ByteArrayInputStream(data)) {
                    ExecFileLoader loader = new ExecFileLoader();
                    loader.load(in);
                    mergeInto(merged, loader.getExecutionDataStore());
                }
            }
            writeExec(merged, file, sessionId);
        }
    }

    /**
     * 把某 key 的覆盖率写成标准 jacoco .exec；若文件已存在且未禁用 append，则与现有数据按探针位 OR 合并。
     */
    public static void writeExecMerge(String key, File file, String sessionId) throws IOException {
        synchronized (WRITE_LOCK) {
            ExecutionDataStore merged = new ExecutionDataStore();
            if (file.exists() && file.length() > 0) {
                try (InputStream in = new java.io.FileInputStream(file)) {
                    ExecFileLoader loader = new ExecFileLoader();
                    loader.load(in);
                    mergeInto(merged, loader.getExecutionDataStore());
                } catch (IOException e) {
                    merged = new ExecutionDataStore();
                }
            }
            try {
                mergeInto(merged, getStore(key));
            } catch (RuntimeException e) {
                writeExec(key, file, sessionId);
                return;
            }
            writeExec(merged, file, sessionId);
        }
    }

    /**
     * 把所有 key 的覆盖率按 {@link Options#execFile(String)} 写出（JVM 关闭钩子 / dumponexit 用）。
     *
     * @param reset 写出后是否清空对应 key 的内存累计（jacococli dump --reset 语义）
     * @return 实际写出的文件列表
     */
    public static List<File> dumpAll(Options o, boolean reset) throws IOException {
        List<File> files = new ArrayList<>();
        for (String key : keys()) {
            if (hasKey(key)) {
                files.addAll(emit(key, o, reset));
            }
        }
        return files;
    }

    /** 只写出（并可选 reset）单个 key 的覆盖率，不影响其它 key 的内存累计。 */
    public static List<File> dumpKey(String key, Options o, boolean reset) throws IOException {
        List<File> files = new ArrayList<>();
        if (hasKey(key)) {
            files.addAll(emit(key, o, reset));
        }
        return files;
    }

    private static List<File> emit(String key, Options o, boolean reset) throws IOException {
        List<File> files = new ArrayList<>();
        File f = o.execFile(key);
        if (o.append()) {
            writeExecMerge(key, f, o.sessionId());
        } else {
            writeExec(key, f, o.sessionId());
        }
        files.add(f);
        if (reset) {
            if (ThreadProbeStore.isMerge()) ThreadProbeStore.resetMerge();
            else ThreadProbeStore.resetKey(key);
        }
        return files;
    }

    /**
     * 过期定期清理：递归删除 root 下「最后修改时间早于 now-expireMs」的覆盖率文件。
     * 只删 agent 自己会产生的两类文件，绝不碰其它：
     *   - *.exec   （覆盖率数据）
     *   - *.class  （classdumpdir 落盘的原始 class）
     * 因删除而变空的子目录会一并删除，但 root 目录本身始终保留。
     */
    public static int cleanupExpired(File root, long expireMs) {
        if (root == null || !root.exists()) return 0;
        long cutoff = System.currentTimeMillis() - expireMs;
        List<File> deleted = new ArrayList<>();
        cleanupExpiredRecursive(root, cutoff, deleted, true);
        return deleted.size();
    }

    private static void cleanupExpiredRecursive(File f, long cutoff, List<File> deleted, boolean isRoot) {
        if (!f.isDirectory()) {
            String name = f.getName();
            boolean target = name.endsWith(".exec") || name.endsWith(".class");
            if (target && f.lastModified() < cutoff) {
                if (f.delete()) deleted.add(f);
            }
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                cleanupExpiredRecursive(c, cutoff, deleted, false);
            }
        }
        if (!isRoot) {
            File[] left = f.listFiles();
            if (left != null && left.length == 0 && f.delete()) {
                deleted.add(f);
            }
        }
    }
}
