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
 * 是否插桩由 {@link Options#shouldInstrument(ClassLoader, String, ProtectionDomain)} 决定，
 * 规则与官方 JaCoCo agent 一致（includes/excludes/exclclassloader/inclbootstrapclasses/inclnolocationclasses）。
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
        // 绝不插桩 agent 自身：transform 里会用到 peruser.* / ASM / jacoco，若这些类在加载时又被
        // 本 transformer 拦下并触发自身加载，JVM 会抛
        // "LinkageError: loader 'app' attempted duplicate class definition"（递归定义）。
        if (isAgentInternal(className)) {
            return null;
        }
        Diagnostics.classSeen();
        // MQ 生产端（Kafka/RocketMQ/RabbitMQ 客户端）：三方类通常不在 includes= 里，
        // 所以这条分支独立于覆盖率插桩，按精确类名匹配，匹配不上零开销。
        if (options.mqKey && MqKeyWeaver.isProducerClass(className)) {
            try {
                byte[] out = MqKeyWeaver.weaveProducer(buf, className);
                if (out != null) return out;
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] mq producer weave failed: " + className + " -> " + t);
                return null;
            }
        }
        // 跨服务出站（Feign/OkHttp/Apache/Dubbo/gRPC/Spring）：把当前 key 写进出站请求头，
        // 让 B 服务（挂同一 agent + headerkey=）能读到同一个 key。默认关闭（httpkey=true）——
        // 给业务请求加 header 属于改变目标系统的数据，必须显式开启；无 key 时请求与不开探针时完全一致。
        // 这些客户端类通常不在 includes= 里，因此同样独立于覆盖率插桩、放在 shouldInstrument 之前。
        if (options.httpKey && HttpKeyWeaver.isTarget(className)) {
            try {
                byte[] out = HttpKeyWeaver.weave(buf, className, loader);
                if (out != null) {
                    if (options.debug) {
                        System.out.println("[xiaoxiao-jacoco] http outbound woven: " + className);
                    }
                    return out;
                }
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] http outbound weave failed: " + className + " -> " + t);
                return null;
            }
        }
        // 跨服务【入站】RPC（Dubbo provider / gRPC server）：B 侧没有 Servlet 入口时，
        // 靠本织入从调用载体（Invocation 附件 / gRPC Metadata）读回 key，补齐「A 出站 + B 入站」闭环。
        // 入站只读、不写任何业务数据，因此默认随 headerkey= / httpkey=true 生效（rpckey=false 可关）。
        if (options.rpcKey && RpcInKeyWeaver.isTarget(className)) {
            try {
                byte[] out = RpcInKeyWeaver.weave(buf, className, loader);
                if (out != null) {
                    if (options.debug) {
                        System.out.println("[xiaoxiao-jacoco] rpc inbound woven: " + className);
                    }
                    return out;
                }
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] rpc inbound weave failed: " + className + " -> " + t);
                return null;
            }
        }
        // parallelStream 归属：织入 ForkJoinTask.fork/doExec，让流里的覆盖率也能按请求细分。
        // 默认关闭（streamkey=true 才启用）—— ForkJoinTask 是并行热路径，不经用户同意不插指令。
        if (options.streamPropagate && BootClassInjector.available() && StreamKeyWeaver.isTarget(className)) {
            try {
                return StreamKeyWeaver.weave(buf);
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] stream weave failed: " + className + " -> " + t);
                return null;
            }
        }
        // 异步归属：给 JDK 线程池的提交入口织入「提交时捕获 key、执行时恢复 key」，
        // 让 @Async / CompletableFuture / 线程池里的覆盖也归到发起请求的 key。
        // JDK 类由 bootstrap 加载，必须等 peruserrt 注入成功才织入；可用 async=false 关闭。
        if (options.asyncPropagate && BootClassInjector.available() && AsyncKeyWeaver.isTarget(className)) {
            try {
                return AsyncKeyWeaver.weave(buf);
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] async weave failed: " + className + " -> " + t);
                return null;
            }
        }
        // headerkey：把按请求头归属的钩子织入 Servlet 统一入口 HttpServlet.service，
        // 框架无关（javax/jakarta 双命名空间）、零改目标系统、支持并发按用户分离。
        // 优先级高于覆盖率插桩（HttpServlet 在 javax/ 下，独立于 includes/excludes 判断）。
        if (options.headerKey != null && RequestKeyWeaver.isTarget(className)) {
            try {
                return RequestKeyWeaver.weave(buf, options.headerKey, loader);
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] request-key weave failed: " + className + " -> " + t);
                return null;
            }
        }
        if (!options.shouldInstrument(loader, className, domain)) {
            // 只是被过滤掉（不是自身类）：记下样例，供 includes 自检提示给出可直接抄的通配
            if (isCandidateForCoverage(loader, className)) {
                Diagnostics.skippedByFilter(className);
                if (hasNoSourceLocation(domain)) {
                    // Spring Boot 可执行 jar 高发：类没有 CodeSource，默认被 JaCoCo 跳过
                    Diagnostics.classNoLocation();
                }
            }
            return null;
        }
        // 插桩前先把【原始未插桩】字节落盘（classdumpdir），供 cli report --classfiles 使用
        if (options.classDumpDir != null) {
            dumpOriginal(className, buf);
        }
        // 缓存原始字节到内存（dumpclasses 命令用，免容器访问即可拿 classfiles 当报告分母）
        if (options.classCache) {
            ClassCache.put(className, buf);
        }
        try {
            byte[] out = InstrumenterFlow.instrument(buf, className.replace('/', '.'));
            Diagnostics.classInstrumented(className);
            // MQ 消费端：@KafkaListener / @RabbitListener / @RocketMQMessageListener 等，
            // 探针自己从消息 header 取回 key，业务不用写一行代码。
            if (options.mqKey) {
                try {
                    byte[] w = MqKeyWeaver.weaveConsumer(out, loader);
                    if (w != null) {
                        out = w;
                        if (options.debug) {
                            System.out.println("[xiaoxiao-jacoco] mq consumer woven: " + className);
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[xiaoxiao-jacoco] mq consumer weave failed: " + className + " -> " + t);
                }
            }
            if (options.debug) {
                System.out.println("[xiaoxiao-jacoco] instrumented: " + className);
            }
            return out;
        } catch (Throwable t) {
            System.err.println("[xiaoxiao-jacoco] instrument failed: " + className + " -> " + t);
            return null;
        }
    }

    /**
     * 是否「本可以插桩」（即没被 bootstrap / source-location / exclclassloader 挡掉，
     * 仅因 includes/excludes 没匹配上而跳过）。用于自检提示里给出 includes 建议。
     */
    private boolean isCandidateForCoverage(ClassLoader loader, String className) {
        if (className == null || className.startsWith("peruser/")) return false;
        if (loader == null && !options.inclBootstrapClasses()) return false;
        // JDK / 三方基础设施类噪音太大，不作为建议来源
        return !(className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("jakarta/") || className.startsWith("jdk/")
                || className.startsWith("sun/") || className.startsWith("com/sun/")
                || className.startsWith("org/springframework/") || className.startsWith("ch/qos/")
                || className.startsWith("org/apache/") || className.startsWith("io/netty/")
                || className.startsWith("org/objectweb/") || className.startsWith("org/jacoco/")
                || className.startsWith("com/xiaoxiao/jacoco/shaded/"));
    }

    /** 没有 source location（CodeSource / location 为空）的类，JaCoCo 默认跳过。 */
    private static boolean hasNoSourceLocation(ProtectionDomain domain) {
        return domain == null || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation() == null;
    }

    /** agent 自身的类（含 shaded 进来的 ASM / JaCoCo），一律不插桩，避免递归加载。 */
    private static boolean isAgentInternal(String className) {
        if (className == null) {
            return true;
        }
        return className.startsWith("peruser/")
                || className.startsWith("peruserrt/")
                || className.startsWith("org/jacoco/")
                || className.startsWith("org/objectweb/")
                // shaded 进来的 ASM 已被 pom.xml 重定位成 com.xiaoxiao.jacoco.shaded.asm.*，
                // 必须一并排除，否则 agent 会去插桩自己的 ASM（递归加载）
                || className.startsWith("com/xiaoxiao/jacoco/shaded/");
    }

    private void dumpOriginal(String className, byte[] buf) {
        File out = new File(options.classDumpDir, className + ".class");
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(buf);
        } catch (IOException e) {
            System.err.println("[xiaoxiao-jacoco] classdump failed: " + className + " -> " + e);
        }
    }
}
