# peruser-jacoco —— 单实例并发下按 key 分离的精准覆盖率（Java Agent）

对任意「被插桩的目标系统」，在**单实例 + 多用户/多用例并发**执行时，把覆盖率按 **key**（用户 / 用例 / 请求 / 任务）
干净分离，互不污染。基于 JaCoCo 0.8.15，但以 **ThreadLocal 探针** 替换其默认「全局静态字段探针」。

> 为什么 JaCoCo 默认做不到：它的 `Instrumenter` 强制把探针存进静态字段 `$jacocoData`，所有线程共享，
> 并发覆盖在全局数组合并。`sessionid` / `append` / `dump --reset` 都解决不了「单实例并发按 key 分离」。
> 本方案绕过 `Instrumenter`，复刻其内部 `ClassProbesAdapter + ClassInstrumenter(IProbeArrayStrategy)` 流程，
> 把探针数组改为 ThreadLocal，再按 key 在「工作单元结束」时合并。

## 它是什么
- `target/peruser-jacoco.jar`（Maven 构建产物，见下「构建」）：一个 **self-contained 的 javaagent**（已内含 asm 9.10.1 + jacoco core/report）。
  目标 JVM 加 `-javaagent` 即可，零业务代码侵入。
- `CoverageTracer`：公共 API，把一个「工作单元」的覆盖率归到某个 key。
- `ReportCli`：独立工具，把 `.exec` + 原始 classfiles 渲染成**每个 key 一份** HTML 报告。

## Java 8+ 兼容（重要）
agent 核心类以 `--release 8` 编译（class 文件版本 **52**），因此可挂接到 **Java 8 及以上**的目标 JVM——
这正是默认 JaCoCo 方案在 Java 8 上直接 `-javaagent` 不会出现 `UnsupportedClassVersionError` 的前提。
- ASM 9.10.1 / JaCoCo 0.8.15 的核心类本身也是 Java 5/8 字节码；jar 内仅 `module-info.class` 是 Java 9，
  构建脚本已用 `unzip -x 'module-info.class'` 排除，不会带进 fat jar。
- 若目标 JVM 是 Java 7 或更低，本 agent 不适用（JaCoCo 0.8.15 内部已要求 Java 8+ 的 API）。
- 验证方式：构建用 `mvn clean package`（`maven-compiler-plugin` 已设 `release 8`）；本地用 JDK 1.8 实跑过 `-javaagent` 挂载 + 写 `.exec` + 官方 `jacococli report` 出带源码报告，
  全部通过（见下「示例」与目录 `coverage/`）。

## 三步套用到你自己的目标系统

### 1. 给目标 JVM 挂 agent
```bash
java -javaagent:/abs/path/peruser-jacoco.jar=outdir=coverage \
     -jar your-target-app.jar
# 或已有启动脚本里加 -javaagent 参数即可
```
agent 参数（逗号分隔 `k=v`）：
- `includes=com.foo:com.bar` —— 只插桩这些包前缀（`:` 分隔）；**留空 = 插桩所有非排除类**。
- `excludes=com.foo.internal` —— 额外排除（`:` 分隔）。默认已排除 `peruser` / `org.jacoco` /
  `org.objectweb.asm` / JDK，避免自插桩和递归。
- `outdir=coverage` —— JVM 关闭时写出 `coverage-<key>.exec` 的目录。
- `headerkey=NAME` —— 启用【按请求头归属】（零改目标系统、支持并发按用户/用例分离）：agent 在 Spring `DispatcherServlet.doDispatch`
  入口读请求头 `NAME`（默认 `X-Coverage-Key`），非空则把**这一次请求**归属到该 key（线程级，A/B 并发互不串）；请求不带该头时回退全局 `CURRENT_KEY`。
- `control=ADDR:PORT` —— 启用内嵌 HTTP 控制端点（见 §2）：`/key` 设全局 key、`/dump` 实时落盘、`/keys` 看当前 key、`/health` 健康检查。

### 2. 用 CoverageTracer 包住「一个工作单元」
只要在工作单元的开始/结束之间包一层，探针就归到对应 key。与具体触发方式无关
（HTTP 请求、MQ 消费、定时任务、测试用例都行）：

```java
import peruser.CoverageTracer;

// 推荐 try-with-resources（自动 begin/end）
try (CoverageTracer t = CoverageTracer.start("case-login-error")) {
    targetSystem.doWork();          // 这里执行的被插桩类，探针落在该线程 ThreadLocal
}

// 等价显式写法
CoverageTracer.begin("user-A");
try { targetSystem.doWork(); } finally { CoverageTracer.end(); }
```
- `start(key)` / `begin(key)` 只设一个线程级 key；`end()` / `close()` 把本线程探针按 key 合并并清空
  （线程池复用也安全，因为每次 work unit 结束都清空）。
