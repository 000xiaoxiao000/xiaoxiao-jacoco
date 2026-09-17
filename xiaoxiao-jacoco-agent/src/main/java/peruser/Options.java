package peruser;

import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.WildcardMatcher;

import java.io.File;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent 参数解析。
 *
 * 【官方 JaCoCo agent 的全部参数】由 org.jacoco.core.runtime.AgentOptions 解析，语义与官方完全一致：
 *   destfile=<path>            exec 输出文件，默认 jacoco.exec
 *   append=true|false          是否向已存在的 exec 追加（true 时按 classId OR 合并，默认 true）
 *   includes=<pattern>         仅插桩匹配的类，支持 * ? 通配符，':' 分隔多项，默认 *
 *   excludes=<pattern>         不插桩匹配的类，':' 分隔多项，默认空
 *   exclclassloader=<pattern>  跳过匹配的类加载器，':' 分隔多项
 *   inclbootstrapclasses=true|false  是否插桩 bootstrap 类加载器加载的类（默认 false）
 *   inclnolocationclasses=true|false 是否插桩没有 source location 的类（默认 false）
 *   sessionid=<id>             session 标识，默认自动生成
 *   dumponexit=true|false      JVM 关闭时是否 dump（默认 true）
 *   output=file|tcpserver|tcpclient|none  输出方式，默认 file
 *   address=<host/ip>          tcpserver 监听地址 / tcpclient 目标地址
 *   port=<port>                tcpserver 监听端口 / tcpclient 目标端口（默认 6300）
 *   classdumpdir=<path>        插桩前把原始（未插桩）class 字节落盘到该目录
 *   jmx=true|false             是否注册 JMX MBean 以支持运行时 dump/reset（默认 false）
 * 参数值可用双引号包裹（内含 ',' 或 '=' 也安全，与官方一致）。
 *
 * 【xiaoxiao-jacoco（原 peruser）扩展参数】——在官方语义之上增加「按 key 分离」：
 *   outdir=DIR                 按 key 输出 exec 的目录，默认 coverage；文件名为 &lt;prefix&gt;-&lt;key&gt;.exec
 *   autokey=KEY                冒烟模式：所有线程合并进单一 KEY（零代码改动），默认 KEY=default
 *   headerkey=NAME             按 HTTP 请求头 NAME 归属（如 X-Coverage-Key），框架无关、并发安全
 *   cleanup=24h                过期定期清理 outdir 下的旧 exec/class 文件（30m/2h/1d，纯数字按小时）
 *   streamkey=true|false       parallelStream 的 key 传递（织入 ForkJoinTask），默认 false
 *   mqkey=true|false           跨进程 MQ 的 key 透传（Kafka/RocketMQ/RabbitMQ），默认 false
 *   mqheader=NAME              MQ 透传用的 header / property 名，默认 X-Coverage-Key
 *   classcachemax=MB           classcache 的字节上限（MB），默认 64；超过即停止缓存
 *
 * 【设计底线：探针适应目标系统，探针不影响目标系统】
 *   1) 任何会让业务改代码的场景，都必须由探针织入解决（CoverageTracer 只是最后兜底，不是前提）；
 *   2) 任何有运行时成本的能力（热路径织入、内存缓存、给消息加 header）默认关闭或可关，
 *      且在没有 key 的线程上开销为零；
 *   3) 探针自带的三方依赖（ASM / JaCoCo）全部重定位到私有包，绝不污染目标系统的 classpath。
 *
 * exec 文件名规则：
 *   - 未指定 destfile：&lt;outdir&gt;/&lt;前缀&gt;-&lt;key&gt;.exec（默认即 coverage/coverage-&lt;key&gt;.exec）
 *   - 指定 destfile：&lt;destfile 所在目录&gt;/&lt;destfile 文件名前缀&gt;-&lt;key&gt;.exec；
 *     autokey 单 key 模式下直接写 destfile 本身（与官方完全一致）
 */
public final class Options {

    /** 官方参数（解析、默认值、引号处理均与 JaCoCo agent 一致）。 */
    private final AgentOptions ao;
    private final boolean destfileSet;

