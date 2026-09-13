package peruser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 代理参数解析。agent 参数形如逗号分隔的 key=value：
 *   includes=com.foo:com.bar    仅插桩这些包前缀（':'分隔）；留空=插桩所有非排除类
 *   excludes=com.foo.internal   额外排除（':'分隔）；默认已排除 peruser / org.jacoco / org.objectweb.asm / JDK
 *   outdir=coverage             JVM 关闭时写出 coverage-&lt;key&gt;.exec 的目录
 *   autokey=KEY                 冒烟测试模式：把所有线程的探针合并进 KEY（类似官方 JaCoCo），
 *                              零代码改动即可看到覆盖率；不指定 KEY 时默认 "default"。
 *                              （与 CoverageTracer 按用例分离互斥：autokey 下 CoverageTracer 不生效）
 *   classdumpdir=DIR            插桩时把【原始未插桩】的 class 字节落盘到 DIR，
 *                              可直接当 jacococli report --classfiles 用（无需目标工程/target/classes）
 *   control=ADDR:PORT           启用内嵌 HTTP 控制端点（不修改目标系统即可驱动分离 + 实时 dump）：
 *                                GET /key?name=X            设定全局当前归属 key（外部驱动 A/B 分离）
 *                                GET /dump[?key=X][&reset=true]  写出 .exec（合并写，可单 key；reset 清内存累计）
 *                                GET /reset[?key=X]         清空累计探针（可单 key）
 *                                GET /keys                  列出当前所有 key
 *                                GET /health                健康检查
 *   headerkey=NAME              启用【按请求头归属】（框架无关、零改目标系统、支持并发按用户/用例分离）：
 *                               agent 会字节码织入 javax/jakarta.servlet.http.HttpServlet 的 service 入口（所有
 *                               Servlet 容器都会调用它，Spring 也走它），读请求头 NAME（如 X-Coverage-Key），
 *                               非空则把【这一次请求】归属到该 key（线程级，A/B 并发互不串）。请求不带该头时回退全局 CURRENT_KEY。
 *                               例：headerkey=X-Coverage-Key  →  A 带 X-Coverage-Key:1、B 带 :2，并发请求各自归到 1/2。
 */
public final class Options {

    final List<String> includes = new ArrayList<>();
    final Set<String> excludes = new HashSet<>(Arrays.asList(
            "peruser",
            "org/jacoco",
            "org/objectweb/asm",
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun"));
    String outDir = "coverage";
    boolean merge = false;
    String mergeKey = "default";
    String classDumpDir = null;
    String controlAddr = null;   // 非空时启用控制端点
    int controlPort = -1;
    String headerKey = null;     // 非空时启用按请求头归属（零改目标系统、并发安全）

    private Options() {
    }

    static Options parse(String args) {
        Options o = new Options();
        if (args != null) {
            for (String kv : args.split(",")) {
                int i = kv.indexOf('=');
                String k = (i < 0 ? kv : kv.substring(0, i)).trim();
                String v = (i < 0 ? "" : kv.substring(i + 1)).trim();
                if (k.equals("includes")) {
                    for (String p : v.split(":")) {
                        if (!p.isEmpty()) o.includes.add(p.replace('.', '/'));
                    }
                } else if (k.equals("excludes")) {
                    for (String p : v.split(":")) {
                        if (!p.isEmpty()) o.excludes.add(p.replace('.', '/'));
                    }
                } else if (k.equals("outdir")) {
                    if (!v.isEmpty()) o.outDir = v;
                } else if (k.equals("autokey")) {
                    o.merge = true;
                    if (!v.isEmpty()) o.mergeKey = v;
                } else if (k.equals("classdumpdir")) {
                    if (!v.isEmpty()) o.classDumpDir = v;
                } else if (k.equals("headerkey")) {
                    if (!v.isEmpty()) o.headerKey = v;
                } else if (k.equals("control")) {
                    if (!v.isEmpty()) {
                        int c = v.lastIndexOf(':');
                        if (c >= 0) {
                            String host = v.substring(0, c).trim();
                            String port = v.substring(c + 1).trim();
                            if (!host.isEmpty()) o.controlAddr = host;
                            try {
                                o.controlPort = Integer.parseInt(port);
                            } catch (NumberFormatException ignore) {
                                o.controlPort = -1;
                            }
                        } else {
                            o.controlAddr = "127.0.0.1";
                            try {
                                o.controlPort = Integer.parseInt(v.trim());
                            } catch (NumberFormatException ignore) {
                                o.controlPort = -1;
                            }
                        }
                    }
                }
            }
        }
        return o;
    }

    boolean shouldInstrument(String className) {
        if (className == null) return false;
        for (String ex : excludes) {
            if (className.startsWith(ex)) return false;
        }
        if (includes.isEmpty()) return true;
        for (String in : includes) {
            if (className.startsWith(in)) return true;
        }
        return false;
    }
}