- 多个并发 work unit 落在不同线程 → 探针天然按线程隔离；结束后各归各的 key。

### 3. 生成每个 key 的独立报告
JVM 关闭后会自动写出 `coverage/coverage-<key>.exec`。再跑：
```bash
java -cp peruser-jacoco.jar peruser.ReportCli \
     --execdir coverage \
     --classes /path/to/your/target/classes \   # 必须是【原始未插桩】的字节
     --classes /path/to/some-lib.jar \           # 也可传 jar
     --sources /path/to/your/src/main/java \   # 可选；传了才能在报告里渲染源码
     --out reports
```
报告生成在 `reports/<key>/index.html`。**注意**：`--classes` 要传**原始（磁盘上未插桩）**的 class 文件，
用来做行/分支映射；agent 在内存里改的是运行时的类，磁盘 class 不动。

## 构建（自己重新生成 peruser-jacoco.jar）
工程现在用 **Maven**（`pom.xml`）构建，产物是 self-contained fat agent（内嵌 asm+jacoco、带 Premain-Class/Agent-Class 清单），目标 JVM 零依赖接入。
你改完 `src/peruser/*.java` 后，一条命令即可重出 agent jar：

```bash
cd peruser-jacoco
mvn clean package            # 产物在 target/peruser-jacoco.jar
```

- 目标字节码固定为 **Java 8（major version 52）**：`pom.xml` 里 `maven.compiler.release=8`，
  即使本机 Maven 跑在 JDK 21，产物仍是 Java 8 字节码，可在 Java 8+ 目标 JVM（如 web3）挂载。
- 依赖（asm 9.10.1 + jacoco core/report）的定制版已放在 `lib/`，并通过 `setup-m2.sh` 装进本地 `~/.m2`；
  **换机器首次构建前先跑一次** `bash setup-m2.sh`（见下）。无需联网拉标准 Central 版本。
- 首次构建（或换机器）需要本地 `.m2` 里有定制版 jacoco，否则 Maven 找不到 `org.jacoco:org.jacoco.core:0.8.15.202606040825`：

```bash
bash setup-m2.sh             # 把 lib/ 里的定制 asm + jacoco 装进 ~/.m2（仅一次）
mvn clean package
```

> ⚠️ **不要用 Maven Central 标准 `org.jacoco:org.jacoco.core:0.8.15` 替换**：那是另一份字节码，
> 与本项目依赖的定制版（版本号带日期后缀 `.202606040825`）内部 API / 覆盖率格式可能不一致，会导致编译或运行时异常。
> 日常构建请用上面的 `mvn` 命令；不要替换为 Maven Central 标准版。

## 目录
```
peruser-jacoco/
  pom.xml                    # Maven 构建（fat agent，Java 8）
  setup-m2.sh                # 首次构建前把 lib/ 里的定制 jacoco/asm 装进本地 ~/.m2
  target/peruser-jacoco.jar  # 产物：self-contained agent（asm+jacoco 已内嵌）
  lib/                        # asm 9.10.1 + jacoco core/report 定制版（agent 运行时依赖，已内嵌进 fat jar）
  integration/                # 参考模板（不被编译；本「control 端点」方案不要求使用）：
                              #   Web3CoverageInterceptor.java / Web3CoverageConfig.java（Spring 拦截器，反射调 CoverageTracer）
  src/peruser/                # 核心：InstrumenterFlow / ThreadLocalProbeArrayStrategy /
                              #   ThreadProbeStore / CoverageTracer / CoverageStore /
                              #   PerUserTransformer / PerUserAgent / ReportCli / Options /
                              #   RequestKeyHook（请求头钩子）/ RequestKeyWeaver（Servlet 统一入口 service 字节码织入，框架无关）
  README.md
```

## 适用与限制
- 适用：单实例、并发、需要按 key 切分覆盖率的精准测试 / 灰度比对 / 多租户隔离验证等。
- **目标 JVM 需 Java 8+**（agent 以 Java 8 字节码编译，已在 JDK 1.8 上验证可挂载）。若目标报
  `UnsupportedClassVersionError: peruser/... has been compiled by a more recent version`，说明用的是旧版
  agent，重新 `mvn clean package` 即可（`maven-compiler-plugin` 已强制 `release 8`）。
  能用「双实例双 agent」时优先官方方案（更简单、零改造）。
