package peruser;

import java.io.File;
import java.lang.instrument.Instrumentation;

/**
 * Java Agent 入口。任意目标系统加 -javaagent:peruser-jacoco.jar 即可获得按 key 分离的精准覆盖率。
 *
 * 目标系统只需把「一个工作单元」用 CoverageTracer 包起来：
 *   try (CoverageTracer t = CoverageTracer.start("user-A")) { ... }
 *
 * JVM 关闭时，本 agent 把每个 key 的覆盖率写成 coverage-&lt;key&gt;.exec；之后用 ReportCli 生成 HTML。
 */
public final class PerUserAgent {

    public static void premain(String args, Instrumentation inst) {
        attach(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        attach(args, inst);
    }

    private static void attach(String args, Instrumentation inst) {
        Options o = Options.parse(args);
        // 注意：【不】把 agent jar 加进 bootstrap 搜索。
        // -javaagent 机制已经把 agent jar 加到了【系统类路径】，被插桩的应用类（由系统 / 应用的类加载器加载，
        //   且都向上委派到系统类加载器）解析 peruser.* 时用的是和系统类路径上的同一份 peruser.ThreadProbeStore。
        // 一旦 appendToBootstrapClassLoaderSearch，bootstrap 里会再存在一份 peruser.ThreadProbeStore：应用类向上委派
        // 命中 bootstrap 副本，而 ControlServer / CoverageStore / PerUserAgent 用的是系统类路径副本 —— 两份静态字段
        // 互不连通，导致 /key 设的 key、dump 读到的 store 不是同一份（探针写进一份、读出另一份，覆盖率全空）。
        // 我们只插桩应用类（java/ javax/ jdk/ sun/ com/sun 已在 excludes 里排除），不会碰到需要 bootstrap 可见的场景，
        // 因此保持「仅系统类路径」即可保证 agent 与被插桩类共享同一个 ThreadProbeStore 实例。
        ThreadProbeStore.configure(o.merge, o.mergeKey);
        inst.addTransformer(new PerUserTransformer(o), true);
        System.out.println("[peruser] agent attached; includes=" + o.includes
                + " excludes(default)=" + o.excludes + " outDir=" + o.outDir
                + (o.merge ? " mergeMode(autokey=" + o.mergeKey + ")" : " perKeyMode(CoverageTracer/external-key)")
                + (o.classDumpDir != null ? " classdumpdir=" + o.classDumpDir : "")
                + (o.headerKey != null ? " headerkey=" + o.headerKey + "(per-request, concurrent-safe)" : "")
                + (o.controlPort > 0 ? " control=" + (o.controlAddr == null ? "127.0.0.1" : o.controlAddr) + ":" + o.controlPort : ""));

        // 内嵌 HTTP 控制端点（不修改目标系统即可驱动分离 + 实时 dump）
        if (o.controlPort > 0) {
            try {
                ControlServer.start(o.controlAddr, o.controlPort, o.outDir);
            } catch (Exception e) {
                System.err.println("[peruser] control server failed to start: " + e);
            }
        }

        final String outDir = o.outDir;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                int n = CoverageStore.keys().size();
                System.out.println("[peruser] shutdown: dumping " + n + " key(s) -> "
                        + new File(outDir).getAbsolutePath());
                CoverageStore.dumpAll(new File(outDir), false);
            } catch (Exception e) {
                System.err.println("[peruser] dump failed: " + e);
            }
        }));
    }
}