    // ===== 过滤匹配器（官方用 WildcardMatcher，对 VM 类名 com/foo/Bar 匹配）=====
    private final List<WildcardMatcher> includeMatchers;
    private final List<WildcardMatcher> excludeMatchers;
    private final List<WildcardMatcher> exclClassLoaderMatchers;

    // ===== xiaoxiao(peruser) 扩展 =====
    final String outDir;               // 按 key 输出 exec 的目录
    final String execPrefix;           // exec 文件名前缀
    final String classDumpDir;         // classdumpdir（null 表示不落盘原始 class）
    final String headerKey;            // headerkey（null 表示不启用）
    final boolean merge;               // autokey 模式
    final String mergeKey;             // autokey 的目标 key
    final long cleanupExpireMs;        // 过期清理阈值毫秒（0=不启用）
    final boolean asyncPropagate;      // 异步（线程池/@Async/CompletableFuture）key 传递，默认开
    final boolean streamPropagate;     // parallelStream（ForkJoinTask）key 传递，默认关（热路径）
    final boolean mqKey;               // 跨进程 MQ 的 key 透传，默认关（会给消息加 header）
    final String mqHeader;             // MQ 透传用的 header / property 名，默认 X-Coverage-Key
    final boolean debug;               // 打印插桩/归属明细，排障用
    final boolean classCache;          // 是否内存缓存被插桩类的原始字节（dumpclasses 用），默认开
    final long classCacheMaxBytes;     // classcache 的字节上限（默认 64MB）

    private Options(AgentOptions ao, boolean destfileSet, Map<String, String> kv) {
        this.ao = ao;
        this.destfileSet = destfileSet;

        this.includeMatchers = toMatchers(ao.getIncludes());
        this.excludeMatchers = joinExcludes(ao.getExcludes());
        this.exclClassLoaderMatchers = toMatchers(emptyToNull(ao.getExclClassloader()));

        String explicitOutDir = kv.get("outdir");
        if (explicitOutDir != null && !explicitOutDir.isEmpty()) {
            this.outDir = explicitOutDir;
        } else if (destfileSet) {
            File parent = new File(ao.getDestfile()).getAbsoluteFile().getParentFile();
            this.outDir = (parent == null) ? "." : parent.getPath();
        } else {
            this.outDir = "coverage";
        }

        if (destfileSet) {
            String fn = new File(ao.getDestfile()).getName();
            this.execPrefix = fn.endsWith(".exec") ? fn.substring(0, fn.length() - 5) : fn;
        } else if (explicitOutDir != null && !explicitOutDir.isEmpty()) {
            String fn = new File(explicitOutDir).getName();
            this.execPrefix = fn.isEmpty() ? "coverage" : fn;
        } else {
            this.execPrefix = "coverage";
        }

        this.classDumpDir = emptyToNull(ao.getClassDumpDir());
        this.headerKey = emptyToNull(kv.get("headerkey"));

        String autokey = kv.get("autokey");
        this.merge = (autokey != null);
        this.mergeKey = (autokey != null && !autokey.isEmpty()) ? autokey : "default";

        long cleanup = 0;
        String c = kv.get("cleanup");
        if (c != null && !c.isEmpty()) {
            long ms = parseDuration(c);
            if (ms > 0) cleanup = ms;
        }
        this.cleanupExpireMs = cleanup;

        this.asyncPropagate = !"false".equalsIgnoreCase(String.valueOf(kv.get("async")));
        this.streamPropagate = "true".equalsIgnoreCase(String.valueOf(kv.get("streamkey")));
        this.mqKey = "true".equalsIgnoreCase(String.valueOf(kv.get("mqkey")));
        String mh = emptyToNull(kv.get("mqheader"));
        this.mqHeader = (mh != null) ? mh : MqKeyHook.DEFAULT_HEADER;
        this.debug = "true".equalsIgnoreCase(String.valueOf(kv.get("debug")));
        this.classCache = !"false".equalsIgnoreCase(String.valueOf(kv.get("classcache")));
        this.classCacheMaxBytes = parseMegaBytes(kv.get("classcachemax"), 64);
    }