- 依赖 JaCoCo 0.8.15 内部包（`org.jacoco.core.internal.*`），随版本可能变动，升级需回归验证。
- 类加载隔离：被插桩类在运行时需能看见 `peruser.ThreadProbeStore`（agent jar 由 `-javaagent` 机制加在**系统 classpath**，
  大多数应用 OK）。**agent 故意不调用 `appendToBootstrapClassLoaderSearch`**：被插桩的应用类（系统 / 应用类加载器，
  均向上委派到系统类加载器）解析 `peruser.*` 时，与 `ControlServer` / `CoverageStore` / `PerUserAgent` 用的是**同一份**
  `ThreadProbeStore` 实例——这样 `/key` 设的 key、`/dump` 读到的 store 才是同一份。一旦误加 bootstrap 搜索，bootstrap 里
  会再存在一份 `ThreadProbeStore`，应用类向上委派命中 bootstrap 副本，而控制端点用的是系统 classpath 副本，两份静态字段
  互不连通 → 探针写进一份、读出另一份 → 覆盖率全空（5 字节空 `.exec`）。我们只插桩应用类（JDK 类已在 excludes 里排除），
  不需要 bootstrap 可见，因此保持「仅系统 classpath」最稳。若目标用隔离类加载器（部分插件框架）导致应用类看不到
  `peruser.*`，再另行把 agent jar 暴露给该加载器。
- asm 冲突：agent 内嵌 asm 9.10.1。若目标应用自带**不同** asm 版本且在同一 classpath，可能冲突；
  此时把目标应用的 asm 也对齐到 9.10.1，或改用 `Class-Path` 方式加载 agent 依赖。
- 性能：每个被插桩类每次方法调用多一次 `ThreadProbeStore.getProbes`（含一次 `ConcurrentHashMap.putIfAbsent`），
  高频路径有轻微开销；可用 `includes=` 限定只插桩关心的包来降低。

## 在真实目标系统（web3 / Spring Boot，Java 8）上的用法

已用 `/Users/xiaoxiao/oATagent/web3-1.0-SNAPSHOT.jar`（Spring Boot 2.3.12，包 `web3Server`，端口默认 18083）
验证通过。`run-web3.sh` 即按下面「冒烟测试」方式启动。

### 1) 冒烟测试（autokey，零代码改动）
`autokey=KEY` 把所有线程探针合并进一个 key（行为类似官方 JaCoCo），用来先确认探针能挂上、能录到真实代码：

```bash
# 用 Java 8 启动（web3 是 JDK 8 编译的 Spring Boot）
/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java \
  -javaagent:/abs/peruser-jacoco.jar=outdir=coverage,autokey=smoke,includes=web3Server \
  -jar /Users/xiaoxiao/oATagent/web3-1.0-SNAPSHOT.jar
```

- 启动后日志首行出现 `[peruser] agent attached; ... mergeMode(autokey=smoke)` 即成功挂接。
- 正常驱动业务（如 `curl http://127.0.0.1:18083/web3/circulate?n=5` 等控制器接口）。
- **停止 web3（Ctrl-C）** 触发 JVM 关闭钩子，写出 `coverage/coverage-smoke.exec`。
- 生成**带源码**报告（用官方 jacococli，最省事；本工程不含该 jar，从 jacoco 发行包取）：

```bash
unzip -oq web3-1.0-SNAPSHOT.jar 'BOOT-INF/classes/*' -d /tmp/web3-classes
JACOCOCLI=/Users/xiaoxiao/download-Package/jacoco-0.8.15/lib/jacococli.jar   # 或你本地任意 jacoco 发行包中的 jacococli.jar
java -jar "$JACOCOCLI" report coverage/coverage-smoke.exec \
  --classfiles /tmp/web3-classes/BOOT-INF/classes \
  --sourcefiles /path/to/remoteweb3/src/main/java \
  --html reports-smoke
# 打开 reports-smoke/index.html
```
> `--sourcefiles` 就是 web3 的 Java 源码根目录（你之前用 `jacococli report --sourcefiles` 出过源码的那个目录）。

> 实测：单次 `/web3/*` 探测后，`Web3Controller.circulate(int)` = **100%**，其余方法按是否真正进入方法体分别 0%~66%，
> 证明探针在真实 web3 代码上正确记录。

### 2) 按用户 / 按用例分离（control 端点，零改目标系统）
> **关键**：本方案**不需要修改目标系统源码、不需要重打包** web3。A/B 分离的 key 由 **agent 内嵌的 HTTP 控制端点**
> 从进程外设定，适合「单实例 + 时间窗口」式驱动：先设 `key=1` 跑 A、实时 `dump&reset`，再设 `key=2` 跑 B。
> （若你愿意改目标系统，也可以用 `CoverageTracer` 把每个请求包一层，效果等价，见 §下「CoverageTracer 模式」。）

