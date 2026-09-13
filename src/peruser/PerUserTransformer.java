package peruser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * JaCoCo 风格的 ClassFileTransformer：对命中的类用 ThreadLocal 探针策略重插桩。
 * 被插桩的类在运行时调用 peruser.ThreadProbeStore.getProbes 取当前线程的探针数组。
 *
 * 注意：使用 Java 8 的 transform 签名（无 Module 参数），以便 agent 能在 Java 8 目标 JVM 上加载。
 */
public final class PerUserTransformer implements ClassFileTransformer {

    private final Options options;

    public PerUserTransformer(Options options) {
        this.options = options;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain domain, byte[] buf) {
        if (buf == null) {
            return null;
        }
        // 方案 A（headerkey）：按请求头归属钩子，织入 Servlet 统一入口 HttpServlet.service，
        // 框架无关（javax/jakarta 双命名空间）、零改目标系统、支持并发按用户分离。
        // 优先级高于覆盖率插桩（HttpServlet 在 javax/ 下被 excludes 排除，但本钩子独立于 includes/excludes）。
        if (options.headerKey != null && RequestKeyWeaver.isTarget(className)) {
            try {
                return RequestKeyWeaver.weave(buf, options.headerKey, loader);
            } catch (Throwable t) {
                System.err.println("[peruser] request-key weave failed: " + className + " -> " + t);
                t.printStackTrace();
                return null;
            }
        }
        if (!options.shouldInstrument(className)) {
            return null;
        }
        // 插桩前先把【原始未插桩】字节落盘（classdumpdir），供 jacococli report --classfiles 使用
        if (options.classDumpDir != null) {
            dumpOriginal(className, buf);
        }
        try {
            return InstrumenterFlow.instrument(buf, className.replace('/', '.'));
        } catch (Throwable t) {
            System.err.println("[peruser] instrument failed: " + className + " -> " + t);
            t.printStackTrace();
            return null;
        }
    }

    private void dumpOriginal(String className, byte[] buf) {
        File out = new File(options.classDumpDir, className + ".class");
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(buf);
        } catch (IOException e) {
            System.err.println("[peruser] classdump failed: " + className + " -> " + e);
        }
    }
}
