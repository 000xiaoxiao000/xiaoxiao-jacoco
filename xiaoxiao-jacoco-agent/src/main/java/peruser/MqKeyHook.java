package peruser;

import java.lang.reflect.Method;
import java.nio.charset.Charset;

/**
 * 跨进程（MQ）key 透传钩子，由 {@link MqKeyWeaver} 织入 MQ 客户端的收发两端。
 *
 * <p><b>为什么必须有它</b>：key 存在 ThreadLocal 里，跨 JVM 就断了。以前只能让业务在消费端
 * 手写 {@code CoverageTracer.begin(msg.getUserProperty("X-Coverage-Key"))} —— 那是让
 * 【目标系统适应探针】。探针的立场是：跨进程传递必须由探针自己在客户端层面完成，
 * 业务一行代码都不用改（与 OpenTelemetry 把 trace context 注入 carrier 的做法一致）。
 *
 * <p>做法：生产端把 key 写进消息的<b>标准扩展区</b>（Kafka headers / RocketMQ user property /
 * RabbitMQ headers），消费端读出来归属。这些扩展区本来就是给中间件元数据用的，
 * 不占业务字段、不改业务语义；对面不是 Java 或没挂探针时，多一个 header 直接被忽略。
 *
 * <p>实现约束：
 * <ul>
 *   <li>零编译期依赖 —— 全部反射，没有 kafka/rabbit/rocket 的 jar 也照样能加载；</li>
 *   <li>只增不改 —— 写不进去（如 props 为 null、headers 不可变）就静默放弃，
 *       绝不为了塞 key 去改动业务消息的任何既有属性；</li>
 *   <li>无 key 时零动作 —— 生产端当前线程没 key 就不写 header，消息与不开探针时逐字节一致。</li>
 * </ul>
 */
public final class MqKeyHook {

    /** 默认透传用的 header / property 名，可用 agent 参数 mqheader= 覆盖。 */
    public static final String DEFAULT_HEADER = "X-Coverage-Key";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ThreadLocal<Boolean> TAGGED = new ThreadLocal<>();

    private static volatile String headerName = DEFAULT_HEADER;
    private static volatile boolean debug = false;
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    private MqKeyHook() {
    }

    public static void configure(String header, boolean debugFlag) {
        if (header != null && !header.trim().isEmpty()) {
            headerName = header.trim();
        }
        debug = debugFlag;
    }

    // ================= 生产端：把当前 key 写进待发送消息 =================

    /**
     * 写 key 到消息载体。返回「应当继续使用的载体对象」：
     * 绝大多数情况就是原对象（原地写入），AMQP 等不可变载体可能返回新对象，
     * 织入处会把返回值写回原来的局部变量槽位。
     */
    public static Object out(Object carrier) {
        final String key = effectiveKey();
        if (key == null || carrier == null) {
            return carrier;
        }
        try {
            if (writeKafka(carrier, key) || writeRocket(carrier, key) || writeAmqp(carrier, key)) {
                log("producer 注入 " + headerName + "=" + key + " -> " + typeName(carrier));
            }
        } catch (Throwable t) {
            // 绝不能因为探针写 header 失败而影响业务发消息
            log("producer 注入失败（已忽略，消息照常发送）: " + t);
        }
        return carrier;
    }

    // ================= 消费端：从消息里读回 key 并归属 =================

    /**
     * 从消息载体读 key 并归属当前线程。carrier 可以是单条消息，也可以是 List（批量消费取第一条）。
     * 由 {@link #outEnd()} 在方法出口配对结束。
     */
    public static void in(Object carrier) {
        String key = null;
        try {
            key = read(readable(carrier));
        } catch (Throwable t) {
            log("consumer 读取失败（已忽略）: " + t);
        }
        if (key == null) {
            TAGGED.set(Boolean.FALSE);
            return;
        }
        ThreadProbeStore.begin(key);
        TAGGED.set(Boolean.TRUE);
        log("consumer 读到 " + headerName + "=" + key + " -> 归属到 key=" + key
                + " (thread=" + Thread.currentThread().getName() + ")");
    }

    /** 消费方法出口（finally）调用：本次确实归属过才 end。 */
    public static void outEnd() {
        if (Boolean.TRUE.equals(TAGGED.get())) {
            ThreadProbeStore.end();
        }
        TAGGED.remove();
    }

    /**
     * 当前有效的归属 key，优先级与 {@link ThreadProbeStore#getProbes(Object[])} 完全一致：
     * 线程 key（含异步传递） -&gt; 外部控制端点设的全局 key -&gt; autokey 模式的合并 key。
     * 这样「探针看到的 key」与「探针透传出去的 key」永远是同一个，不会出现归属错位。
     */
    private static String effectiveKey() {
        String k = peruserrt.KeyBridge.get();
        if (k != null) {
            return k;
        }
        k = ThreadProbeStore.getCurrentKey();
        if (k != null) {
            return k;
        }
        return ThreadProbeStore.isMerge() ? ThreadProbeStore.mergeKey() : null;
    }

    // ================= 反射读写 =================

