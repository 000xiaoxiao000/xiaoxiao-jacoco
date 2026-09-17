package peruser;

import org.jacoco.core.runtime.AgentOptions;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Java Agent 入口（xiaoxiao-jacoco-agent）。任意目标系统加
 * {@code -javaagent:/abs/xiaoxiao-jacoco-agent.jar=参数} 即可获得覆盖率采集能力。
 *
 * 参数与官方 JaCoCo agent 完全一致（destfile / includes / excludes / output / jmx ...），
 * 并扩展了按 key（用户 / 用例 / 请求头）分离：outdir= / autokey= / headerkey= / cleanup=。
 *
 * 目标系统可把「一个工作单元」用 CoverageTracer 包起来做零侵入的按 key 归属：
 *   try (CoverageTracer t = CoverageTracer.start("user-A")) { ... }
 */
public final class PerUserAgent {

    private PerUserAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        attach(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        attach(args, inst);
    }

    private static void attach(String args, Instrumentation inst) {
        // 必须在加载任何 peruser.* 之前完成：注入后 peruserrt.* 由 bootstrap 加载，
        // peruser.*（系统类路径）向上委派即可命中同一份，不会状态分裂。
        BootClassInjector.inject(inst);

        final Options o = Options.parse(args);

        // 注意：【不】把 agent jar 加进 bootstrap 搜索。
        // -javaagent 机制已把 agent jar 加到【系统类路径】，被插桩的应用类（由系统 / 应用的类加载器加载，
        //   且都向上委派到系统类加载器）解析 peruser.* 时用的是和系统类路径上的同一份 peruser.ThreadProbeStore。
        // 一旦 appendToBootstrapClassLoaderSearch，bootstrap 里会再存在一份 ThreadProbeStore：应用类向上委派
        // 命中 bootstrap 副本，而 PerUserAgent / CoverageStore 用的是系统类路径副本 —— 两份静态字段互不连通，
        // 探针写进一份、读出另一份，覆盖率全空。故保持「仅系统类路径」。
        ThreadProbeStore.configure(o.merge, o.mergeKey);
        RequestKeyHook.setDebug(o.debug);
        ClassCache.configure(o.classCacheMaxBytes);
        MqKeyHook.configure(o.mqHeader, o.debug);
        inst.addTransformer(new PerUserTransformer(o), true);

        if (o.asyncPropagate) {
            enableAsyncPropagation(inst);
        }
        if (o.streamPropagate) {
            enableStreamPropagation(inst);
        }

        System.out.println("[xiaoxiao-jacoco] agent attached: " + o
                + (o.merge ? "  autokey(" + o.mergeKey + ")" : "  perKey(CoverageTracer/autokey/headerkey)")
                + (o.headerKey != null ? "  headerkey=" + o.headerKey + "(per-request, concurrent-safe)" : "")
                + (o.asyncPropagate
                    ? (BootClassInjector.available()
                        ? "  async=on(线程池/@Async/CompletableFuture 已传递 key)"
                        : "  async=on(但 bootstrap 注入失败，仅 new Thread 生效)")
                    : "  async=off")
                + (o.streamPropagate ? "  streamkey=on(parallelStream 已传递 key)" : "")
                + (o.mqKey ? "  mqkey=on(" + o.mqHeader + " 已注入 Kafka/RocketMQ/RabbitMQ 收发两端)" : ""));
        System.out.println("[xiaoxiao-jacoco] 过滤条件: includes=" + o.agentOptions().getIncludes()
                + "  excludes=" + o.agentOptions().getExcludes()
                + "  exclclassloader=" + o.agentOptions().getExclClassloader()
                + "\n     inclnolocationclasses=" + o.inclNoLocationClasses()
                + "  inclbootstrapclasses=" + o.agentOptions().getInclBootstrapClasses()
                + "（inclnolocationclasses 默认 false：没有 source location 的类会被跳过，"
                + "Spring Boot 可执行 jar 通常需要显式设为 true）");
        System.out.println("[xiaoxiao-jacoco] class cache: classcache=" + o.classCache()
                + (o.classCache()
                    ? "（被插桩类的原始字节留在内存，可用 cli dumpclasses 拉回，免 scp/镜像）"
                    : "（关闭：dumpclasses 不可用，请用 classdumpdir 或构建产物 jar 当 classfiles）"));
        warnIncludes(o.agentOptions().getIncludes());
        startSelfCheck(o);

        final IAgentOutput output = Outputs.create(o);
        try {
            output.startup();
        } catch (Exception e) {
            System.err.println("[xiaoxiao-jacoco] output startup failed: " + e);
        }

        if (o.jmx()) {
            JmxSupport.register(o, output);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (o.dumpOnExit()) {
                    // 按 key 分离的 exec 是 xiaoxiao-jacoco 的扩展能力，与官方 output 通道正交：
                    // output=file 时由 FileOutput 自己写；tcpserver / tcpclient 时也照样落到 outdir，
                    // 免得配了 outdir 却只在 TCP 通道上拿到「所有 key 的并集」。output=none 时不写任何文件。
                    dumpPerKeyFiles(o, false);
                    output.writeExecutionData(false);
                }
                output.shutdown();
            } catch (Exception e) {
                System.err.println("[xiaoxiao-jacoco] dump failed: " + e);
            }
        }));

        if (o.cleanupExpireMs > 0) {
            startCleanupScheduler(o);
        }
    }

    /**
     * 启动后的自检：覆盖率全空是最常见也最难自查的故障（分不清是「没插桩」还是「没 key」），
     * 这里在 60s / 3min 两个时间点各看一次，确实没采到就把最可能的原因和建议的 includes 打进应用日志。
     */
    /**
     * includes 写错是「覆盖率全空」的头号原因。JaCoCo 的 WildcardMatcher 用的是
     * {@code Pattern.matcher(s).matches()}——<b>全匹配</b>，不是前缀匹配：
     * 写 {@code includes=webServer} 只会匹配「VM 类名【恰好】等于 webServer」的类，
     * 而真实类名是 {@code com/xxx/webServer/...}，永远不可能相等，结果就是一个类都没插桩。
     * 这里在启动时就把这类写法直接点名。
     */
    private static void warnIncludes(final String includes) {
        if (includes == null || includes.isEmpty()) {
            return;
        }
        for (String part : includes.split(":", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            final boolean wildcard = part.indexOf('*') >= 0 || part.indexOf('?') >= 0;
            final boolean qualified = part.indexOf('.') >= 0 || part.indexOf('/') >= 0;
            if ("*".equals(part)) {
                System.err.println("[xiaoxiao-jacoco] warning: includes=* 会连 Spring/Tomcat/MyBatis 等三方库一起插桩，"
                        + "启动和每次请求都会明显变慢、内存占用也会变大；"
                        + "链路跑通后请收窄到业务包名（如 includes=com.xxx.*）");
            } else if (!wildcard && !qualified) {
                System.err.println("[xiaoxiao-jacoco] warning: includes=" + part
                        + " 只能匹配【VM 类名完全等于 " + part + "】的类（JaCoCo 是全匹配，不是前缀匹配）");
                System.err.println("  -> 真实类名形如 com/公司/模块/xxx/Controller，永远不可能等于 '" + part
                        + "'，结果就是 0 个类被插桩、覆盖率全空");
                System.err.println("  -> includes 匹配的是【VM 类名】(com/foo/Bar)，不是 URL 路径"
                        + "（/web3/testWeb）、不是模块名、不是包名简写");
                System.err.println("  -> 改成 includes=*" + part + "*   （类名任意位置包含 " + part + "，最稳）");
                System.err.println("  -> 或 includes=" + part + "*    （类名以 " + part + " 开头，仅当它出现在包名开头时有效）");
                System.err.println("  -> 拿不准包名就先 includes=* 跑通，再看 cli keys 输出里给的包名样例");
            } else if (!wildcard) {
                System.err.println("[xiaoxiao-jacoco] warning: includes=" + part
                        + " 不含通配符，只会匹配【一个类】：" + part.replace('.', '/'));
                System.err.println("  -> 想匹配整个包请写成 includes=" + part + ".* 或 includes=" + part + "/*");
            }
        }
    }

    private static void startSelfCheck(final Options o) {
        final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xiaoxiao-jacoco-selfcheck");
            t.setDaemon(true);
            return t;
        });
        sched.schedule(() -> Diagnostics.checkAndHint(o.headerKey), 30, TimeUnit.SECONDS);
        sched.schedule(() -> {
            Diagnostics.checkAndHint(o.headerKey);
            sched.shutdownNow();
        }, 3, TimeUnit.MINUTES);
    }

    /**
     * 让 JDK 线程池织入生效：这些类可能已经在 premain 之前被 JVM 加载过，需要显式 retransform；
     * 尚未加载的会在首次加载时由 PerUserTransformer 直接织入。
     */
    private static void enableAsyncPropagation(Instrumentation inst) {
        if (!BootClassInjector.available()) {
            return;
        }
        for (String cn : AsyncKeyWeaver.targetClassNames()) {
            try {
                inst.retransformClasses(Class.forName(cn, false, null));
            } catch (Throwable t) {
                // 类未加载 / 不允许重定义都不是致命问题：未加载的会在加载时织入
                System.err.println("[xiaoxiao-jacoco] async retransform skipped for " + cn + " -> " + t);
            }
        }
    }

    /**
     * 让 parallelStream 的织入生效：ForkJoinTask 通常在 premain 之前就已被 JVM 加载
     * （commonPool 初始化），必须显式 retransform；否则要等到下次类加载才生效。
     */
    private static void enableStreamPropagation(Instrumentation inst) {
        if (!BootClassInjector.available()) {
            return;
        }
        for (String cn : StreamKeyWeaver.targetClassNames()) {
            try {
                inst.retransformClasses(Class.forName(cn, false, null));
            } catch (Throwable t) {
                System.err.println("[xiaoxiao-jacoco] stream retransform skipped for " + cn + " -> " + t);
            }
        }
    }

    /**
     * 把【按 key 分离】的 exec 落到 outdir。这是 xiaoxiao-jacoco 的扩展能力，与官方 output 通道正交：
     * output=file 时由 FileOutput 自己写（这里跳过避免重复）；output=none 时完全不写；
     * tcpserver / tcpclient 时也照样落盘，免得配了 outdir 却只能在 TCP 通道上拿到「所有 key 的并集」。
     */
    static void dumpPerKeyFiles(Options o, boolean reset) {
        final AgentOptions.OutputMode mode = o.output();
        if (mode == AgentOptions.OutputMode.file || mode == AgentOptions.OutputMode.none) {
            return;
        }
        try {
            for (File f : CoverageStore.dumpAll(o, reset)) {
                System.out.println("[xiaoxiao-jacoco] wrote " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[xiaoxiao-jacoco] per-key dump failed: " + e);
        }
    }

    /** 过期定期清理：后台定时线程删除 outdir / classdumpdir 下过期（mtime 早于阈值）的 exec 与 class 文件。 */
    private static void startCleanupScheduler(final Options o) {
        final long expire = o.cleanupExpireMs;
        final long period = Math.max(expire / 2, 30_000L); // 扫描很轻量；周期下限 30s 便于验证
        final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xiaoxiao-jacoco-cleanup");
            t.setDaemon(true);
            return t;
        });
        sched.scheduleAtFixedRate(() -> {
            try {
                int n = 0;
                n += CoverageStore.cleanupExpired(new File(o.outDir), expire);
                if (o.classDumpDir != null) n += CoverageStore.cleanupExpired(new File(o.classDumpDir), expire);
                if (n > 0) {
                    System.out.println("[xiaoxiao-jacoco] cleanup: removed " + n
                            + " expired item(s) older than " + (expire / 3_600_000) + "h under outdir/classdumpdir");
                }
            } catch (Exception e) {
                System.err.println("[xiaoxiao-jacoco] cleanup failed: " + e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }
}
