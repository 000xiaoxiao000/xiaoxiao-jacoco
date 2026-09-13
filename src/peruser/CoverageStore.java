package peruser;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 覆盖率数据仓库：从 ThreadProbeStore 取某 key 的 ExecutionDataStore，或序列化成标准 .exec 文件。
 *
 * .exec 为 JaCoCo 标准二进制格式，可被官方 jacococli / 其它 JaCoCo 工具二次处理。
 *
 * 关键语义：写出采用「合并写（merge-on-dump）」——
 * 若目标 .exec 已存在，当前内存探针会按位 OR 合并进已有数据，而非从零覆盖。
 * 这样即使有人中途 /dump?reset=true 清了某 key 的内存，已落盘的数据也不会丢失，
 * 仍在线跑的 key 在后续 dump 时把数据累加回来，互不干扰。
 */
public final class CoverageStore {

    /** 所有 .exec 写出串行化，避免并发 dump 同一文件时读到半截内容。 */
    private static final Object WRITE_LOCK = new Object();

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

    public static Set<String> keys() {
        if (ThreadProbeStore.isMerge()) {
            return ThreadProbeStore.hasMergeData()
                    ? java.util.Collections.singleton(ThreadProbeStore.mergeKey())
                    : java.util.Collections.<String>emptySet();
        }
        return ThreadProbeStore.keys();
    }

    /** 把某 key 的覆盖率【覆盖式】写成标准 jacoco .exec（不合并，供明确需要覆盖的场景使用）。 */
    public static void writeExec(String key, File file) throws IOException {
        ExecutionDataStore store = getStore(key);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ExecutionDataWriter w = new ExecutionDataWriter(fos);
            store.accept(w);
            w.flush();
        }
    }

    /**
     * 把某 key 的覆盖率写成标准 jacoco .exec；若文件已存在，则与现有数据按探针位 OR 合并。
     *
     * 这样「先 dump 一次 → 业务继续跑 → 再 dump 一次（无论是否 reset）」不会把已落盘数据
     * 覆盖成不完整快照，而是在已有基础上累加，实现多 key 并发互不干扰。
     */
    private static void writeExecMerge(String key, File file) throws IOException {
        synchronized (WRITE_LOCK) {
            ExecutionDataStore current = getStore(key);
            ExecutionDataStore merged = new ExecutionDataStore();
            if (file.exists()) {
                try {
                    ExecFileLoader loader = new ExecFileLoader();
                    loader.load(file);
                    for (ExecutionData d : loader.getExecutionDataStore().getContents()) {
                        merged.put(d);
                    }
                } catch (IOException e) {
                    // 已有文件损坏：忽略，直接用当前内存数据覆盖写
                    merged = new ExecutionDataStore();
                }
            }
            try {
                for (ExecutionData d : current.getContents()) {
                    merged.put(d);
                }
            } catch (IllegalStateException dup) {
                // 类被重新插桩导致探针数/类名不一致（目标重部署）：旧的 .exec 已经失效，
                // 用当前内存数据直接覆盖写，丢弃陈旧内容。
                writeExec(key, file);
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                ExecutionDataWriter w = new ExecutionDataWriter(fos);
                merged.accept(w);
                w.flush();
            }
        }
    }

    /**
     * 把当前所有 key 的覆盖率写出为 coverage-&lt;key&gt;.exec（与 JVM 关闭钩子共用同一逻辑）。
     * 采用合并写，重复 dump 不会互相覆盖。
     *
     * @param dir   输出目录
     * @param reset 写出后是否清空对应的探针累计（等价于 jacococli dump --reset，但支持多 key）
     * @return 实际写出的文件列表
     */
    public static List<File> dumpAll(File dir, boolean reset) throws IOException {
        dir.mkdirs();
        List<File> files = new ArrayList<>();
        if (ThreadProbeStore.isMerge()) {
            if (ThreadProbeStore.hasMergeData()) {
                File f = new File(dir, "coverage-" + ThreadProbeStore.sanitize(ThreadProbeStore.mergeKey()) + ".exec");
                writeExecMerge(ThreadProbeStore.mergeKey(), f);
                files.add(f);
                if (reset) ThreadProbeStore.resetMerge();
            }
            return files;
        }
        for (String key : ThreadProbeStore.keys()) {
            if (ThreadProbeStore.hasKey(key)) {
                File f = new File(dir, "coverage-" + ThreadProbeStore.sanitize(key) + ".exec");
                writeExecMerge(key, f);
                files.add(f);
                if (reset) ThreadProbeStore.resetKey(key);
            }
        }
        return files;
    }

    /**
     * 只写出（并可选 reset）单个 key 的覆盖率，不影响其它 key 的内存累计。
     *
     * @param key   目标 key
     * @param dir   输出目录
     * @param reset 写出后是否清空该 key 的探针累计
     * @return 实际写出的文件列表（命中则为 1 个，未命中则为空）
     */
    public static List<File> dumpKey(String key, File dir, boolean reset) throws IOException {
        dir.mkdirs();
        List<File> files = new ArrayList<>();
        if (ThreadProbeStore.isMerge()) {
            // 冒烟(合并)模式只有单一 key，直接走全量
            return dumpAll(dir, reset);
        }
        if (ThreadProbeStore.hasKey(key)) {
            File f = new File(dir, "coverage-" + ThreadProbeStore.sanitize(key) + ".exec");
            writeExecMerge(key, f);
            files.add(f);
            if (reset) ThreadProbeStore.resetKey(key);
        }
        return files;
    }
}