    /** 批量消费（RocketMQ）传进来的是 List，取第一条做归属。 */
    private static Object readable(Object carrier) {
        if (carrier instanceof java.util.List) {
            java.util.List<?> l = (java.util.List<?>) carrier;
            return l.isEmpty() ? null : l.get(0);
        }
        return carrier;
    }

    /** 依次尝试三种 MQ 的读法，谁的方法存在就用谁。 */
    private static String read(Object msg) throws Exception {
        if (msg == null) {
            return null;
        }
        // Kafka ConsumerRecord：headers().lastHeader(name).value()
        Object headers = invokeIfExists(msg, "headers");
        if (headers != null) {
            Object h = invokeIfExists(headers, "lastHeader", headerName);
            if (h != null) {
                Object v = invokeIfExists(h, "value");
                if (v instanceof byte[]) {
                    return new String((byte[]) v, UTF8);
                }
                if (v instanceof String) {
                    return (String) v;
                }
            }
        }
        // RocketMQ Message：getProperty(name)
        Object p = invokeIfExists(msg, "getProperty", headerName);
        if (p instanceof String) {
            return (String) p;
        }
        // Spring AMQP Message：getMessageProperties().getHeader(name)
        Object props = invokeIfExists(msg, "getMessageProperties");
        if (props == null) {
            props = invokeIfExists(msg, "getHeaders"); // AMQP.BasicProperties
        }
        if (props != null) {
            Object v = invokeIfExists(props, "getHeader", headerName);
            if (v instanceof String) {
                return (String) v;
            }
        }
        return null;
    }

    /** Kafka ProducerRecord：headers().add(name, bytes) */
    private static boolean writeKafka(Object msg, String key) throws Exception {
        Object headers = invokeIfExists(msg, "headers");
        if (headers == null) {
            return false;
        }
        Method add = findMethod(headers.getClass(), "add", String.class, byte[].class);
        if (add == null) {
            return false;
        }
        add.setAccessible(true);
        add.invoke(headers, headerName, key.getBytes(UTF8));
        return true;
    }

    /** RocketMQ Message：putUserProperty(name, key) */
    private static boolean writeRocket(Object msg, String key) throws Exception {
        Method m = findMethod(msg.getClass(), "putUserProperty", String.class, String.class);
        if (m == null) {
            return false;
        }
        m.setAccessible(true);
        m.invoke(msg, headerName, key);
        return true;
    }

    /**
     * RabbitMQ：往 BasicProperties 的 headers map 里塞一条。
     * 只在 map 已存在且可写时写入 —— 不为塞 key 去新建/替换消息属性，避免动到业务的既有属性。
     */
    private static boolean writeAmqp(Object props, String key) throws Exception {
        if (props == null) {
            return false;
        }
        Object map = invokeIfExists(props, "getHeaders");
        if (map == null) {
            Object springProps = invokeIfExists(props, "getMessageProperties");
            if (springProps != null) {
                map = invokeIfExists(springProps, "getHeaders");
            }
        }
        if (!(map instanceof java.util.Map)) {
            return false;
        }
        java.util.Map<Object, Object> m = asMap(map);
        try {
            m.put(headerName, key);
            return true;
        } catch (UnsupportedOperationException e) {
            return false; // 不可变 map，放弃，不抛给业务
        }
    }

    // ================= 反射小工具 =================

    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, Object> asMap(Object map) {
        return (java.util.Map<Object, Object>) map;
    }

    /** 无参调用：方法不存在返回 null。 */
    private static Object invokeIfExists(Object target, String name) throws Exception {
        Method m = findMethod(target.getClass(), name);
        if (m == null) {
            return null;
        }
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** 单 String 参调用：方法不存在返回 null。 */
    private static Object invokeIfExists(Object target, String name, String arg) throws Exception {
        Method m = findMethod(target.getClass(), name, String.class);
        if (m == null) {
            return null;
        }
        m.setAccessible(true);
        return m.invoke(target, arg);
    }

    /** 沿类层次找方法（实现类可能是非 public 的，找到后 setAccessible）。 */
    private static Method findMethod(Class<?> c, String name, Class<?>... ptypes) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, ptypes);
            } catch (NoSuchMethodException ignore) {
                // 继续往上
            } catch (Throwable ignore) {
                return null;
            }
        }
        for (Class<?> itf : allInterfaces(c)) {
            try {
                return itf.getMethod(name, ptypes);
            } catch (Throwable ignore) {
                // 下一个
            }
        }
        return null;
    }

    private static java.util.Set<Class<?>> allInterfaces(Class<?> c) {
        java.util.Set<Class<?>> out = new java.util.LinkedHashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            collect(k.getInterfaces(), out);
        }
        return out;
    }

    private static void collect(Class<?>[] ifs, java.util.Set<Class<?>> out) {
        for (Class<?> i : ifs) {
            if (out.add(i)) {
                collect(i.getInterfaces(), out);
            }
        }
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static void log(String s) {
        if (!debug) {
            return;
        }
        if (LOGGED.incrementAndGet() <= 200) {
            System.out.println("[xiaoxiao-jacoco] mqkey: " + s);
        }
    }
}
