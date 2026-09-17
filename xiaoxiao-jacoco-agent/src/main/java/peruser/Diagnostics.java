package peruser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期自检计数器。
 *
 * 为什么需要它：覆盖率是空的（.exec 里 classes=0）时，可能的根因有三类，
 * 光看结果完全分不出来，只能靠猜：
 *   1) includes/excludes 没匹配到任何类           -> 一个类都没插桩
 *   2) headerkey 钩子没触发 / 请求头名不对         -> 插桩了，但没有 key，探针被丢弃
 *   3) 请求根本没打到这个 JVM                     -> 钩子也没触发
 * 这里把这三类分别计数，并由 {@code cli keys} 一次性回传，看到数字就能定位。
 */
public final class Diagnostics {

    /** 被 transformer 看到的应用类总数（排除 agent 自身）。 */
    private static final AtomicLong CLASSES_SEEN = new AtomicLong();
    /** 真正插桩成功的类数。 */
    private static final AtomicLong CLASSES_INSTRUMENTED = new AtomicLong();
    /**
     * 「本可以插桩但被 source location 检查挡掉」的类数。
     * Spring Boot 可执行 jar（BOOT-INF/classes、嵌套 jar）里的类经常没有 CodeSource，
     * 而 JaCoCo 默认 inclnolocationclasses=false 会把它们【全部跳过】——表现同样是覆盖率全空。
     */
    private static final AtomicLong CLASSES_NO_LOCATION = new AtomicLong();
    /** 进入 HTTP 入口钩子（HttpServlet.service）的次数。 */
    private static final AtomicLong REQUESTS_HOOKED = new AtomicLong();
    /** 钩子成功读到 key 的次数（请求头存在且非空）。 */
    private static final AtomicLong REQUESTS_TAGGED = new AtomicLong();

    /** 因 includes/excludes 被跳过的类的样例（最多 8 个，用于给出 includes 建议）。 */
    private static final int SAMPLE_LIMIT = 8;
    private static final Set<String> SKIPPED_SAMPLES =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** 插桩成功的类名（最多记这么多，供 cli stats 列出「到底插了什么」）。 */
    private static final int INSTRUMENTED_LIMIT = 3000;
    private static final Set<String> INSTRUMENTED_SAMPLES =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** 打印过的提示次数上限，避免刷屏。 */
    private static final AtomicLong HINTS_PRINTED = new AtomicLong();

    private Diagnostics() {
    }

    public static void classSeen() {
        CLASSES_SEEN.incrementAndGet();
    }

    public static void classInstrumented(String vmName) {
        CLASSES_INSTRUMENTED.incrementAndGet();
        if (vmName != null) {
            synchronized (INSTRUMENTED_SAMPLES) {
                if (INSTRUMENTED_SAMPLES.size() < INSTRUMENTED_LIMIT) {
                    INSTRUMENTED_SAMPLES.add(vmName);
                }
            }
        }
    }

    public static void classNoLocation() {
        CLASSES_NO_LOCATION.incrementAndGet();
    }

    public static void requestHooked() {
        REQUESTS_HOOKED.incrementAndGet();
    }

    public static void requestTagged() {
        REQUESTS_TAGGED.incrementAndGet();
    }

    /** 记录一个「本可以插桩、但被 includes/excludes 过滤掉」的类名样例。 */
    public static void skippedByFilter(String vmName) {
        synchronized (SKIPPED_SAMPLES) {
            if (SKIPPED_SAMPLES.size() < SAMPLE_LIMIT) {
                SKIPPED_SAMPLES.add(vmName);
            }
        }
    }

    public static long classesSeen() {
        return CLASSES_SEEN.get();
    }

    public static long classesInstrumented() {
        return CLASSES_INSTRUMENTED.get();
    }

    public static long requestsHooked() {
        return REQUESTS_HOOKED.get();
    }

    public static long requestsTagged() {
        return REQUESTS_TAGGED.get();
    }

    public static long classesNoLocation() {
        return CLASSES_NO_LOCATION.get();
    }

    public static List<String> skippedSamples() {
        synchronized (SKIPPED_SAMPLES) {
            return new ArrayList<>(SKIPPED_SAMPLES);
        }
    }

    /** 已插桩的类名（前 limit 个；limit<=0 表示全部）。 */
    public static List<String> instrumentedClasses(int limit) {
        synchronized (INSTRUMENTED_SAMPLES) {
            final List<String> all = new ArrayList<>(INSTRUMENTED_SAMPLES);
            if (limit > 0 && all.size() > limit) {
                return new ArrayList<>(all.subList(0, limit));
            }
            return all;
        }
    }

