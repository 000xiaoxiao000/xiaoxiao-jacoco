package peruser;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import peruserrt.KeyBridge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程级探针存储 + 按 key（用户 / 用例 / 请求）归属。
 *
 * 三种归属来源（互斥优先级从高到低）：
 *   1) CoverageTracer.begin(key) / 请求头钩子设置的【线程 key】——存在
 *      peruserrt.KeyBridge 的 InheritableThreadLocal 里；
 *   2) 外部控制端点 /key?name=X 设置的【全局当前 key】——不修改目标系统、由进程外驱动设定，
 *      适合「单实例 + 时间窗口」式的 A/B 分离（先设 key=1 跑 A、dump&reset，再设 key=2 跑 B）；
 *   3) autokey（MERGE）模式——所有线程合并进一个 key（零代码改动）。
 *
 * 任一归属来源都没有时，探针写入一次性数组直接丢弃（不落盘）。
 *
 * 【异步归属】只要有 key，探针就【直接写入该 key 的共享数组】，不再依赖 end() 把线程局部副本
 * 合并回来 —— 这样 @Async / CompletableFuture / 线程池里的子线程，只要拿到 key（new Thread 靠
 * InheritableThreadLocal 继承，池线程靠 AsyncKeyWeaver 在提交时捕获），覆盖数据就不会丢。
 *
 * - KEY:      peruserrt.KeyBridge 里的 InheritableThreadLocal（可被子线程继承，支持 begin/end 嵌套）
 * - CURRENT_KEY: 外部控制端点设置的全局当前 key（不修改目标系统）
 * - META:     全局 classId -> (vmName, probeCount)，供生成报告时重建 ExecutionData
 * - USER / USER_META: 每个 key 累计的探针（并发按位 OR，各线程直写）
 * - GLOBAL:   MERGE 模式下所有线程共享的探针数组
 */
public final class ThreadProbeStore {

    private static final class Meta {
        final String name;
        final int probes;

        Meta(String name, int probes) {
            this.name = name;
            this.probes = probes;
        }
    }

    /** 外部控制端点设置的全局当前 key（不修改目标系统即可驱动分离） */
    private static final AtomicReference<String> CURRENT_KEY = new AtomicReference<>(null);

    /** classId -> 元数据（类名 + 探针数） */
    private static final ConcurrentHashMap<Long, Meta> META = new ConcurrentHashMap<>();

    /** key -> classId -> 合并后的 boolean[] */
    private static final ConcurrentHashMap<String, Map<Long, boolean[]>> USER = new ConcurrentHashMap<>();
    /** key -> classId -> 元数据 */
    private static final ConcurrentHashMap<String, Map<Long, Meta>> USER_META = new ConcurrentHashMap<>();

    // ===== 冒烟测试模式（autokey）：所有线程共享一份全局探针，合并进一个 key，零代码改动 =====
    private static volatile boolean MERGE = false;
    private static String MERGE_KEY = "default";
    private static final ConcurrentHashMap<Long, boolean[]> GLOBAL = new ConcurrentHashMap<>();

    private ThreadProbeStore() {
    }

    /** 由 PerUserAgent 在挂接前调用，设定冒烟测试模式。 */
    public static void configure(boolean merge, String mergeKey) {
        MERGE = merge;
        if (mergeKey != null && !mergeKey.isEmpty()) MERGE_KEY = mergeKey;
    }

    public static boolean isMerge() {
        return MERGE;
    }

    public static String mergeKey() {
        return MERGE_KEY;
    }

    // ===== 外部控制端点用：设定 / 读取全局当前 key（不修改目标系统）=====
    public static void setCurrentKey(String key) {
        CURRENT_KEY.set(key);
    }

    public static String getCurrentKey() {
        return CURRENT_KEY.get();
    }

    public static void clearCurrentKey() {
        CURRENT_KEY.set(null);
    }

