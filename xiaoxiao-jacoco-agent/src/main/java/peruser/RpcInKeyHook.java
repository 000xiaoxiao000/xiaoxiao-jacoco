package peruser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 跨服务【入站】RPC 的 key 归属钩子（二期），由 {@link RpcInKeyWeaver} 织入
 * Dubbo provider / gRPC server / Spring WebFlux Controller。
 *
 * <p><b>为什么需要它</b>：一期（{@link HttpKeyHook}）只解决了【出站】——A 调 B 时把 key 写进请求。
 * 但 B 这一侧的入站读 key 只织了 Servlet（{@link RequestKeyWeaver}），
 * 而 A→B 走 Dubbo / gRPC 时 B 根本不经过 Servlet：B 的线程读不到 key，
 * {@code ThreadProbeStore.getProbes} 返回一次性数组 → 本次调用的覆盖直接丢弃。
 * 于是「跨服务按 key 分离覆盖率」在 RPC 链路上是断的。
 *
 * <p>本钩子补齐入站那一半：在 provider / server 的业务方法【执行前】从调用载体里读回 key 并归属，
 * 业务代码一行都不用改。
 *
 * <p>入站是【只读】动作 —— 不写任何业务数据、不给请求加字段，因此默认就随
 * {@code headerkey=} / {@code httpkey=true} 一起启用（用 {@code rpckey=false} 可显式关掉），
 * 这与出站（{@code httpkey}，会给请求加 header，默认关）的立场不同。
 *
 * <p>实现约束（与 {@link HttpKeyHook} / {@link MqKeyHook} 一致）：
 * <ul>
 *   <li>零编译期依赖 —— dubbo / grpc 一个都没用也照样加载，全部反射；</li>
 *   <li>读不到 key 就零动作 —— 绝不影响没有探针流量标识的调用；</li>
 *   <li>任何异常都吞掉 —— 绝不能因为探针读 key 而影响业务被调；</li>
 *   <li>配对用栈而非布尔位 —— 一次调用可能连过多层（filter → invoker），
 *       且 gRPC 的 enter/exit 不在同一个方法里（startCall / close），栈 + 兜底收尾可防 key 泄漏到线程上。</li>
 * </ul>
 */
public final class RpcInKeyHook {

    /** 与 {@link HttpKeyHook} 同名，保证「A 用什么头出去、B 就用什么头进来」。 */
    public static final String DEFAULT_HEADER = "X-Coverage-Key";

    private static volatile String headerName = DEFAULT_HEADER;
    private static volatile boolean debug = false;
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 本次 enter 是否真的 begin 过；exit 按栈配对，避免嵌套/跨方法时提前结束别人的归属。 */
    private static final ThreadLocal<java.util.ArrayDeque<Boolean>> STACK =
            new ThreadLocal<java.util.ArrayDeque<Boolean>>() {
                @Override
                protected java.util.ArrayDeque<Boolean> initialValue() {
                    return new java.util.ArrayDeque<Boolean>();
                }
            };

    private RpcInKeyHook() {
    }

    public static void configure(String header, boolean debugFlag) {
        if (header != null && !header.trim().isEmpty()) {
            headerName = header.trim();
        }
        debug = debugFlag;
    }

    /**
     * 入站调用入口：从调用载体（Dubbo Invocation / gRPC Metadata / Map）读 key 并归属当前线程。
     * 必须与 {@link #exit()} 成对出现。
     */
    public static void enter(Object carrier) {
        String key = null;
        try {
            key = read(carrier);
        } catch (Throwable t) {
            log("入站读取失败（已忽略，业务照常执行）: " + t);
        }
        if (key == null) {
            // 也要压栈：保证后面那次 exit 有东西可弹，不会因为「少压一个」而误结束外层归属
            stack().push(Boolean.FALSE);
            return;
        }
        if (key.equals(EffectiveKey.get())) {
            // 已经归属到同一个 key（典型：A 侧 httpkey 刚写进去、consumer 侧又走到 AbstractInvoker）
            // —— 不重复 begin，避免嵌套计数与日志噪音
            stack().push(Boolean.FALSE);
            return;
        }
        drainDangling();
        ThreadProbeStore.begin(key);
        stack().push(Boolean.TRUE);
        log("入站读到 " + headerName + "=" + key + " -> 归属到 key=" + key
                + " (thread=" + Thread.currentThread().getName() + ")");
    }

    /** 入站调用出口：本次确实 begin 过才结束归属。 */
    public static void exit() {
        java.util.ArrayDeque<Boolean> s = STACK.get();
        Boolean b = s.isEmpty() ? null : s.pollLast();
        if (Boolean.TRUE.equals(b)) {
            ThreadProbeStore.end();
        }
    }

    private static java.util.ArrayDeque<Boolean> stack() {
        return STACK.get();
    }

    /**
     * 兜底收尾：栈里还压着未配对的 enter（gRPC 场景 close 没走到、或异常路径吞了 finally），
     * 逐个 end 并清空，防止 key 常驻在复用线程上污染后面的请求。
     */
    private static void drainDangling() {
        java.util.ArrayDeque<Boolean> s = STACK.get();
        if (s == null || s.isEmpty()) {
            return;
        }
        int n = 0;
        for (Boolean b : s) {
            if (Boolean.TRUE.equals(b)) {
                n++;
            }
        }
        for (int i = 0; i < n; i++) {
            try {
                ThreadProbeStore.end();
            } catch (Throwable ignore) {
                // 收尾失败也不能影响本次归属
            }
        }
        s.clear();
    }