    /** 从 VM 类名推出可直接抄进 includes= 的包通配（取前两段包名）。 */
    public static List<String> suggestedIncludes() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String vm : skippedSamples()) {
            int i = vm.indexOf('/');
            int j = (i < 0) ? -1 : vm.indexOf('/', i + 1);
            String pkg = (j > 0) ? vm.substring(0, j + 1) : ((i > 0) ? vm.substring(0, i + 1) : vm);
            if (seen.add(pkg)) {
                out.add(pkg + "*");
            }
        }
        return out;
    }

    /** 一行摘要，供 tcpserver 日志与启动自检使用。 */
    public static String summary() {
        return "classes(seen=" + CLASSES_SEEN.get() + ", instrumented=" + CLASSES_INSTRUMENTED.get()
                + ", noSourceLocation=" + CLASSES_NO_LOCATION.get()
                + "), requests(hooked=" + REQUESTS_HOOKED.get() + ", tagged=" + REQUESTS_TAGGED.get() + ")";
    }

    /**
     * 自检提示：覆盖率全空时把最可能的原因直接打出来。
     * 只在「确实什么都没采到」时才提示，且最多提示 3 次，避免刷屏。
     */
    public static void checkAndHint(String headerKey) {
        final long inst = CLASSES_INSTRUMENTED.get();
        final long hooked = REQUESTS_HOOKED.get();
        final long tagged = REQUESTS_TAGGED.get();

        // 一切正常：给一次正面确认，让人一眼知道「插桩成功了」，而不是只在出错时才吭声
        if (inst > 0 && tagged > 0) {
            if (HINTS_PRINTED.compareAndSet(0, 1)) {
                System.out.println("[xiaoxiao-jacoco] 自检: " + summary()
                        + "  -> 正常：已插桩 " + inst + " 个类，" + tagged + " 次请求成功归属 key；"
                        + "随时可用 cli stats 查看插桩了哪些类、各 key 采到多少");
            }
            return;
        }
        // 已插桩但还没有请求进来（应用刚起 / 还没人访问）：不是故障，别误报
        if (inst > 0 && hooked == 0) {
            if (HINTS_PRINTED.compareAndSet(0, 1)) {
                System.out.println("[xiaoxiao-jacoco] 自检: " + summary()
                        + "  -> 已插桩 " + inst + " 个类，等待请求进来（还没捕获到 HTTP 请求）");
            }
            return;
        }
        if (HINTS_PRINTED.incrementAndGet() > 3) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[xiaoxiao-jacoco] 自检: ").append(summary()).append("  -> 覆盖率目前是空的，原因很可能是：");
        if (CLASSES_INSTRUMENTED.get() == 0) {
            sb.append("\n  (1) 一个类都没插桩：includes/excludes 没匹配上。")
                    .append("includes 匹配的是【VM 类名】(com/foo/Bar)，是全匹配（不是前缀匹配）；")
                    .append("不是 URL 路径（/web/testWeb）、不是模块名、不是包名简写。")
                    .append("（若你的应用还在启动中，等启动完再看这条提示）");
            List<String> sug = suggestedIncludes();
            if (!sug.isEmpty()) {
                sb.append("\n      已加载类的包名样例：").append(sug)
                        .append("\n      建议改成 includes=").append(sug.get(0))
                        .append("（或 includes=* 先验证链路，再收窄）");
            }
            if (CLASSES_NO_LOCATION.get() > 0) {
                sb.append("\n      另检测到 ").append(CLASSES_NO_LOCATION.get())
                        .append(" 个类【没有 source location】（Spring Boot 可执行 jar 常见），")
                        .append("这类会被 JaCoCo 默认跳过 -> 加 inclnolocationclasses=true 再重启。");
            }
        } else if (REQUESTS_TAGGED.get() == 0) {
            sb.append("\n  (2) 类已插桩但一次请求都没读到 key：")
                    .append(REQUESTS_HOOKED.get() == 0
                            ? "HTTP 入口钩子一次都没触发（请求没进 HttpServlet.service？还是非 Servlet 栈如 WebFlux？）"
                            : "钩子触发了 " + REQUESTS_HOOKED.get() + " 次，但请求头 " + headerKey
                              + " 一次都没读到（头名写错 / 大小写 / 请求没带上？）");
            sb.append("\n      没有 key 的请求，其覆盖率会被丢弃（不会落到任何 .exec）。");
        }
        System.err.println(sb.toString());
    }
}