    /** 解析 MB 值；非法或未指定时用默认值。 */
    private static long parseMegaBytes(String v, long defaultMb) {
        if (v == null || v.trim().isEmpty()) {
            return defaultMb * 1024L * 1024L;
        }
        try {
            long mb = Long.parseLong(v.trim());
            return (mb <= 0) ? defaultMb * 1024L * 1024L : mb * 1024L * 1024L;
        } catch (NumberFormatException e) {
            return defaultMb * 1024L * 1024L;
        }
    }

    /** 由原生 driver 之外的 key（xiaoxiao/peruser 扩展参数） */
    private static final java.util.Set<String> EXTENSION_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "outdir", "autokey", "headerkey", "cleanup", "async", "streamkey", "mqkey", "mqheader",
            "debug", "classcache", "classcachemax"));

    /** 官方 JaCoCo agent 支持的全部参数（其余未知参数只告警、不报错，避免拖累启动脚本） */
    private static final java.util.Set<String> OFFICIAL_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            AgentOptions.DESTFILE, AgentOptions.APPEND, AgentOptions.INCLUDES, AgentOptions.EXCLUDES,
            AgentOptions.EXCLCLASSLOADER, "exclclassloaders",
            AgentOptions.INCLBOOTSTRAPCLASSES, AgentOptions.INCLNOLOCATIONCLASSES,
            AgentOptions.SESSIONID, AgentOptions.DUMPONEXIT, AgentOptions.OUTPUT,
            AgentOptions.ADDRESS, AgentOptions.PORT, AgentOptions.CLASSDUMPDIR, AgentOptions.JMX));

    /** output= 的合法取值（与官方 AgentOptions.OutputMode 一致），用于给出可读的报错。 */
    private static final String VALID_OUTPUTS = "file, tcpserver, tcpclient, none";

    public static Options parse(String args) {
        final Map<String, String> kv = AgentArgParser.parse(args);
        preValidate(kv);

        // 只把【官方参数】交给 AgentOptions（它会对未知 key 抛异常），扩展参数由此处单独解析。
        // 未知参数只告警不报错，保证旧启动脚本（含已废弃参数）不会让目标 JVM 启动失败。
        final StringBuilder official = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String k = e.getKey();
            if (EXTENSION_KEYS.contains(k)) continue;
            if (OFFICIAL_KEYS.contains(k)) {
                if (official.length() > 0) official.append(',');
                official.append(k).append('=').append(quote(e.getValue()));
            } else {
                System.err.println("[xiaoxiao-jacoco] warning: ignoring unknown/unsupported agent option: " + k);
            }
        }
        AgentOptions ao = new AgentOptions(official.toString());
        // 官方别名：exclclassloaders == exclclassloader（部分 JaCoCo 文档/版本用复数形式）
        if (!kv.containsKey(AgentOptions.EXCLCLASSLOADER) && kv.containsKey("exclclassloaders")) {
            ao.setExclClassloader(kv.get("exclclassloaders"));
        }
        return new Options(ao, kv.containsKey(AgentOptions.DESTFILE), kv);
    }

    /**
     * 官方 AgentOptions 的报错是 JVM 级的 FATAL（如 "No enum constant ...OutputMode.xxx"），
     * 在启动日志里非常难定位。这里先做一遍可读的预校验，把常见误配直接说清楚。
     */
    private static void preValidate(Map<String, String> kv) {
        // ---- output=：必须是官方四种模式之一 ----
        String output = kv.get(AgentOptions.OUTPUT);
        if (output != null) {
            String v = output.trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.equals("file") && !v.equals("tcpserver") && !v.equals("tcpclient") && !v.equals("none")) {
                throw new IllegalArgumentException(
                        "invalid agent option output=\"" + output + "\". "
                        + "output 只能是 [" + VALID_OUTPUTS + "] 之一，它表示【输出通道】，不是 IP 地址。\n"
                        + "  - output=file      （默认）JVM 退出时写 <outdir>/<prefix>-<key>.exec\n"
                        + "  - output=tcpserver  agent 在 address:port 上【监听】，等你用 cli dump 去抓（address 填本机 IP）\n"
                        + "  - output=tcpclient  agent 启动时主动【连出去】到 address:port（address 填采集端 IP）\n"
                        + "  - output=none       不主动输出，只能靠 JMX 触发\n"
                        + "IP / 端口请分别用 address= 与 port= 指定。");
            }
        }

        // ---- address=：常见误配是写成 address=1.2.3.4:6300 ----
        String address = kv.get(AgentOptions.ADDRESS);
        if (address != null && address.indexOf(':') >= 0) {
            String host = address.substring(0, address.indexOf(':'));
            String portPart = address.substring(address.indexOf(':') + 1);
            System.err.println("[xiaoxiao-jacoco] warning: address=\"" + address
                    + "\" 含端口，端口请用单独的 port= 指定；已按 address=" + host + " port=" + portPart + " 处理");
            kv.put(AgentOptions.ADDRESS, host);
            if (!kv.containsKey(AgentOptions.PORT)) {
                kv.put(AgentOptions.PORT, portPart);
            }
        }

        // ---- port=：必须是整数 ----
        String port = kv.get(AgentOptions.PORT);
        if (port != null) {
            try {
                Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "invalid agent option port=\"" + port + "\": port 必须是整数（如 port=6300），不要写成 host:port。");
            }
        }

        // ---- 布尔参数：必须是 true/false ----
        for (String boolKey : new String[]{AgentOptions.APPEND, AgentOptions.DUMPONEXIT,
                AgentOptions.INCLBOOTSTRAPCLASSES, AgentOptions.INCLNOLOCATIONCLASSES, AgentOptions.JMX,
                "async", "streamkey", "mqkey", "classcache"}) {
            String v = kv.get(boolKey);
            if (v != null && !"true".equalsIgnoreCase(v.trim()) && !"false".equalsIgnoreCase(v.trim())) {
                throw new IllegalArgumentException(
                        "invalid agent option " + boolKey + "=\"" + v + "\": 只能是 true 或 false。");
            }
        }
    }

    /** 值里含 , = 空格 时用双引号包裹（与 JaCoCo 的解析规则一致）。 */
    private static String quote(String v) {
        if (v == null) return "";
        boolean need = v.indexOf(',') >= 0 || v.indexOf('=') >= 0
                || v.indexOf(' ') >= 0 || v.indexOf('"') >= 0;
        return need ? '"' + v.replace("\"", "\\\"") + '"' : v;
    }

    // ===== 官方参数透出 =====

    public AgentOptions agentOptions() {
        return ao;
    }

    public AgentOptions.OutputMode output() {
        return ao.getOutput();
    }

    public String address() {
        return ao.getAddress();
    }

    public int port() {
        return ao.getPort();
    }

    /**
     * session 标识。官方未配置 sessionid 时 AgentOptions 返回空串，
     * 而 SessionInfo 不接受空 id（会抛 IllegalArgumentException），这里兜底为默认名。
     */
    public String sessionId() {
        String id = ao.getSessionId();
        return (id == null || id.isEmpty()) ? CoverageStore.DEFAULT_SESSION_ID : id;
    }

    public boolean append() {
        return ao.getAppend();
    }

    public boolean dumpOnExit() {
        return ao.getDumpOnExit();
    }

    public boolean jmx() {
        return ao.getJmx();
    }

    public boolean inclBootstrapClasses() {
        return ao.getInclBootstrapClasses();
    }

    public boolean inclNoLocationClasses() {
        return ao.getInclNoLocationClasses();
    }

    /** 是否缓存被插桩类的原始字节（dumpclasses 命令依赖），默认 true；false 则纯靠 classdumpdir / 构建产物。 */
    public boolean classCache() {
        return classCache;
    }

    @Override
    public String toString() {
        return ao.toString();
    }

    // ===== 按 key 的 exec 文件名 =====

    /**
     * 某 key 对应的 exec 文件：
     * autokey 单 key 模式且显式指定了 destfile 时，直接返回 destfile（与官方行为一致）；
     * 否则返回 &lt;outDir&gt;/&lt;execPrefix&gt;-&lt;key&gt;.exec。
     */
    public File execFile(String key) {
        if (merge && destfileSet) {
            return new File(ao.getDestfile());
        }
        return new File(outDir, execPrefix + "-" + ThreadProbeStore.sanitize(key) + ".exec");
    }

    // ===== 是否插桩 =====

    /**
     * 判断是否插桩，按官方语义叠加：
     * 1) loader==null（bootstrap）且未开 inclbootstrapclasses → 跳过；
     * 2) 该类无 source location 且未开 inclnolocationclasses → 跳过；
     * 3) 类加载器命中 exclclassloader → 跳过；
     * 4) include/exclude 通配符不匹配 → 跳过。
     *
     * @param loader    加载该类的 ClassLoader（null 表示 bootstrap）
     * @param className VM 类名（com/foo/Bar）
     * @param domain    ProtectionDomain（用于判断是否有 source location）
     */
    public boolean shouldInstrument(ClassLoader loader, String className, ProtectionDomain domain) {
        if (className == null) return false;
        if (className.startsWith("peruser/")) return false;
        if (loader == null && !inclBootstrapClasses()) return false;
        if (!inclNoLocationClasses() && hasNoSourceLocation(domain)) return false;
        if (matches(exclClassLoaderMatchers, loaderName(loader))) return false;
        if (matches(excludeMatchers, className)) return false;
        if (includeMatchers.isEmpty()) return true;
        return matches(includeMatchers, className);
    }

    private static boolean hasNoSourceLocation(ProtectionDomain domain) {
        return domain == null
                || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation() == null;
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "<bootstrap>";
        // 注意：ClassLoader.getName() 是 Java 9+ API，这里目标字节码是 Java 8，故只用类名
        return loader.getClass().getName();
    }

    // ===== 工具 =====

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static boolean matches(List<WildcardMatcher> ms, String name) {
        for (WildcardMatcher m : ms) {
            if (m.matches(name)) return true;
        }
        return false;
    }

    /** 官方 include/exclude 用 ':' 分隔；每项里的 '.' 视为 '/'（兼容 com.foo.Bar 写法）。 */
    private static List<WildcardMatcher> toMatchers(String patterns) {
        List<WildcardMatcher> list = new ArrayList<>();
        if (patterns == null || patterns.isEmpty()) return list;
        for (String p : patterns.split(":")) {
            if (p.isEmpty()) continue;
            String pattern = p.replace('.', '/');
            // 不含通配符时按「前缀」匹配，兼容旧习惯 includes=webServer 而不必写 web3Server*
            if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
                pattern = pattern + "*";
            }
            list.add(new WildcardMatcher(pattern));
        }
        return list;
    }

    /** exclude 额外加上 agent 自身 / JaCoCo / ASM / JDK 的默认排除项（不允许被用户覆盖掉）。 */
    private List<WildcardMatcher> joinExcludes(String userExcludes) {
        List<WildcardMatcher> list = new ArrayList<>();
        for (String p : Arrays.asList("peruser/*", "org/jacoco/*", "org/objectweb/asm/*",
                // shaded 进来的 ASM（pom.xml 重定位后的私有包），同样禁止被插桩
                "com/xiaoxiao/jacoco/shaded/*",
                "java/*", "javax/*", "jdk/*", "sun/*", "com/sun/*")) {
            list.add(new WildcardMatcher(p));
        }
        list.addAll(toMatchers(emptyToNull(userExcludes)));
        return list;
    }

    /** 解析过期时长（cleanup=）：支持 30m / 2h / 1d，纯数字按小时；非法返回 -1。 */
    static long parseDuration(String v) {
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        char last = s.charAt(s.length() - 1);
        if (Character.isDigit(last)) {
            try {
                return Long.parseLong(s) * 3600_000L;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        long num;
        try {
            num = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        switch (last) {
            case 'm':
                return num * 60_000L;
            case 'h':
                return num * 3600_000L;
            case 'd':
                return num * 86_400_000L;
            default:
                return -1;
        }
    }
}
