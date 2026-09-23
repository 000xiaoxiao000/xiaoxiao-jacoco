package peruser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 跨服务（出站 HTTP / RPC）key 透传钩子，由 {@link HttpKeyWeaver} 织入各类客户端。
 *
 * <p><b>为什么必须有它</b>：key 存在 ThreadLocal 里，跨 JVM 就断了。A 服务带着
 * {@code X-Coverage-Key} 进来，A 再用 Feign / HttpClient 调 B 时，那个头【不会】自动跟过去 ——
 * B 侧读不到 key，本次调用的覆盖直接丢弃。以前只能让业务在 A 侧手写拦截器把 key 塞进请求头，
 * 那是让【目标系统适应探针】。探针的立场是：跨进程传递必须由探针自己在客户端层面完成，
 * 业务一行代码都不用改（与 OpenTelemetry 把 trace context 注入 carrier 的做法一致）。
 *
 * <p>做法：在请求真正发出去之前，把当前 key 写进请求的标准 header 区。
 * header 区本来就是给中间件元数据用的，不占业务字段、不改业务语义；
 * 对面没挂探针时多一个 header 直接被忽略。
 *
 * <p>实现约束（与 {@link MqKeyHook} 一致）：
 * <ul>
 *   <li>零编译期依赖 —— 全部反射，feign/okhttp/apache/dubbo/grpc 一个都没有也照样能加载；</li>
 *   <li>只增不改 —— 已有同名 header 一律跳过，绝不覆盖业务或网关已设置的值；
 *       写不进去（不可变载体、只读 HttpHeaders）就静默放弃；</li>
 *   <li>无 key 时零动作 —— 当前线程没归属就不写 header，请求与不开探针时逐字节一致；</li>
 *   <li>任何异常都吞掉 —— 绝不能因为探针写 header 而影响业务发请求。</li>
 * </ul>
 *
 * <p>默认关闭（{@code httpkey=true} 才启用）：给业务请求加 header 属于【改变目标系统的数据】，
 * 必须由使用方显式点头，探针不能自作主张。
 */
public final class HttpKeyHook {

    /** 默认透传用的 header 名，可用 agent 参数 httpheader= 覆盖（缺省还会继承 headerkey= 的名字）。 */
    public static final String DEFAULT_HEADER = "X-Coverage-Key";

    private static volatile String headerName = DEFAULT_HEADER;
    private static volatile boolean debug = false;
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    private HttpKeyHook() {
    }

    public static void configure(String header, boolean debugFlag) {
        if (header != null && !header.trim().isEmpty()) {
            headerName = header.trim();
        }
        debug = debugFlag;
    }

    /**
     * 把当前 key 写进请求载体。返回「应当继续使用的载体对象」：
     * 绝大多数情况就是原对象（原地写入），织入处会把返回值写回原来的局部变量槽位。
     */
    public static Object out(Object carrier) {
        final String key = EffectiveKey.get();
        if (key == null || carrier == null) {
            return carrier;
        }
        try {
            if (write(carrier, key)) {
                log("注入 " + headerName + "=" + key + " -> " + carrier.getClass().getName());
            }
        } catch (Throwable t) {
            // 绝不能因为探针写 header 失败而影响业务发请求
            log("注入失败（已忽略，请求照常发送）: " + t);
        }
        return carrier;
    }

    /** 依次尝试各客户端的写法，谁的方法存在就用谁。 */
    private static boolean write(Object c, String key) throws Exception {
        return writeFeign(c, key)
                || writeOkHttp(c, key)
                || writeApache(c, key)
                || writeSpringHeaders(c, key)
                || writeDubbo(c, key)
                || writeGrpc(c, key);
    }

    // ===================== Feign：RequestTemplate =====================

    /**
     * feign.RequestTemplate：先查 headers() 是否已有该头（header(String,String...) 是【替换】语义），
     * 没有才写入。
     */
    private static boolean writeFeign(Object t, String key) throws Exception {
        if (!Reflect.isClass(t, "feign.RequestTemplate")) {
            return false;
        }
        Object headers = Reflect.invoke(t, "headers");
        if (headers instanceof java.util.Map) {
            if (((java.util.Map<?, ?>) headers).containsKey(headerName)) {
                return false;
            }
        }
        Method m = Reflect.findMethod(t.getClass(), "header", String.class, String[].class);
        if (m == null) {
            return false;
        }
        m.invoke(t, headerName, new String[]{key});
        return true;
    }

    // ===================== OkHttp：Request.Builder（含 Retrofit） =====================

    /** 用 addHeader（追加），不用 header()（替换）。同一 Builder 重复 build 时靠 getHeaders 去重。 */
    private static boolean writeOkHttp(Object b, String key) throws Exception {
        if (!Reflect.isClass(b, "okhttp3.Request$Builder", "com.squareup.okhttp.Request$Builder")) {
            return false;
        }
        if (okHttpAlreadyHas(b)) {
            return false;
        }
        Method add = Reflect.findMethod(b.getClass(), "addHeader", String.class, String.class);
        if (add == null) {
            return false;
        }
        add.invoke(b, headerName, key);
        return true;
    }

    /**
     * okhttp 4.x 的 Builder 暴露 getHeaders$okhttp()（Kotlin 生成），可据此判断去重；
     * 3.x 拿不到就放弃去重（重复一个同值 header 无害：对面取第一个值）。
     */
    private static boolean okHttpAlreadyHas(Object b) {
        Object hb = Reflect.invoke(b, "getHeaders$okhttp");
        if (hb == null) {
            return false;
        }
        Object h = Reflect.invoke(hb, "build");
        if (h == null) {
            return false;
        }
        return Reflect.invokeStr(h, "get", headerName) != null;
    }