    /** 文件名安全化：key 里可能含 / : 等非法字符。 */
    public static String sanitize(String key) {
        return key.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    /** 开始一个归属单元：之后本线程（及继承/传递了 key 的异步线程）执行的探针都归到 key（直到 end）。 */
    public static void begin(String key) {
        KeyBridge.push(key);
    }

    /**
     * 结束当前归属单元：退出 key 栈。
     * 探针已在 getProbes 里直接写入共享数组，这里无需再合并（这也是异步不丢数据的原因）。
     */
    public static void end() {
        KeyBridge.pop();
    }

    /**
     * 由插桩后的类在每个方法入口调用，返回当前归属 key 的探针数组。
     * 首次访问某类时按 probeCount 申请数组。
     */
    public static boolean[] getProbes(Object[] args) {
        long id = ((Long) args[0]).longValue();
        String name = (String) args[1];
        int pc = ((Integer) args[2]).intValue();
        META.putIfAbsent(id, new Meta(name, pc));

        if (MERGE) {
            // 冒烟测试模式：所有线程写入同一份全局数组 -> 合并覆盖率（类似官方 JaCoCo）
            boolean[] arr = GLOBAL.get(id);
            if (arr == null) {
                boolean[] nv = new boolean[pc];
                boolean[] prev = GLOBAL.putIfAbsent(id, nv);
                arr = (prev != null) ? prev : nv;
            }
            return arr;
        }

        // perKey 模式
        String key = KeyBridge.get();           // 1) 线程 key（含异步线程继承/传递过来的）
        if (key == null) {
            key = CURRENT_KEY.get();            // 2) 外部控制端点设置的全局当前 key
        }
        if (key == null) {
            return new boolean[pc];             // 3) 无归属 -> 丢弃探针
        }
        return probesOf(key, id, name, pc);
    }

    /**
     * 取（或创建）该 key 下某类的探针数组。
     *
     * 关键点：返回的是【共享数组】，任何拿到 key 的线程都直接往里写，不经过「线程局部副本 + end() 合并」。
     * 这样异步子线程（@Async / 线程池 / CompletableFuture）即使不调用 end()，数据也已经落进该 key，
     * 不会丢归属。并发下多个线程写同一 boolean[] 的不同下标（只置 true、不回退），最终即并集。
     */
    private static boolean[] probesOf(String key, long id, String name, int pc) {
        Map<Long, boolean[]> u = USER.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Map<Long, Meta> um = USER_META.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        boolean[] arr = u.get(id);
        if (arr == null) {
            boolean[] nv = new boolean[pc];
            boolean[] prev = u.putIfAbsent(id, nv);
            arr = (prev != null) ? prev : nv;
            // 没有 CoverageTracer.end() 来补元数据，必须在这里把类名/探针数记进 USER_META，
            // 否则 getStore(key) 时 um.get(id)==null 会跳过该类，导致 .exec 里一个类都没有（空覆盖率）。
            um.putIfAbsent(id, new Meta(name, pc));
        }
        return arr;
    }

    /** 冒烟测试模式下取合并后的 ExecutionDataStore。 */
    public static ExecutionDataStore getMergeStore() {
        ExecutionDataStore store = new ExecutionDataStore();
        for (Map.Entry<Long, boolean[]> e : GLOBAL.entrySet()) {
            Meta m = META.get(e.getKey());
            if (m != null) {
                store.put(new ExecutionData(e.getKey(), m.name, e.getValue()));
            }
        }
        return store;
    }

    public static boolean hasMergeData() {
        return !GLOBAL.isEmpty();
    }

    /** 取某 key 的独立 ExecutionDataStore，可直接喂给 JaCoCo Analyzer / 序列化 .exec */
    public static ExecutionDataStore getStore(String key) {
        ExecutionDataStore store = new ExecutionDataStore();
        Map<Long, boolean[]> u = USER.get(key);
        Map<Long, Meta> um = USER_META.get(key);
        if (u != null && um != null) {
            for (Map.Entry<Long, boolean[]> e : u.entrySet()) {
                Meta m = um.get(e.getKey());
                if (m != null) {
                    store.put(new ExecutionData(e.getKey(), m.name, e.getValue()));
                }
            }
        }
        return store;
    }

    public static boolean hasKey(String key) {
        if (MERGE) return hasMergeData();
        return USER.containsKey(key);
    }

    public static Set<String> keys() {
        return MERGE ? java.util.Collections.<String>emptySet() : USER.keySet();
    }

    /** 单 key 的采集概况（供 cli stats 展示）。 */
    public static final class KeyStat {
        public final String key;
        public final int classes;
        public final int probes;
        public final int covered;

        KeyStat(String key, int classes, int probes, int covered) {
            this.key = key;
            this.classes = classes;
            this.probes = probes;
            this.covered = covered;
        }
    }

    /**
     * 每个 key 的采集概况：类数 / 探针总数 / 已覆盖探针数。
     * 这是「到底采到东西没有」最直接的证据——比看 exec 文件里 classes=0 直观得多。
     */
    public static java.util.List<KeyStat> perKeyStats() {
        java.util.List<KeyStat> out = new java.util.ArrayList<>();
        if (MERGE) {
            int total = 0;
            int covered = 0;
            for (boolean[] a : GLOBAL.values()) {
                for (boolean b : a) {
                    total++;
                    if (b) {
                        covered++;
                    }
                }
            }
            out.add(new KeyStat(MERGE_KEY, GLOBAL.size(), total, covered));
            return out;
        }
        for (Map.Entry<String, Map<Long, boolean[]>> e : USER.entrySet()) {
            int total = 0;
            int covered = 0;
            for (boolean[] a : e.getValue().values()) {
                for (boolean b : a) {
                    total++;
                    if (b) {
                        covered++;
                    }
                }
            }
            out.add(new KeyStat(e.getKey(), e.getValue().size(), total, covered));
        }
        return out;
    }

    /** 清空某 key 的累计探针（dump --reset 用）。 */
    public static void resetKey(String key) {
        USER.remove(key);
        USER_META.remove(key);
    }

    /** 清空全部 key 的累计探针（dump --reset 用，perKey 模式）。 */
    public static void resetAll() {
        USER.clear();
        USER_META.clear();
    }

    /** 清空 MERGE 模式的全局探针。 */
    public static void resetMerge() {
        GLOBAL.clear();
    }
}