启动命令加 `control=ADDR:PORT` 启用内嵌控制端点（复用 JDK 自带 `com.sun.net.httpserver`，无额外依赖）：

```bash
/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java \
  -javaagent:/abs/peruser-jacoco.jar=outdir=coverage,includes=web3Server,control=127.0.0.1:9300 \
  -jar /Users/xiaoxiao/oATagent/web3-1.0-SNAPSHOT.jar
```

agent 启动后日志会打印 `control server started: http://127.0.0.1:9300  ( /key /dump /keys /health )`。端点：

- `GET /health` —— 健康检查。
- `GET /key?name=X` —— 设定**全局当前归属 key**（进程外驱动 A/B 分离）；不带 `name` 则返回当前 key。
- `GET /dump[?key=X][&reset=true]` —— 把覆盖率写成 `coverage-<key>.exec`（**合并写**，见下「并发不互相覆盖」）。
  - 不带 `key`：写出**所有** key；带 `key=2`：只写出 `key=2`（不影响其它 key 的内存累计）。
  - `reset=true`：写出后清空对应 key 的内存累计（等价于 `jacococli dump --reset`，但天然支持多 key）。
- `GET /reset[?key=X]` —— 清空累计探针；不带 `key` 清空**全部**，带 `key=2` 只清空 `key=2`（不影响其它 key）。
- `GET /keys` —— 列出当前所有 key 与模式。

> **并发不互相覆盖（重要）**：`/dump` 写 `coverage-<key>.exec` 时采用**合并写**——若文件已存在，当前内存探针会按位 OR 合并进已有数据，
> 而不是从零覆盖。因此「中途 `/dump?reset=true` 清了某 key 的内存、该 key 仍在线继续跑、再 `/dump`」不会把已落盘数据覆盖成不完整快照，
> 而是累加回来。多 key 并发、有人还在跑时，互不干扰。
> 若想让某个 key **干净重跑**（丢弃历史），先 `rm coverage-<key>.exec` 再重新打业务 + dump；`reset` 只清内存，文件因合并写会保留并累加。

驱动 A/B 分离（shell 示例；**localhost 务必绕过代理** `curl --noproxy '*'`，否则连不上 127.0.0.1 端口）：

```bash
# ---- 用户 A ----
curl --noproxy '*' -s "http://127.0.0.1:9300/key?name=1"        # 设全局当前 key=1
curl --noproxy '*' -s "http://127.0.0.1:18083/web3/circulate?n=5"   # 用 A 的凭证驱动业务
curl --noproxy '*' -s "http://127.0.0.1:9300/dump?reset=true"   # 实时落盘并清空 -> coverage-1.exec

# ---- 用户 B ----
curl --noproxy '*' -s "http://127.0.0.1:9300/key?name=2"        # 切到 key=2
curl --noproxy '*' -s "http://127.0.0.1:18083/web3/circulate?n=5"   # 用 B 的凭证驱动业务
curl --noproxy '*' -s "http://127.0.0.1:9300/dump?reset=true"   # -> coverage-2.exec
```

机制：被插桩类在每个方法入口调 `ThreadProbeStore.getProbes`，归属优先级为
① `CoverageTracer.begin(key)` 设的线程局部 key（需目标系统包一层，本方案不用）→
② 控制端点设的**全局当前 key**（本方案用）→
③ 都没有则探针直接丢弃（不落盘）。
`/key` 设的全局 key 对**所有线程**生效，所以「先设 key=1 跑完 A → `dump&reset` → 再设 key=2 跑 B」即可干净分离。
JVM 正常关闭（Ctrl-C）时关闭钩子也会把尚未 reset 的 key 各出一份 `.exec`。

> 与 `autokey` 互斥：`autokey` 是「全部线程合并一个 key」的冒烟模式；做按用户分离时不要带 `autokey`。
> 本方案不依赖 `CoverageTracer`、不改目标系统，但需要你从进程外用 `/key` + `/dump` 驱动。

