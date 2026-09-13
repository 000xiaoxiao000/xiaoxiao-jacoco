package peruser;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程级探针存储 + 按 key（用户 / 用例 / 请求）归属。
 *
 * 三种归属来源（互斥优先级从高到低）：
 *   1) CoverageTracer.begin(key) 设置的【线程局部 key】——目标系统代码里包裹工作单元时使用；
 *   2) 外部控制端点 /key?name=X 设置的【全局当前 key】——不修改目标系统、由进程外驱动设定，
 *      适合「单实例 + 时间窗口」式的 A/B 分离（先设 key=1 跑 A、dump&reset，再设 key=2 跑 B）；
 *   3) autokey（MERGE）模式——所有线程合并进一个 key（零代码改动）。
 *
 * 任一归属来源都没有时，探针写入一次性数组直接丢弃（不落盘）。
 *
 * - TL:       每个线程（=一次被 CoverageTracer 包裹的工作单元）一份 Map<classId, boolean[]>
 * - KEY:      当前线程正在归属的 key（由 CoverageTracer.begin 设置）
 * - CURRENT_KEY: 外部控制端点设置的全局当前 key（不修改目标系统）
 * - META:     全局 classId -> (vmName, probeCount)，供生成报告时重建 ExecutionData
 * - USER / USER_META: 每个 key 累计的探针（按位 OR 合并多次工作单元）
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

    /** 当前线程的探针：classId -> 该类的 boolean[] 探针数组（CoverageTracer 模式用） */
    private static final ThreadLocal<Map<Long, boolean[]>> TL =
            ThreadLocal.withInitial(HashMap::new);

    /** 当前线程正在归属的 key（CoverageTracer.begin 设置） */
    private static final ThreadLocal<String> KEY = new ThreadLocal<>();

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

    /** 开始一个归属单元：之后本线程执行的、被插桩类的探针都归到 key（直到 end）。 */
    public static void begin(String key) {
        KEY.set(key);
    }

    /** 结束当前归属单元：把本线程探针按 key 合并、清空 ThreadLocal（线程池复用也安全）。 */
    public static void end() {
        String key = KEY.get();
        KEY.remove();
        Map<Long, boolean[]> req = TL.get();
        if (key == null || req == null || req.isEmpty()) {
            TL.remove();
            return;
        }
        Map<Long, boolean[]> u = USER.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Map<Long, Meta> um = USER_META.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        for (Map.Entry<Long, boolean[]> e : req.entrySet()) {
            long id = e.getKey();
            boolean[] src = e.getValue();
            Meta m = META.get(id);
            if (m != null) um.putIfAbsent(id, m);
            u.merge(id, src, (a, b) -> {
                for (int i = 0; i < a.length; i++) {
                    if (b[i]) a[i] = true;
                }
                return a;
            });
        }
        req.clear();
        TL.remove();
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
        String key = KEY.get();                 // 1) CoverageTracer 线程局部 key
        if (key == null) {
            // 2) 外部控制端点设置的全局当前 key（不修改目标系统即可分离）
            String gk = CURRENT_KEY.get();
            if (gk == null) {
                return new boolean[pc];         // 3) 无归属 -> 丢弃探针
            }
            Map<Long, boolean[]> u = USER.computeIfAbsent(gk, k -> new ConcurrentHashMap<>());
            Map<Long, Meta> um = USER_META.computeIfAbsent(gk, k -> new ConcurrentHashMap<>());
            boolean[] arr = u.get(id);
            if (arr == null) {
                arr = new boolean[pc];
                u.put(id, arr);
                // 外部 key 模式没有 CoverageTracer.end() 来补元数据，必须在这里把类名/探针数记进 USER_META，
                // 否则 getStore(key) 时 um.get(id)==null 会跳过该类，导致 .exec 里一个类都没有（空覆盖率）。
                Meta m = META.get(id);
                um.putIfAbsent(id, m != null ? m : new Meta(name, pc));
            }
            return arr;                         // 直接写入共享 map，无需 end 提交
        }
        // CoverageTracer 模式：线程局部工作副本，end() 时合并
        Map<Long, boolean[]> map = TL.get();
        boolean[] arr = map.get(id);
        if (arr == null) {
            arr = new boolean[pc];
            map.put(id, arr);
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
