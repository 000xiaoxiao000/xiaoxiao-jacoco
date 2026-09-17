package peruser;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 被插桩类的【原始（未插桩）字节码】内存缓存。
 *
 * <p>用途：覆盖率报告的分母（classfiles）需要被插桩类的字节码。官方 JaCoCo 通常从
 * 构建产物 / classdumpdir 拿；但在 docker / k8s 等「禁止 scp、不给被测机密码」的环境里，
 * 这两种来源都难以取得。本缓存让 agent 在内存里直接保留原始字节，cli 通过 tcp 协议的
 * {@code dumpclasses} 命令一次性打包拉回（zip），彻底免容器访问、免镜像访问。
 *
 * <p>为什么必须是【原始】字节码：JaCoCo 报告时用 Analyzer 对 classfiles 自行重插桩，从而与
 * exec 里的探针编号对齐。若缓存插桩后的字节码，报告会二次插桩、探针编号错乱。缓存发生在
 * transformer 插桩之前（见 {@link PerUserTransformer}）。
 *
 * <p>内存保护：总量超过 {@link #MAX_BYTES}（256MB）后停止缓存并告警，避免 agent 拖垮被测应用。
 * 满额后 {@link #available()} 仍返回 true（已缓存的部分仍可用），只是不再增长。
 */
final class ClassCache {

    /** 累计原始字节上限，超过即停缓存。常见应用全量类字节码约 20~80MB，256MB 留足余量。 */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL = new AtomicLong(0);
    private static final AtomicBoolean DISABLED = new AtomicBoolean(false);

    private ClassCache() {
    }

    /** 缓存一个类的原始字节（插桩前）。className 为 VM 内部名（com/foo/Bar），作为 zip entry 的目录结构。 */
    static void put(String className, byte[] originalBytes) {
        if (DISABLED.get() || className == null || originalBytes == null) {
            return;
        }
        final byte[] prev = CACHE.put(className, originalBytes);
        if (prev == null) {
            final long now = TOTAL.addAndGet(originalBytes.length);
            if (now > MAX_BYTES) {
                DISABLED.set(true);
                System.err.println("[xiaoxiao-jacoco] warning: class cache 累计超过 "
                        + (MAX_BYTES / 1024 / 1024) + "MB，已停止缓存（dumpclasses 仅含已缓存部分）。"
                        + "如需完整 classfiles，请用构建产物 jar 或 classdumpdir 代替。");
            }
        }
        // prev != null：同一类被重复定义（热替换/重加载），保留最新字节，大小统计近似不变
    }

    /** 是否仍有可用缓存（未超限且非空）。 */
    static boolean available() {
        return !CACHE.isEmpty();
    }

    /** 已缓存类数量。 */
    static int size() {
        return CACHE.size();
    }

    /** 已缓存字节总量（近似）。 */
    static long byteCount() {
        return TOTAL.get();
    }

    /**
     * 把所有缓存的原始字节码打包成 zip，每个 entry 名为 {@code <className>.class}（含 / 即目录结构），
     * 直接对应 report --classfiles 期望的目录布局。返回字节数组（一次性在内存，受 MAX_BYTES 约束）。
     */
    static byte[] toZip() throws java.io.IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : CACHE.entrySet()) {
                final ZipEntry ze = new ZipEntry(e.getKey() + ".class");
                ze.setSize(e.getValue().length);
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