### 3) 并发按用户分离（headerkey，零改目标系统、真正支持并发、框架无关）
> **关键**：如果你要的是「A、B **同时**请求 web3，各自归到 coverage-1 / coverage-2」——即单实例并发、按请求归属——用本方案。
> 它**不需要改 web3 源码、不需要重打包**，也不依赖顺序驱动，并且**不绑定任何具体 Web 框架**。
> agent 在字节码层给所有 Servlet 容器的统一入口 `javax.servlet.http.HttpServlet` / `jakarta.servlet.http.HttpServlet`
> 的 `service(ServletRequest, ServletResponse)` 方法包一层钩子（Tomcat/Jetty/Undertow 都会调用它，Spring 也经由它进入）：
> 每次请求入口读请求头（默认 `X-Coverage-Key`），非空就把**这一次请求**归属到该 key（线程级，并发互不串）；出口 `finally` 提交。
> 请求**不带**该头时回退到全局 `CURRENT_KEY`（§2 控制端点），两种机制可共存。

启动命令加 `headerkey=NAME`（建议同时保留 `control` 端点便于实时 dump / 看 key）：

```bash
/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java \
  -javaagent:/abs/peruser-jacoco.jar=outdir=coverage,includes=web3Server,headerkey=X-Coverage-Key,control=127.0.0.1:9300 \
  -jar /Users/xiaoxiao/oATagent/web3-1.0-SNAPSHOT.jar
```

驱动（A、B 可**完全并发**，不再需要顺序窗口）：

```bash
# A 带 X-Coverage-Key:1，B 带 X-Coverage-Key:2，同时打业务接口
curl --noproxy '*' -H "X-Coverage-Key: 1" "http://127.0.0.1:18083/web3/circulate?n=5"   # 用 A 凭证
curl --noproxy '*' -H "X-Coverage-Key: 2" "http://127.0.0.1:18083/web3/circulate?n=5"   # 用 B 凭证
# 跑完后一次 dump，两个 key 各出一份（合并写：中途再 dump 也不会覆盖，只会累加）
curl --noproxy '*' -s "http://127.0.0.1:9300/dump?reset=true"
# -> 同时生成 coverage-1.exec 与 coverage-2.exec
# 只关心某个用户时也可单 key 操作，互不干扰：
#   curl --noproxy '*' -s "http://127.0.0.1:9300/dump?key=2"      # 只出 key=2
#   curl --noproxy '*' -s "http://127.0.0.1:9300/reset?key=2"     # 只清 key=2 的内存累计
```

机制：被插桩类的方法入口调 `ThreadProbeStore.getProbes`，归属优先级为
① `CoverageTracer.begin(key)` / **请求头 key**（线程局部，本方案走这条）—— agent 在 `HttpServlet.service` 入口 `begin`、出口 `finally` `end`，每条请求独立线程级 key →
② 全局 `CURRENT_KEY`（§2 控制端点）→
③ 都没有则探针直接丢弃（不落盘）。
因为每次请求在 `HttpServlet.service` 的线程里独立 `begin/end`，A、B 并发请求分别落在各自线程的 key，天然互不污染，即「单实例并发按用户分离」。

> 验证：本地对真实 `javax.servlet.http.HttpServlet` 做了 ASM 织入测试（`COMPUTE_FRAMES` 用目标类加载器解析父类、失败回退 `java/lang/Object`，不再因加载不到 Spring 类而崩溃），并跑了并发链路测试——按请求头归属后 `dumpAll` 同时产出 `coverage-1.exec` 与 `coverage-2.exec`，且两 key 覆盖位不同。

#### （可选）CoverageTracer 模式（改目标系统时更细粒度）
若你**愿意**在目标系统里包一层，可在「一个工作单元」的边界调 `CoverageTracer`：

```java
import peruser.CoverageTracer;
try (CoverageTracer t = CoverageTracer.start("user-A")) {   // 自动 begin/end
    targetSystem.doWork();
}
```

`start/begin` 设的是**线程局部** key（优先级 ①，高于全局 key），`end/close` 在单元结束时按 key 合并并清空（线程池复用安全）。
这适合「同进程内多个工作单元并发、且每个单元的 key 在代码里就能确定」的场景；否则用上面的控制端点更省事。
`integration/` 目录保留了 web3 的反射版拦截器模板，仅作参考，本方案不要求使用。

### 已知坑（web3 实测）
- 控制器类级基路径是 `/web3`（如 `Web3Controller` 上的 `@RequestMapping("/web3")`），接口是 `/web3/circulate` 等。
- `bootstrap.yml` 连 Nacos（`172.16.10.77:8848`）；沙箱里连不上时应用仍用 `application.yml` 默认值正常启动，
  但依赖 Nacos/DB 的接口会 400/500（属业务外部依赖，不影响探针本身）。
- 报告 `--classes` 一定要用磁盘上的**原始** class（agent 只改内存运行时类，磁盘字节不变），否则对不上探针 id。