    // ===================== 读 key =====================

    /** 依次尝试各载体的读法：Map（自研 RPC 兜底）→ Dubbo → gRPC → WebFlux → 通用 getHeader。 */
    private static String read(Object c) {
        if (c == null) {
            return null;
        }
        String v = readMap(c);
        if (v == null) {
            v = readDubbo(c);
        }
        if (v == null) {
            v = readGrpc(c);
        }
        if (v == null) {
            v = readReactive(c);
        }
        if (v == null) {
            Object h = Reflect.invokeStr(c, "getHeader", headerName);
            if (h instanceof String) {
                v = (String) h;
            }
        }
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** 自研 RPC / 部分框架直接把上下文塞在 Map 里。 */
    private static String readMap(Object c) {
        if (!(c instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) c).get(headerName);
        if (v instanceof String) {
            return (String) v;
        }
        // 有些框架（含 gRPC metadata 内部）用 byte[] 存
        return (v instanceof byte[]) ? new String((byte[]) v) : null;
    }

    /**
     * Dubbo：provider 侧的 Invocation 附件。
     * 3.x 是 getObjectAttachment，2.x 是 getAttachment（顺序不能反：3.x 上 getAttachment 只返回 String 附件，
     * 但两者都存在的版本里 object 版信息更全）。
     */
    private static String readDubbo(Object inv) {
        Method getObj = Reflect.findMethod(inv.getClass(), "getObjectAttachment", String.class);
        Method get = (getObj != null) ? getObj
                : Reflect.findMethod(inv.getClass(), "getAttachment", String.class);
        if (get == null) {
            return null;
        }
        Object v;
        try {
            v = get.invoke(inv, headerName);
        } catch (Throwable t) {
            return null;
        }
        return (v instanceof String) ? (String) v : null;
    }

    /** gRPC：io.grpc.Metadata.get(Key.of(name, ASCII_STRING_MARSHALLER))。 */
    private static String readGrpc(Object md) {
        if (!Reflect.isClass(md, "io.grpc.Metadata")) {
            return null;
        }
        try {
            Class<?> mdClass = md.getClass();
            Field f = mdClass.getField("ASCII_STRING_MARSHALLER");
            Object marshaller = f.get(null);
            Class<?> keyClass = Class.forName("io.grpc.Metadata$Key", false, mdClass.getClassLoader());
            Method of = Reflect.findMethod(keyClass, "of", String.class, f.getType());
            if (of == null) {
                return null;
            }
            Object k = of.invoke(null, headerName, marshaller);
            Method get = Reflect.findMethod(mdClass, "get", keyClass);
            if (get == null) {
                return null;
            }
            Object v = get.invoke(md, k);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Spring WebFlux（非 Servlet 入站）：从 {@code ServerWebExchange} / {@code ServerHttpRequest} 读请求头。
     *
     * <p>为什么不在 {@code HttpWebHandlerAdapter#handle}（统一入站口）读：那个方法返回的是
     * {@code Mono}，【立即返回】，业务要等 subscribe 才执行 —— 用 try/finally 框它会在业务跑之前就退出。
     * 而 {@code InvocableHandlerMethod} 的业务调用 lambda 手里就拿着 exchange，且
     * {@code Method.invoke}（业务 controller 方法）在 lambda 体内是<b>同步</b>执行的，
     * 在那里读 + try/finally 归属才是正确的「业务方法窗口」，也无需 pending 暂存 / 跨线程传递 / 时间窗。
     *
     * <p>{@code HttpHeaders#getFirst} 本身大小写不敏感，与 Servlet 侧行为一致。
     */
    private static String readReactive(Object c) {
        Object req;
        if (isReactiveType(c, "org.springframework.web.server.ServerWebExchange")) {
            req = Reflect.invoke(c, "getRequest");
        } else if (isReactiveType(c, "org.springframework.http.server.reactive.ServerHttpRequest")) {
            req = c;
        } else {
            return null;
        }
        if (req == null) {
            return null;
        }
        Object headers = Reflect.invoke(req, "getHeaders");
        if (headers == null) {
            return null;
        }
        Object v = Reflect.invokeStr(headers, "getFirst", headerName);
        if (v instanceof String) {
            return (String) v;
        }
        // 兜底：MultiValueMap.get(name) 返回 List（个别桥接实现只暴露 get）
        Object list = Reflect.invokeStr(headers, "get", headerName);
        if (list instanceof java.util.List<?>) {
            java.util.List<?> l = (java.util.List<?>) list;
            if (!l.isEmpty() && l.get(0) instanceof String) {
                return (String) l.get(0);
            }
        }
        return null;
    }

    /** 按类型（含父类型/接口）判断，不能用 {@link Reflect#isClass} —— exchange 的实现类是 DefaultServerWebExchange。 */
    private static boolean isReactiveType(Object o, String type) {
        if (o == null) {
            return false;
        }
        try {
            return Class.forName(type, false, o.getClass().getClassLoader()).isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String s) {
        if (!debug) {
            return;
        }
        if (LOGGED.incrementAndGet() <= 200) {
            System.out.println("[xiaoxiao-jacoco] rpckey: " + s);
        }
    }
}
