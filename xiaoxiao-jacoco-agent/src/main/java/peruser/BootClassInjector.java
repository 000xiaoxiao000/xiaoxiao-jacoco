package peruser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 把 {@code peruserrt} 包单独打成临时 jar 并注入 bootstrap 类加载器搜索路径。
 *
 * 目的：织入 JDK 线程池（java.util.concurrent.*，bootstrap 加载）后，那些类要调用
 * {@code peruserrt.KeyBridge} 来传递 key —— bootstrap 看不到系统类路径上的类，必须先注入。
 *
 * 只注入 peruserrt 这一个【无状态依赖、自包含】的小包，绝不注入 peruser.*：
 *   - peruser.PerUserAgent 已由系统类加载器加载（JVM 加载 Premain-Class 在前）；
 *   - 若 peruser.* 也进 bootstrap，后续加载的 peruser.ThreadProbeStore 会走 bootstrap 副本，
 *     与系统类路径上的 PerUserAgent 形成两份静态字段 —— 探针写进一份、读出另一份，覆盖率全空。
 * peruserrt 则相反：只放进临时 jar（不在系统类路径上），注入前从未被加载，因此全局唯一一份。
 */
public final class BootClassInjector {

    private static final String[] CLASSES = {
            "peruserrt/KeyBridge.class",
            "peruserrt/KeyRunnable.class",
            "peruserrt/KeyCallable.class",
    };

    private static volatile boolean available = false;

    private BootClassInjector() {
    }

    public static boolean available() {
        return available;
    }

    /** 尽力而为：失败（安全管理器、只读临时目录等）只告警，异步传递降级为「new Thread 继承」。 */
    public static synchronized void inject(Instrumentation inst) {
        if (available) {
            return;
        }
        File tmp = null;
        try {
            tmp = File.createTempFile("xiaoxiao-jacoco-boot", ".jar");
            tmp.deleteOnExit();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tmp))) {
                for (String n : CLASSES) {
                    byte[] b = readResource(n);
                    if (b == null) {
                        throw new IllegalStateException("missing resource " + n);
                    }
                    jos.putNextEntry(new JarEntry(n));
                    jos.write(b);
                    jos.closeEntry();
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tmp));
            available = true;
            System.out.println("[xiaoxiao-jacoco] async key propagation enabled (peruserrt injected into bootstrap)");
        } catch (Throwable t) {
            System.err.println("[xiaoxiao-jacoco] warning: cannot inject peruserrt into bootstrap -> "
                    + t + "；异步覆盖率传递将只对 new Thread() 生效，线程池场景请用 async=false 或忽略");
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    private static byte[] readResource(String name) throws Exception {
        ClassLoader cl = BootClassInjector.class.getClassLoader();
        InputStream in = (cl == null)
                ? ClassLoader.getSystemResourceAsStream(name)
                : cl.getResourceAsStream(name);
        if (in == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