    // ===================== Apache HttpClient 4.x / 5.x =====================

    /** HttpRequest / ClassicHttpRequest：containsHeader 为空才 addHeader。 */
    private static boolean writeApache(Object req, String key) throws Exception {
        Method contains = Reflect.findMethod(req.getClass(), "containsHeader", String.class);
        if (contains == null) {
            return false;
        }
        Method add = Reflect.findMethod(req.getClass(), "addHeader", String.class, String.class);
        if (add == null) {
            // hc5 的 HttpMessage 有 addHeader(String, Object)
            add = Reflect.findMethod(req.getClass(), "addHeader", String.class, Object.class);
        }
        if (add == null) {
            return false;
        }
        Object v = contains.invoke(req, headerName);
        if (v instanceof Boolean && ((Boolean) v)) {
            return false;
        }
        add.invoke(req, headerName, key);
        return true;
    }

    // ===================== Spring：HttpHeaders（RestTemplate / WebClient 通用兜底） =====================

    /**
     * org.springframework.http.HttpHeaders：覆盖所有 ClientHttpRequest 实现
     * （Simple / OkHttp3 / HttpComponents / Netty4），因为织的是 getHeaders() 的返回值。
     * 只读 HttpHeaders（readOnlyHttpHeaders）写入会抛 UnsupportedOperationException —— 静默放弃。
     */
    private static boolean writeSpringHeaders(Object h, String key) throws Exception {
        if (!Reflect.isClass(h, "org.springframework.http.HttpHeaders")) {
            return false;
        }
        Method contains = Reflect.findMethod(h.getClass(), "containsKey", Object.class);
        if (contains != null) {
            Object v = contains.invoke(h, headerName);
            if (v instanceof Boolean && ((Boolean) v)) {
                return false;
            }
        }
        Method add = Reflect.findMethod(h.getClass(), "add", String.class, String.class);
        if (add == null) {
            return false;
        }
        try {
            add.invoke(h, headerName, key);
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof UnsupportedOperationException) {
                return false;
            }
            throw e;
        }
    }

    // ===================== Dubbo：Invocation 附件 =====================

    /**
     * 优先写 Invocation 本身的附件（3.x setObjectAttachment / 2.x setAttachment）；
     * 载体上没有这些方法时兜底写 RpcContext 的客户端附件。
     */
    private static boolean writeDubbo(Object inv, String key) throws Exception {
        Method getObj = Reflect.findMethod(inv.getClass(), "getObjectAttachment", String.class);
        Method getStr = (getObj != null) ? null : Reflect.findMethod(inv.getClass(), "getAttachment", String.class);
        if (getObj == null && getStr == null) {
            return false;
        }
        Object cur = (getObj != null) ? getObj.invoke(inv, headerName) : getStr.invoke(inv, headerName);
        if (cur != null) {
            return false;
        }
        Method setObj = Reflect.findMethod(inv.getClass(), "setObjectAttachment", String.class, Object.class);
        if (setObj != null) {
            setObj.invoke(inv, headerName, key);
            return true;
        }
        Method setStr = Reflect.findMethod(inv.getClass(), "setAttachment", String.class, String.class);
        if (setStr != null) {
            setStr.invoke(inv, headerName, key);
            return true;
        }
        return writeDubboContext(key);
    }

    /** 兜底：写 RpcContext 的客户端附件（3.x getClientAttachment / 2.x getContext）。 */
    private static boolean writeDubboContext(String key) {
        try {
            Class<?> rc = Class.forName("org.apache.dubbo.rpc.RpcContext");
            Method g = Reflect.findMethod(rc, "getClientAttachment");
            if (g == null) {
                g = Reflect.findMethod(rc, "getContext");
            }
            if (g == null) {
                return false;
            }
            Object att = g.invoke(null);
            Method set = Reflect.findMethod(att.getClass(), "setAttachment", String.class, String.class);
            if (set == null) {
                set = Reflect.findMethod(att.getClass(), "setAttachment", String.class, Object.class);
            }
            if (set == null) {
                return false;
            }
            set.invoke(att, headerName, key);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== gRPC：Metadata =====================

    /** io.grpc.Metadata：全反射构造 Key（Metadata.Key.of(name, ASCII_STRING_MARSHALLER)）。 */
    private static boolean writeGrpc(Object md, String key) throws Exception {
        if (!Reflect.isClass(md, "io.grpc.Metadata")) {
            return false;
        }
        Class<?> mdClass = md.getClass();
        Field f = mdClass.getField("ASCII_STRING_MARSHALLER");
        Object marshaller = f.get(null);
        Class<?> keyClass = Class.forName("io.grpc.Metadata$Key", false, mdClass.getClassLoader());
        Method of = Reflect.findMethod(keyClass, "of", String.class, f.getType());
        if (of == null) {
            return false;
        }
        Object k = of.invoke(null, headerName, marshaller);
        Method contains = Reflect.findMethod(mdClass, "containsKey", keyClass);
        if (contains != null) {
            Object v = contains.invoke(md, k);
            if (v instanceof Boolean && ((Boolean) v)) {
                return false;
            }
        }
        Method put = Reflect.findMethod(mdClass, "put", keyClass, Object.class);
        if (put == null) {
            return false;
        }
        put.invoke(md, k, key);
        return true;
    }

    private static void log(String s) {
        if (!debug) {
            return;
        }
        if (LOGGED.incrementAndGet() <= 200) {
            System.out.println("[xiaoxiao-jacoco] httpkey: " + s);
        }
    }
}
