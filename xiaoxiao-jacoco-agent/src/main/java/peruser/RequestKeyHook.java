package peruser;

import java.lang.reflect.Method;

/**
 * 请求级 key 钩子，由 {@link RequestKeyWeaver} 注入到 Servlet 统一入口
 * （javax/jakarta.servlet.http.HttpServlet.service）的入口/出口调用，框架无关。
 *
 * 设计约束（避免引入对 javax.servlet / jakarta.servlet 的依赖、也不占方法局部变量）：
 *   - tag 接收 Object 类型的 request，用反射读 getHeader(NAME)，零编译期依赖；
 *     同时兼容 javax.servlet.http.HttpServletRequest 与 jakarta.servlet.http.HttpServletRequest
 *     （两者都有 getHeader(String) 方法）；
 *   - 本次请求是否打标记在 ThreadLocal 里，untag 据此决定是否调用 ThreadProbeStore.end()。
 *
 * 这样 A、B 并发请求分别带 X-Coverage-Key:1 / :2 时，各自线程的 KEY 互不干扰，
 * 配合 ThreadProbeStore 的线程级 key 路径（getProbes path 1）即可并发按用户分离覆盖率。
 */
public final class RequestKeyHook {

    private static final ThreadLocal<Boolean> TAGGED = new ThreadLocal<>();

    /**
     * 打印归属明细。默认只打前 3 次（足以确认钩子是否工作，又不刷屏），debug=true 时打前 200 次。
     */
    private static volatile boolean DEBUG = false;
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean shouldLog() {
        if (DEBUG) {
            return LOGGED.incrementAndGet() <= 200;
        }
        return LOGGED.get() < 3 && LOGGED.incrementAndGet() <= 3;
    }

    private RequestKeyHook() {
    }

    public static void setDebug(boolean debug) {
        DEBUG = debug;
    }

    /**
     * 在请求入口调用：读请求头，非空则把本次请求归属到该 key（线程级）。
     *
     * @param request    真实的 HttpServletRequest（或任意实现了 getHeader(String) 的对象）
     * @param headerName 请求头名，如 X-Coverage-Key
     */
    public static void tag(Object request, String headerName) {
        // HttpServlet 的两个 service 重载都织入了钩子，同一次请求会进来两次；
        // 计数只在最外层记一次，否则自检数字会翻倍、看着像「请求数是 2 倍」。
        final boolean nested = TAGGED.get() != null;
        if (!nested) {
            Diagnostics.requestHooked();
        }
        String key = null;
        if (request != null && headerName != null && !headerName.isEmpty()) {
            key = readHeader(request, headerName);
            if (key != null) {
                key = key.trim();
                if (key.isEmpty()) key = null;
            }
        }
        if (key != null) {
            ThreadProbeStore.begin(key);
            if (!nested) {
                Diagnostics.requestTagged();
            }
            TAGGED.set(Boolean.TRUE);
            if (shouldLog()) {
                System.out.println("[xiaoxiao-jacoco] hook: " + headerName + "=" + key
                        + " -> 归属到 key=" + key + " (thread=" + Thread.currentThread().getName() + ")");
            }
        } else {
            TAGGED.set(Boolean.FALSE);
            if (shouldLog()) {
                System.out.println("[xiaoxiao-jacoco] hook: 请求未携带 " + headerName
                        + " -> 本次覆盖率不归属（丢弃），request=" + typeName(request));
            }
        }
    }

    /** 在请求出口（finally）调用：若本次打过标，提交该 key 的探针累计。 */
    public static void untag() {
        if (Boolean.TRUE.equals(TAGGED.get())) {
            ThreadProbeStore.end();
        }
        TAGGED.remove();
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /**
     * 反射读 getHeader(String)。
     *
     * 为什么不用 {@code request.getClass().getMethod(...)} 一把梭：容器的 request 实现类
     * 有时是非 public 的（Tomcat 的 Request/RequestFacade 各版本不一样、Jetty/Undertow 亦不同），
     * getMethod 能拿到 public 方法，但 invoke 会因为「声明类不可访问」抛 IllegalAccessException，
     * 于是静默变成「没读到 key」—— 表现就是覆盖率全空且看不出原因。
     * 这里按 实现类 -> 父类 -> 接口 -> 标准 HttpServletRequest 接口 的顺序找，并 setAccessible(true)。
     */
    private static String readHeader(Object request, String headerName) {
        Method m = findHeaderMethod(request.getClass());
        if (m == null) {
            return null;
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignore) {
            // JDK9+ 对 JDK 内部类的强封装会失败；这里的目标都是应用/容器类，通常没问题
        }
        try {
            Object v = m.invoke(request, headerName);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Method findHeaderMethod(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod("getHeader", String.class);
                if (m != null) return m;
            } catch (NoSuchMethodException ignore) {
                // 继续往上找
            } catch (Throwable ignore) {
                return null;
            }
        }
        // 接口方法（实现类不可访问时用接口方法 invoke 同样有效）
        Method m = findInInterfaces(c, new java.util.HashSet<Class<?>>());
        if (m != null) return m;
        // 兜底：直接用标准接口（javax 或 jakarta）
        for (String itf : new String[]{"javax.servlet.http.HttpServletRequest",
                "jakarta.servlet.http.HttpServletRequest",
                "javax.servlet.ServletRequest",
                "jakarta.servlet.ServletRequest"}) {
            try {
                Class<?> k = Class.forName(itf, false, c.getClassLoader());
                if (k.isAssignableFrom(c)) {
                    return k.getMethod("getHeader", String.class);
                }
            } catch (Throwable ignore) {
                // 该命名空间不存在
            }
        }
        return null;
    }

    private static Method findInInterfaces(Class<?> c, java.util.Set<Class<?>> visited) {
        if (c == null || !visited.add(c)) return null;
        for (Class<?> itf : c.getInterfaces()) {
            try {
                return itf.getMethod("getHeader", String.class);
            } catch (NoSuchMethodException ignore) {
                Method m = findInInterfaces(itf, visited);
                if (m != null) return m;
            }
        }
        return null;
    }
}
