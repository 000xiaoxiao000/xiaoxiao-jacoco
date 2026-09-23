# xiaoxiao-jacoco —— 单实例并发下按 key 分离的精准覆盖率

对任意「被插桩的目标系统」，在**单实例 + 多用户/多用例并发**执行时，把覆盖率按 **key**
（用户 / 用例 / 请求 / 任务）干净分离，互不污染。基于 JaCoCo 0.8.15，但以 **ThreadLocal 探针**
替换其默认「全局静态字段探针」。

> 为什么 JaCoCo 默认做不到：它的 `Instrumenter` 强制把探针存进静态字段 `$jacocoData`，所有线程共享，
> 并发覆盖在全局数组合并。`sessionid` / `append` / `dump --reset` 都解决不了「单实例并发按 key 分离」。
> 本方案绕过 `Instrumenter`，复刻其内部 `ClassProbesAdapter + ClassInstrumenter(IProbeArrayStrategy)` 流程，
> 把探针数组改为「按 key 取、直写共享数组」，并让 key 能跟着异步调用链走（见 §2.6）。

工程由**两个可独立打包的模块**组成，**不含任何 HTTP 接口**：

| 模块 | 产物 | 用途 |
|---|---|---|
| `xiaoxiao-jacoco-agent` | `xiaoxiao-jacoco-agent.jar` | javaagent，挂到目标 JVM。支持**官方 JaCoCo agent 的全部参数** + 按 key 分离扩展 |
| `xiaoxiao-jacoco-cli` | `xiaoxiao-jacoco-cli.jar` | 独立命令行工具。支持**官方 jacococli 的全部命令**：report / merge / dump / instrument / classinfo / execinfo / version |

---

## 一、快速开始

### 1. 构建

```bash
cd xiaoxiao-jacoco
bash setup-m2.sh          # 首次（或换机器）把 lib/ 里的定制 asm + jacoco 装进 ~/.m2
mvn clean package

# 也可以只打其中一个模块
mvn -pl xiaoxiao-jacoco-agent -o package
mvn -pl xiaoxiao-jacoco-cli   -o package
```

产物：

```
xiaoxiao-jacoco-agent/target/xiaoxiao-jacoco-agent.jar   # fat jar，内含 asm + jacoco core
xiaoxiao-jacoco-cli/target/xiaoxiao-jacoco-cli.jar       # fat jar，内含 asm + jacoco core + report，Main-Class 已配好
```

默认产物是**防反编译的剥离版**：不写调试信息（等价 `javac -g:none`），所以 jar 里没有行号、
没有局部变量名、也没有源文件名；MANIFEST 里也只保留 JVM 真正要读的入口属性，不暴露实现名 / 版本 / 构建 JDK。
（这**不影响覆盖率**：JaCoCo 读的是**被测业务类**的行号，与探针自身的调试信息无关。）

```bash
# 内部排障版：保留行号 / 变量名，产出 *-debug.jar（仅内部用，不要外发）
mvn -o package -Pdebug-symbols
# 不改 pom 的临时开法
mvn -o package -Djacoco.debug.level=source,lines,vars
```

> ⚠️ 调试信息是靠 `debuglevel`（不是 `debug`）控制的：`maven-compiler-plugin` 的
> `debug=false` 是个陷阱 —— plexus-compiler 在 `debug=false` 时**根本不传 `-g`**，
> 于是落到 javac 默认（`-g:source,lines`），行号和源文件名照样进 class。
> 另外 `debug` 参数没有 user property，设 `maven.compiler.debug` 无效。详见根 `pom.xml` 里的注释。

> ⚠️ **不要用 Maven Central 标准 `org.jacoco:org.jacoco.core:0.8.15` 替换**：本项目依赖的是定制版
> （版本号带日期后缀 `.202606040825`），内部 API / 覆盖率格式可能不一致。必须先用 `setup-m2.sh` 装进本地 `.m2`。

### 2. 挂 agent

```bash
java -javaagent:/abs/path/xiaoxiao-jacoco-agent.jar=outdir=coverage,includes=com.foo \
     -jar your-target-app.jar
```

### 3. 归属 key（三选一，都不改业务逻辑也能用）

**① `headerkey` —— 按 HTTP 请求头归属（推荐，零改代码、并发安全、框架无关）**

```bash
-javaagent:...=outdir=coverage,includes=com.foo,headerkey=X-Coverage-Key
```

agent 在 `HttpServlet.service` 入口织入钩子，读请求头 `X-Coverage-Key`，非空即把**这一次请求**归属到该 key
（线程级，A/B 并发互不串）。不带该头时回退到 `CoverageTracer` 设置的 key 或 `autokey`。

**② `autokey` —— 冒烟模式（零改代码，全进程合并成一个 key）**

```bash
-javaagent:...=outdir=coverage,autokey=smoke,includes=com.foo
```

`start/begin` 设的是**线程局部** key（存在 `InheritableThreadLocal` 里，异步子线程也能拿到，见 §2.6），
探针**直写该 key 的共享数组**，所以异步任务里即使不调 `end()` 也不会丢数据；
`end/close` 只是退出该 key 的归属区间（线程池复用安全）。

### 4. 出报告

```bash
java -jar xiaoxiao-jacoco-cli.jar report \
     --execdir coverage \
     --classfiles /path/to/target/classes \     # 必须是【原始未插桩】的字节，可重复传，也可是 jar
     --sourcefiles /path/to/src/main/java \     # 可选，传了才能在报告里渲染源码
     --html reports
```

`--execdir coverage` 默认把该目录下所有 `*.exec` **合并成一份**报告（原生并集）；加 `--perkey` 才按每个 key 拆成 `reports/<key>/index.html` 多份，格式与官方 `jacococli report` 完全一致。
加 `--merge` 额外出一份全员并集 `reports/all/index.html`。`--html`/`--xml`/`--csv` 任选其一或组合。

---

## 二、agent 参数（= 官方全部参数 + 按 key 扩展）

参数是逗号分隔的 `k=v`，值可用双引号包裹（内含 `,`/`=` 也安全，与官方一致）。

### 2.1 官方 JaCoCo agent 参数（语义与官方完全一致）

| 参数 | 说明 | 默认 |
|---|---|---|
| `destfile=<path>` | exec 输出文件 | `jacoco.exec` |
| `append=true\|false` | 是否向已存在的 exec 追加（true 时按 classId **OR 合并**，不覆盖历史） | `true` |
| `includes=<pattern>` | 仅插桩匹配的类，`*` `?` 通配符，`:` 分隔多项。**全匹配**（JaCoCo `WildcardMatcher` 用 `Pattern.matches`），**不是前缀匹配** —— 见下方 ⚠️ | `*` |
| `excludes=<pattern>` | 不插桩匹配的类，`:` 分隔多项 | 空 |
| `exclclassloader=<pattern>` | 跳过匹配的类加载器（`exclclassloaders` 亦可） | 空 |
| `inclbootstrapclasses=true\|false` | 是否插桩 bootstrap 类加载器加载的类 | `false` |
| `inclnolocationclasses=true\|false` | 是否插桩没有 source location 的类 | `false` |
| `sessionid=<id>` | session 标识 | 自动生成 |
| `dumponexit=true\|false` | JVM 关闭时是否 dump | `true` |
| `output=file\|tcpserver\|tcpclient\|none` | 输出方式 | `file` |
| `address=<host/ip>` | tcpserver 监听地址 / tcpclient 目标地址 | 回环地址 |
| `port=<port>` | tcpserver 监听端口 / tcpclient 目标端口 | `6300` |
| `classdumpdir=<path>` | 插桩前把**原始未插桩** class 字节落盘到该目录 | 不落盘 |
| `jmx=true\|false` | 注册 `com.xiaoxiao.jacoco:type=Runtime` MBean，支持运行时 dump/reset | `false` |

未知参数**只打印告警不致命**（官方会对未知 key 直接 FATAL）。

> ⚠️ **头号坑：`includes` 是全匹配，不是前缀匹配。**
> JaCoCo 的 `WildcardMatcher` 用 `Pattern.matcher(s).matches()`，所以每个匹配项必须覆盖**整个** VM 类名
> （`com/foo/Bar` 这种斜杠形式）。因此：
>
> | 写法                        | 实际效果                                                                            |
> |-----------------------------|-------------------------------------------------------------------------------------|
> | `includes=webServer`        | ❌ 只匹配「类名**恰好**等于 `webServer`」的类 → 几乎必然 **0 个类插桩、覆盖率全空** |
> | `includes=webServer*`       | 只匹配类名以 `webServer` **开头**的（仅当它位于包名开头）                           |
> | `includes=*webServer*`      | ✅ 类名任意位置包含 `webServer`                                                     |
> | `includes=com.remote.web.*` | ✅ 该包及其子包下所有类（推荐）                                                     |
>
> `includes` 匹配的是 **VM 类名**，不是 URL 路径（`/web/testWeb`）、不是模块名、不是包名简写。
> 写错时 agent 启动日志会直接点名并给出改法；拿不准就先 `includes=*` 跑通，再看 §3.3.1 的自检输出。

### 2.2 xiaoxiao-jacoco 扩展参数（按 key 分离）

| 参数 | 说明 | 默认 |
|---|---|---|
| `outdir=DIR` | 按 key 输出 exec 的目录 | `coverage` |
| `autokey=KEY` | 冒烟模式：所有线程合并进单一 KEY | 不启用 |
| `headerkey=NAME` | 按 HTTP 请求头 NAME 归属 | 不启用 |
| `async=true\|false` | 异步（线程池 / @Async / CompletableFuture）key 传递，见 §2.6 | `true` |
| `streamkey=true` | parallelStream / ForkJoinTask 的 key 传递，见 §2.7（**默认关**：热路径） | `false` |
| `mqkey=true` | 跨进程 MQ 的 key 透传（Kafka / RocketMQ / RabbitMQ），见 §2.8（**默认关**：会加消息头） | `false` |
| `mqheader=NAME` | MQ 透传用的 header / property 名 | `X-Coverage-Key` |
| `httpkey=true` | 跨服务出站调用（Feign / OkHttp / Apache / Dubbo / gRPC / Spring）的 key 透传，见 §2.9（**默认关**：会加请求头） | `false` |
| `httpheader=NAME` | 出站透传用的 header 名（缺省继承 `headerkey=` 的名字） | `X-Coverage-Key` |
| `rpckey=true\|false` | 跨服务入站归属（Dubbo provider / gRPC server / Spring WebFlux），见 §2.10（**只读**，默认随 `headerkey=` / `httpkey=true` 生效） | 随上两者 |
| `classcache=true\|false` | 内存缓存被插桩类的原始字节（`dumpclasses` 依赖它） | `true` |
| `classcachemax=MB` | classcache 的字节上限，超过即停止缓存 | `64` |
| `cleanup=24h` | 定期清理 outdir 下的旧 exec/class（`30m` / `2h` / `1d`，纯数字按小时） | 不启用 |
| `debug=true` | 打印被插桩的类名与每次请求归属明细（排障用，默认只打前 3 次归属） | `false` |

> 后四个「默认关 / 有限额」的参数，是「探针不影响目标系统」这条底线的具体体现：
> 热路径织入与内存/消息上的额外开销，都必须由使用方显式点头才会发生。

### 2.3 exec 文件名规则

- 未指定 `destfile`：`coverage/coverage-<key>.exec`
- 指定 `destfile`：`<destfile 目录>/<destfile 前缀>-<key>.exec`
- `autokey` 单 key 模式：直接写 `destfile` 本身（与官方完全一致）

### 2.4 output 四种模式

> `output=` 是**输出通道**，取值只有 `file` / `tcpserver` / `tcpclient` / `none` 四个，
> **不是 IP 地址**。IP 和端口请分别用 `address=` 和 `port=` 指定（写错会给出明确报错，不会静默失败）。

- **`file`**（默认）：JVM 退出（或 JMX dump）时按 key 写 `outdir/<prefix>-<key>.exec`。
- **`tcpserver`**：agent 在 `address:port` 上**监听**，等外部来抓，**协议与官方完全一致**，
  `jacococli dump` 与 `xiaoxiao-jacoco-cli dump` 都能直接抓。
  - **必须显式指定 `address`**：留空只绑 `127.0.0.1`，其它机器连不上（会 `Connection refused`）。
    跨机请写 `address=<被测机对外IP>` 或 `address=0.0.0.0`。
  - TCP 通道里是**所有 key 的并集**；但 JVM 退出 / JMX dump 时**照样会**把按 key 分离的
    `outdir/<prefix>-<key>.exec` 落盘，两条路都走。
  - 客户端不发命令时（如 `nc`）等待 5s 后照样回一次 dump。
- **`tcpclient`**：agent 启动即连上 `address:port`（`address` 填**采集端** IP），
  JVM 退出时把并集 exec 流推过去；同样也会把按 key 的 exec 落到 outdir。
  - 本工程不再自带采集端服务，需要你自己在 `address:port` 上起一个按 exec 协议读取的监听端。
- **`none`**：不主动输出，也不写文件，只能靠 JMX 触发。

### 2.4.1 跨机怎么选 tcpserver / tcpclient

| | `tcpserver` | `tcpclient` |
|---|---|---|
| 连接方向 | 你的电脑 → 被测机（**出方向**） | 被测机 → 你的电脑（**入方向**） |
| `address` 填 | **被测机**的 IP | **采集端**的 IP |
| 前提 | 被测机 `port` 对你可达（防火墙/安全组放行） | 被测机能访问到你的电脑（跨网段/NAT 通常不通） |

**绝大多数情况选 `tcpserver`**：被测机在机房/云上，你的电脑能连过去，反过来一般连不通。

### 2.5 JMX

```bash
-javaagent:...=outdir=coverage,jmx=true
```

MBean 对象名 `com.xiaoxiao.jacoco:type=Runtime`（刻意不占用官方 `org.jacoco:type=Runtime`，
避免与已挂的官方 jacoco agent 撞名），方法：`dump()` / `reset()` / `getVersion()` /
`getSessionId()` / `setSessionId()`。可用 `jconsole` / `jmxterm` / 代码调用。

### 2.6 异步归属：线程池 / @Async / CompletableFuture 不丢 key（默认开启）

key 原本存在 ThreadLocal 里，异步子线程拿不到 —— 覆盖要么丢失、要么记到别的 key 上（串号）。
开启后（默认 `async=true`）：

| 异步形式 | 传递方式 |
|---|---|
| `new Thread()` | key 存在 `InheritableThreadLocal`，子线程创建时自动继承 |
| 线程池（`Executors.*`、`ThreadPoolTaskExecutor`、Tomcat 工作线程…） | 织入 JDK 提交入口，**提交时捕获 key、执行时设置、结束回退** |
| `CompletableFuture.runAsync/supplyAsync` | 同上（走 `ForkJoinPool` 提交入口） |
| `@Async` | 同上（Spring 最终也是提交给某个 Executor） |
| `ScheduledExecutorService.schedule` | 同上（一次性任务；周期性任务不织入，它不属于单次请求） |

织入的 JDK 入口：`ThreadPoolExecutor.execute`、`AbstractExecutorService.submit`×3、
`ForkJoinPool.execute/submit`、`ScheduledThreadPoolExecutor.schedule`×2。
注入的等价代码只有一行：`task = peruserrt.KeyBridge.wrap(task);`
（当前线程没有 key 时 `wrap` 原样返回，零开销）。

另外探针现在**直写该 key 的共享数组**，不再依赖「线程局部副本 + `end()` 合并」，
所以异步任务里即使没人调 `end()`，数据也已经落在正确的 key 下。

```bash
# 默认就是开启的；显式关闭（比如目标 JVM 不允许重定义 JDK 类时）
-javaagent:...=outdir=coverage,async=false
```

自研线程池（任务先进自己的队列、再由别的线程取出执行，绕开了 JDK 提交入口）可手动包一层；
关闭后实测影响：线程池 / CompletableFuture 里的覆盖**全部丢失**（`async=false` 对照组结果为 0）。

### 2.7 parallelStream 归属：`streamkey=true`（默认关闭）

`parallelStream` / `ForkJoinTask.fork()` **不走**线程池的提交入口，所以 §2.6 的织入覆盖不到它。
开启后探针自己织入 `java.util.concurrent.ForkJoinTask` 的两个方法：

| 织入点 | 执行线程 | 作用 |
|---|---|---|
| `fork()` | 提交线程 | `KeyBridge.markFork(this)` —— 当前线程有 key 才挂到任务上，无 key 直接返回 |
| `doExec()` | ForkJoin 工作线程 | 入口 `forkEnter(this)` 取出 key 设进本线程，所有出口 `forkExit(this)` 回退并清表 |

任务与 key 的对应关系存在探针私有的 `ConcurrentHashMap` 里，**没有全局锁**；
且只有「提交线程当前有 key」时才写表，普通应用线程开销为零。

```bash
-javaagent:...=outdir=coverage,streamkey=true
```

**实测（JDK 21，预热 commonPool 后 —— 即池线程早于 key 存在、继承不到 key 的真实情形）**：

| | 工作线程看到的 key | 覆盖探针 |
|---|---|---|
| 不开 `streamkey` | `null` | 3 / 10 |
| `streamkey=true` | `k1` | **8 / 10** |

> 为什么默认关：`fork()` 是并行计算的热路径，多两条指令 + 一次哈希写入。
> 不需要按请求细分时用 `autokey` 或 `cli setkey`（进程级 key，不依赖线程传递）即可零开销采到。

### 2.8 跨进程 MQ 归属：`mqkey=true`（默认关闭）

key 存在 ThreadLocal 里，跨 JVM 就断了。开启后由探针在 MQ 客户端层面完成注入与读回，
**业务代码一行都不用改**（与 OpenTelemetry 把 trace context 写进 carrier 的做法一致）：

| 端 | 织入点 | 载体 |
|---|---|---|
| Kafka 生产 | `KafkaProducer.send(ProducerRecord, Callback)` | record headers |
| Kafka 消费 | `@KafkaListener` 方法 / `MessageListener` 实现 | ConsumerRecord headers |
| RocketMQ 生产 | `DefaultMQProducer.send(Message)` | `putUserProperty` |
| RocketMQ 消费 | `@RocketMQMessageListener` 类 / `MessageListenerConcurrently|Orderly` | Message property（批量取第一条） |
| RabbitMQ 生产 | `ChannelN.basicPublish(...)` | BasicProperties headers |
| RabbitMQ 消费 | `@RabbitListener` 方法 / `MessageListener` 实现 | Message headers |

```bash
-javaagent:...=outdir=coverage,mqkey=true,mqheader=X-Coverage-Key
```

约束与兜底：

- 只写**标准扩展区**（headers / user property），不碰业务字段；对面不是 Java 或没挂探针时，
  多出来的 header 会被直接忽略，对业务零影响；
- 写不进去就静默放弃（如 Rabbit 的 props 为 null、headers 是不可变 map），
  **绝不会为了塞 key 去改动消息的任何既有属性**，也不会让发消息失败；
- 消费端读不到 key 就当作「本次不归属」，行为与没开 `mqkey` 完全一致；
- 织入点全部按「类名 / 注解名 / 接口名」匹配，版本对不上就匹配不到 —— 安全降级，不会破坏目标类。

> 为什么默认关：加消息头属于**改变目标系统的数据**，必须由使用方显式点头。

### 2.9 跨服务调用归属：`httpkey=true`（默认关闭）

A 服务带着 `X-Coverage-Key` 进来，A 再用 Feign / HttpClient 调 B —— **这个头不会自动跟过去**，
B 侧读不到 key，本次调用的覆盖直接丢弃。开启后由探针在【出站客户端】层面把当前 key 写进请求头，
**业务代码一行都不用改**（不用再手写 Feign `RequestInterceptor` / HttpClient 拦截器）：

| 客户端 | 织入点 | 载体 |
|---|---|---|
| Feign | `SynchronousMethodHandler.targetRequest(RequestTemplate)`（+ `RequestTemplate.request()` 兜底） | `RequestTemplate.header` |
| OkHttp 3/4（含 Retrofit） | `Request$Builder.build()` | `addHeader` |
| Apache HttpClient 4.3+ | `InternalHttpClient` / `MinimalHttpClient.doExecute(...)` | `HttpRequest.addHeader` |
| Apache HttpClient 5.x | `InternalHttpClient.doExecute(...)` | `ClassicHttpRequest.addHeader` |
| Dubbo 2.x / 3.x | `AbstractClusterInvoker.invoke(Invocation)` | Invocation 附件（`setObjectAttachment` / `setAttachment`），兜底 `RpcContext` 客户端附件 |
| gRPC | `ClientCallImpl.start(Listener, Metadata)` | `Metadata` |
| Spring 通用兜底 | `AbstractClientHttpRequest.getHeaders()`（含 reactive 版） | `HttpHeaders` —— 一个类覆盖 RestTemplate / WebClient 的全部 ClientHttpRequest 实现 |

```bash
# A 服务：入站用 headerkey 归属，出站把同一个 key 透传给 B（header 名自动同名，无需配两遍）
-javaagent:...=headerkey=X-Coverage-Key,httpkey=true,includes=com.foo.*,output=tcpserver,address=0.0.0.0,port=6300

# B 服务：原样读取同一个头
-javaagent:...=headerkey=X-Coverage-Key,includes=com.bar.*,output=tcpserver,address=0.0.0.0,port=6301
```

透传用的 header 名优先级：`httpheader=<名>` > `headerkey=<名>` > 默认 `X-Coverage-Key`。
取 key 的口径与归属完全一致（线程 key → 全局 key → autokey 合并 key），
**「归属到哪个 key」和「透传出去哪个 key」永远是同一个**。

约束与兜底（与 `mqkey` 一致）：

- 只写标准 header 区，**已有同名 header 一律跳过**，绝不覆盖业务或网关已设置的值；
- **无 key 时零动作**：当前线程没归属就不写头，请求与不开探针时逐字节一致；
- 写不进去（如 Spring 的只读 `readOnlyHttpHeaders`）或反射不到方法 → 静默放弃，绝不影响发请求；
- 织入点按「类名 + 方法签名」精确匹配，版本对不上就匹配不到 —— 安全降级；
- 织入前会确认目标类的类加载器能解析 `peruser.HttpKeyHook`，看不见就不织（防 `NoClassDefFoundError`）；
- 这些客户端类通常不在 `includes=` 里，因此本织入独立于覆盖率插桩单独走一条分支。

### 2.10 跨服务入站归属：`rpckey`（Dubbo provider / gRPC server / WebFlux，默认随 headerkey / httpkey 生效）

上一段的 ⚠️ 已经解除：B 侧即使**没有 Servlet**（A→B 走 Dubbo / gRPC，或 B 本身就是 WebFlux），探针也能在
**业务方法执行前**从调用载体里读回 key 并归属，业务代码同样一行都不用改。

| 服务端 | 织入点 | 载体 | 形态 |
|---|---|---|---|
| Dubbo 3.x / 2.7 | `AbstractInvoker.invoke(Invocation)`（所有 protocol 的最终执行入口） | Invocation 附件（`getObjectAttachment` / `getAttachment`） | `try { 原方法体 } finally { exit() }` |
| Dubbo 3.x / 2.7 | `ContextFilter.invoke(Invoker, Invocation)`（provider 侧 `@Activate(PROVIDER)`） | 同上 | 同上 |
| Dubbo 2.6- | `com.alibaba.dubbo.*` 同名两个类 | 同上 | 同上 |
| gRPC（grpc-stub 生成的服务） | `ServerCalls$UnaryServerCallHandler.startCall(ServerCall, Metadata)`、`$StreamingServerCallHandler.startCall(...)` | 请求 `Metadata` | 入口 `enter` |
| gRPC | `ServerCallImpl.close(Status, Metadata)`（本次调用结束） | — | 出口 `exit` |
| **Spring WebFlux**（注解式 `@RestController`） | `InvocableHandlerMethod.lambda$invoke$N(ServerWebExchange, BindingContext, Object[])` —— controller 方法就在该 lambda 内被 `Method.invoke` **同步**调用 | `ServerWebExchange` → `getRequest().getHeaders().getFirst(name)`（`HttpHeaders` 大小写不敏感） | `try { 原方法体 } finally { exit() }` |

```bash
# B 服务（Dubbo provider / gRPC server）：和 HTTP 侧写法完全一样，不用为 RPC 多配任何参数
-javaagent:...=headerkey=X-Coverage-Key,includes=com.bar.*,output=tcpserver,address=0.0.0.0,port=6301
```

开关与命名：

- 入站是**只读**动作（不给请求加任何字段、不改业务数据），所以**不额外收用户同意**：
  只要配了 `headerkey=` 或 `httpkey=true`，`rpckey` 就自动为 on；`rpckey=true` / `rpckey=false` 可显式开关。
- 读的 header / 附件名与出站写的是同一个（优先级 `httpheader=` > `headerkey=` > `X-Coverage-Key`），
  **A 用什么头出去、B 就用什么头进来**，天然同名，不存在配错。

约束与兜底：

- **读不到 key 就零动作**：没有探针流量标识的调用与不开探针时行为完全一致，不会误归属；
- 同一 key 重复进入（filter → invoker 两层都命中）**只归属一次**，不产生嵌套计数；
- gRPC 的 enter / exit 不在同一个方法里（startCall / close），万一 close 没走到，
  下一次 enter 会**兜底收尾**上一次归属，绝不把 key 泄漏到复用线程上污染后面的请求；
- 反射读不到方法 / 抛任何异常 → 静默放弃，绝不影响业务被调；
- 同样按「类名 + 方法签名」精确匹配（已核对 Dubbo 2.6/2.7/3.x、gRPC 1.27 / 1.46 / 1.70、
  Spring WebFlux 5.3.x / 6.2.x 签名一致），版本对不上就匹配不到 —— 安全降级；
  织入前同样检查类加载器能否解析 `peruser.RpcInKeyHook`。

**WebFlux 的两个注意点**：

- 织入点不是 `HttpWebHandlerAdapter#handle`（统一入站口）。那个方法返回 `Mono`、**立即返回**，
  业务要等 subscribe 才执行 —— 在那里用 `try/finally` 会在业务跑之前就退出。
  真正的业务窗口是 `InvocableHandlerMethod` 里那个 lambda（`lambda$invoke$N`，编号随 Spring 版本变，
  故按**前缀**匹配），且它手里就拿着 `exchange`，能直接读请求头，不需要 pending 暂存 / 跨线程传递。
- 归属范围是「controller 方法同步执行窗口」，与 Servlet 侧 `HttpServlet.service` 的语义一致：
  controller 返回 `Mono` 之后、在 `flatMap` / `publishOn` 切走的线程上继续跑的代码不在窗口内
  （这部分靠 `async` 线程池传播覆盖）。

> 尚未覆盖：纯 `grpc-core` 自建 handler（不走 `grpc-stub` 生成代码）、
> WebFlux **函数式路由**（`HandlerFunction` / `RouterFunction`，handler 是业务自己的类、类名不定）。
> 这类场景仍可用 `autokey=<k>` 或 `cli setkey --key <k>` 兜底。

### 2.11 兜底：`CoverageTracer`（只在自研队列等极少数场景需要）

`headerkey` / `autokey` / `async` / `streamkey` / `mqkey` / `httpkey` / `cli setkey` 覆盖了绝大多数场景，
而且都**不需要业务代码配合**。剩下的唯一场景是：同进程内任务先进入**自研队列**（不走 JDK 提交入口），
再由别的线程捞起执行 —— 探针无从知晓你们的队列在哪里，这时才需要在入队处包一层

`CoverageTracer.start/begin/end` 仍保留（想在业务里显式划归属区间时可用），但它们**不是**使用前提。

---

## 三、cli 命令（= 官方 jacococli 全部命令）

```bash
java -jar xiaoxiao-jacoco-cli.jar <command> [options]
```

### 3.1 `report` —— 出报告

```bash
report [<execfiles> ...] --classfiles <path> [--classfiles <path> ...] \
       [--sourcefiles <path> ...] --html <dir> [--xml <file>] [--csv <file>] \
       [--encoding <enc>] [--name <name>] [--tabwidth <n>] [--quiet]
```

官方参数：`--classfiles` / `--sourcefiles` / `--html` / `--xml` / `--csv` / `--encoding` /
`--name` / `--tabwidth` / `--quiet`，语义与官方一致。

扩展参数（按 key 分离场景用）：

| 参数 | 说明 |
|---|---|
| `--execdir <dir>` | 收集该目录下所有 `*.exec`，**默认合并成一份**报告（与原生多 exec 并集一致） |
| `--perkey [key]` | 按 key 拆分（默认**关闭**；key 取自文件名 `coverage-<key>.exec` 的 `<key>` 段）。**带 key 时只出该 key 的报告**：`--perkey 1` 只生成 key=1 的报告（报告落在 `--html` 目录本身，不再建 `<key>/` 子目录）；不带的 `--perkey` 才是「每个 key 一份」 |
| `--merge` | 额外出一份所有 exec 的并集报告 `all/` |
| `--baseline-out <json>` | 采集方法级基线（跨构建增量用，见 §5） |
| `--baseline <json>` | 用基线做「方法级携带」，生成增量对比报告 |

> **原生格式独立开关**：`--html` / `--xml` / `--csv` 三者相互独立，只生成你指定的格式（与原生 `jacococli report` 一致）。
> **报告文件名按输入 exec 命名**：`--xml <dir>` / `--csv <dir>` 传【目录】时，文件名默认 = 输入 exec 的文件名（去掉 `.exec`）：
> - 单文件 `report coverage-1.exec --xml reports` → `reports/coverage-1.xml`（覆盖全 0 也照样按此命名）
> - `--perkey`（不带值）下每份按各自 exec 文件名：`coverage-1.xml` / `coverage-2.xml`（HTML 子目录同名 `coverage-1/`）
> - `--perkey 1` 只命中 `coverage-1.exec`：报告直接写到 `--html` 目录（无子目录），`--xml <dir>` 文件名 = 该 exec 名
> - 多 exec 合并（不加 `--perkey`）→ `reports/jacoco.xml` / `jacoco.csv`
> - `--merge` 的额外汇集报告 → `reports/all.xml` / `all.csv`
> - 显式传 `.xml` / `.csv` 文件（如 `--xml reports/jacoco.xml`）则原样，不受上述规则影响。

兼容旧写法：不带命令名、直接以 `--execdir` 开头时等价于 `report`。

### 3.2 `merge` —— 合并多个 exec

```bash
merge [<execfiles> ...] --destfile <path> [--append true|false]
```

按 classId **OR 合并**（不是覆盖）。

### 3.3 `dump` —— 从运行中的 agent 抓 exec

```bash
dump [--address <addr>] [--port <port>] [--destfile <path>] [--reset] [--retry <n>] [--quiet]
     [--key <k>]...        # 扩展：只抓某个 key，可重复或逗号分隔
```

**输出文件**：写到 `--destfile`（默认 `./jacoco.exec`）；多 key 时拆成 `<path 去 .exec>-<key>.exec`。
父目录不存在会**自动创建**；文件名不以 `.exec` 结尾会**自动补上**。

```bash
dump --address 172.xx.xx.10 --port 6300 --key 1 --destfile coverage/coverage-1.exec
dump --address 172.xx.xx.10 --port 6300 --key 2 --destfile coverage/coverage-2.exec
```

走官方二进制 TCP 协议，与 `jacococli dump` 互通。已存在同名 destfile 时按官方追加语义 OR 合并进去。

> 注意：`nc` 直连 tcpserver 抓到的是**裸协议流**，末尾多一个 `CMDOK(0x20)` 块，不是合法 exec 文件。
> 请用 `dump` 命令抓取（客户端会把该块剥离后再落盘）。

#### 按 key 抓取（`--key`，xiaoxiao-jacoco 专有）

不带 `--key` 时是**官方语义**：只能拿到「所有 key 的并集」，无法区分哪个覆盖来自哪个请求。
带 `--key` 时走私有扩展块，只拿该 key 的数据：

```bash
# 一次抓 1 和 2 两个 key，分别落盘
dump --address 172.xx.xx.10 --port 6300 --key 1,2 --destfile dumped.exec
#   -> dumped-1.exec   dumped-2.exec

# 只抓一个 key：直接写 --destfile
dump --address 172.xx.xx.10 --port 6300 --key 1 --destfile cov-1.exec

# 抓完顺便清掉该 key 的内存累计（不影响其它 key）
dump --address 172.xx.xx.10 --port 6300 --key 1 --reset --destfile cov-1.exec
```

多 key 时是**覆盖写**（文件即该 key 的完整数据），不与旧文件 OR 合并，保证各 key 严格分离。
`--reset` 只清指定 key，其它 key 不受影响。

> 该扩展块只有 `xiaoxiao-jacoco-agent` 认识；对官方 agent 请勿使用 `--key`。
> 不带 `--key` 时发的仍是官方块，官方 `jacococli dump` 可正常抓取。

### 3.3.1 `keys` —— 列出 agent 当前已采集的 key

```bash
keys [--address <addr>] [--port <port>] [--retry <n>] [--quiet]
```

输出 `headerkey` 抓到的所有取值（即 `X-Coverage-Key` 出现过的值），一行一个。
不知道有哪些 key 时先跑它，再把结果喂给 `dump --key`。

```bash
keys --address 172.xx.xx.10 --port 6300
# 1
# 2
dump --address 172.xx.xx.10 --port 6300 --key 1,2 --destfile dumped.exec
```

**它同时是「覆盖率为什么是空的」第一诊断入口**：除了 key 列表，还会回传 agent 端的自检计数
（`classes instrumented` / `requests hooked` / `tagged`），没有 key 时直接给出最可能的原因和
可直接抄的 `includes`。`dump --key K` 抓到 0 个类时也会自动拉一次这些数字。

```bash
keys --address 172.xx.xx.10 --port 6300
# [xiaoxiao-jacoco-cli] no key collected yet on 172.xx.xx.10:6300
#   ! agent 到目前为止【一个类都没有插桩】(classes instrumented=0)
#     -> 根因：includes/excludes 没匹配上。includes 匹配的是【VM 类名】(com/foo/Bar)，
#        不是 URL 路径（/web/testWeb）、不是模块名（webServer）、不是包名简写。
#     -> 该进程里已加载的类，包名样例：[com/remote/web/*]
#        建议把 agent 参数改成 includes=com/remote/web/*
#     -> 改完必须重启被测应用才生效。
```

判读方式：

| 自检数字 | 含义 | 该做什么 |
|---|---|---|
| `instrumented=0` | 一个类都没插桩 | 改 `includes`（VM 类名），或加 `inclnolocationclasses=true`（Spring Boot 可执行 jar 常见）；改完重启 |
| `instrumented>0`、`hooked=0` | 请求没进 `HttpServlet.service`（如 WebFlux/Netty 非 Servlet 栈） | 改用 `CoverageTracer` 埋点，或在入口 Filter 里 `begin(key)` |
| `hooked>0`、`tagged=0` | HTTP 入口进来了，但请求头没读到 | 核对 `headerkey=` 的头名、请求是否真的带了该 header |
| `tagged>0` 但仍抓不到 | key 字符串不一致（空格/大小写） | `keys` 列出的值才是 `dump --key` 该填的值 |

### 3.3.2 `stats` —— 插桩了什么、插了多少、各 key 采到多少（排障首选）

`keys` 只在「没数据」时才提示；想知道**插桩是否成功、插了哪些类、每个 key 各有多少覆盖**，
随时跑 `stats`，无需等自检、无需 dump：

```bash
java -jar xiaoxiao-jacoco-cli.jar stats --address 172.xx.xx.10 --port 6300
# --limit <n>  最多列多少个类名，默认 50；--limit 0 列全部
```

正常时输出：

```
插桩情况
  transformer 看到的类        171
  已插桩的类                  5
  无 source location 被跳过   0

请求归属
  HTTP 入口钩子触发次数       3
  成功读到 key 的次数         3

各 key 采集到的覆盖
  key                     classes  probes   covered  覆盖率
  1                       3        17       9        52.9%
  2                       4        18       17       94.4%

已插桩的类（共 5 个）
  demo/App
  demo/Controller
  ...
```

`已插桩的类 = 0` 时会直接给出诊断（includes 全匹配说明 + 该进程真实包名样例 + 可照抄的 `includes=`）：

```
诊断：一个类都没插桩（最常见原因是 includes 没匹配上）
  -> includes 匹配的是【VM 类名】(com/foo/Bar)，是全匹配（不是前缀匹配）；不是 URL 路径、不是模块名、不是包名简写
  -> 该进程里已加载的类，包名样例：[demo/*]
     建议改成 includes=demo/*（或先 includes=* 验证链路，再收窄）
  -> 被 includes 过滤掉的类样例：[demo/App, demo/Controller, ...]
```

> `stats` 是 xiaoxiao-jacoco 私有块（0x43/0x22）。
> 命令会提示「不支持 stats，请升级 agent」。

### 3.3.3 `dumpclasses` —— 从运行中的 agent 拉回「被插桩类的原始字节码」（免 scp / 免镜像）

覆盖率报告的分母（classfiles）：
`classdumpdir` 落在容器里本地读不到、构建产物 jar 在镜像仓库里不好拉。本命令走 agent 已有的
tcpserver 通道（与 `dump`/`keys`/`stats` 同源），把 agent 内存里缓存的【原始（未插桩）字节码】
打成 zip 流回本地，彻底免容器访问、免镜像访问。

```bash
dumpclasses [--address <addr>] [--port <port>] [--outdir <dir>] [--zip <file>] [--retry <n>] [--quiet]
```

```bash
# 从被测机 agent 拉回 classfiles（解压到 ./libs，可直接当 report 的 --classfiles）
java -jar xiaoxiao-jacoco-cli.jar dumpclasses --address 172.xx.xx.10 --port 6300 --outdir libs --zip libs.zip

# 然后出报告（classfiles 指向解压目录）
java -jar xiaoxiao-jacoco-cli.jar report coverage/coverage-1.exec --classfiles libs --html reports
```

要点：
- **必须是原始（未插桩）字节码**：agent 在插桩前就把类字节码留在内存（默认开，可用 `classcache=false` 关闭），
  JaCoCo 报告时用 `Analyzer` 自行重插桩对齐探针编号，所以缓存原始字节才能和 exec 对上。
- 解压出的目录结构就是 `com/foo/Bar.class`，`report --classfiles <outdir>` 的 `AnalyzePaths` 会递归扫 `.class`，直接可用。
- 内存有 256MB 上限保护，超限后停止缓存并告警（已缓存的部分仍可用）；超大应用建议改用构建产物 jar / `classdumpdir`。
- 这是 xiaoxiao-jacoco 的专有扩展（私有块 `0x44`/`0x23`）。官方 agent 不认识该块，会退化成一次普通 dump，
  此时命令会提示「agent 不支持 dumpclasses，请升级 xiaoxiao-jacoco-agent」。

### 3.3.4 `setkey` —— 远程设定全局当前 key（**零改业务代码**的按 key 分离）

不想改目标系统代码、又想按用例/批次分离覆盖率时用这个：归属判定发生在**进程级**，
不依赖线程传递，所以 `parallelStream`、自研线程池这些「key 传不过去」的场景也能采到。

```bash
setkey --key <k> [--address <addr>] [--port <port>] [--retry <n>] [--quiet]
setkey --clear [--address <addr>] [--port <port>]     # 清除全局 key
```

```bash
# 典型：单实例 + 时间窗口式 A/B 分离（全程不用碰业务代码）
java -jar xiaoxiao-jacoco-cli.jar setkey --key case-A --address 172.16.11.13 --port 6300
#   ... 跑用例 A ...
java -jar xiaoxiao-jacoco-cli.jar dump --key case-A --destfile coverage/case-A.exec --reset
java -jar xiaoxiao-jacoco-cli.jar setkey --key case-B --address 172.16.11.13 --port 6300
#   ... 跑用例 B ...
java -jar xiaoxiao-jacoco-cli.jar dump --key case-B --destfile coverage/case-B.exec --reset
java -jar xiaoxiao-jacoco-cli.jar setkey --clear --address 172.16.11.13 --port 6300
```

要点：
- 全局 key 对**整个进程**生效，期间所有「没有线程 key」的覆盖都归到它。
  **要真正并发地按请求/用例分离，请用 `headerkey`（也是零改代码）或业务侧 `CoverageTracer`。**
- 线程级 key（请求头 / `CoverageTracer`）优先级**高于**全局 key，两者可以共存。
- 这是私有扩展（块 `0x45`）。官方 agent 不认识，会退化成一次普通 dump。

### 3.4 `instrument` —— 离线插桩

```bash
instrument [<sourcefiles> ...] --dest <dir>
```

### 3.5 `classinfo` —— 查看类的探针布局

```bash
classinfo [<classlocations> ...] [--verbose]
```

### 3.6 `execinfo` —— 查看 exec 内容

```bash
execinfo [<execfiles> ...] [--verbose] [--quiet] [--execdir <dir>]
```

输出 sessions / classes / probes。

### 3.7 `version` / `help`

---

## 四、典型用法组合

### 4.1 按用户 / 按用例分离（headerkey，零改目标系统、真正支持并发）

```bash
java -javaagent:/abs/xiaoxiao-jacoco-agent.jar=outdir=coverage,includes=com.foo,headerkey=X-Coverage-Key \
     -jar your-app.jar
```

```bash
# 用户 A
curl -H "X-Coverage-Key: user-A" http://host/api/xxx
# 用户 B（可与 A 并发）
curl -H "X-Coverage-Key: user-B" http://host/api/xxx

# 跑完一次 dump，两个 key 各出一份（合并写：中途再 dump 也不会覆盖，只会累加）
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage --perkey \
     --classfiles /path/to/classes --sourcefiles /path/to/src --html reports
# -> reports/user-A/index.html   reports/user-B/index.html
# 不加 --perkey 时默认合并成一份 reports/index.html

# 只想看某一个 key：--perkey <key>（只该 key 的报告，写在 reports 根下）
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage --perkey user-A \
     --classfiles /path/to/classes --html reports
# -> reports/index.html（只有 user-A 的数据）
```

### 4.2 长跑服务：tcpserver + 跨机抓取（本机 192.168.6.130 ← 被测机 172.xx.xx.10）

被测机上（**`address` 必须填被测机自己的 IP**，否则只绑回环，你连不上）：

```bash
# includes 必须写【VM 类名】通配，写成 webServer（模块名/URL 片段）会一个类都匹配不到
java -javaagent:/abs/xiaoxiao-jacoco-agent.jar=outdir=coverage,includes=com.remote.*,inclnolocationclasses=true,output=tcpserver,address=172.xx.xx.10,port=6300,headerkey=X-Coverage-Key \
     -jar web-1.0-SNAPSHOT.jar
```

> 拿不准包名就先 `includes=*` 起一次，`keys` 命令会把该进程里已加载类的包名样例和建议的
> `includes` 直接打出来，照抄即可（§3.3.1）。Spring Boot 可执行 jar 建议常带
> `inclnolocationclasses=true`。

你的电脑上：

**不带 `--key`（官方语义，拿到所有 key 的并集）**：

```bash
java -jar xiaoxiao-jacoco-cli.jar dump --address 172.xx.xx.10 --port 6300 --destfile coverage/dumped.exec
java -jar xiaoxiao-jacoco-cli.jar report --exec coverage/dumped.exec --classfiles /path/to/classes --html reports
```

**按 key 分别抓（推荐，多用户场景）**：

```bash
# 先看有哪些 key
java -jar xiaoxiao-jacoco-cli.jar keys --address 172.xx.xx.10 --port 6300

# 一次抓 1 和 2，分别落盘 dumped-1.exec / dumped-2.exec
java -jar xiaoxiao-jacoco-cli.jar dump --address 172.xx.xx.10 --port 6300 --key 1,2 \
     --destfile coverage/dumped.exec

# 各自出报告
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage --classfiles /path/to/classes \
     --sourcefiles /path/to/src --html reports
```

另外被测机 JVM 退出时还会把按 key 分离的 `coverage/coverage-<key>.exec` 落盘，
把它 scp 回来用 `--execdir` 同样能出每个 key 各一份报告。

### 4.3 长跑服务：JMX 触发

```bash
java -javaagent:...=outdir=coverage,includes=com.foo,jmx=true -jar your-app.jar
# jconsole 连上 -> com.xiaoxiao.jacoco:type=Runtime -> dump()
```

### 4.4 冒烟验证（先确认探针能挂上）

```bash
java -javaagent:/abs/xiaoxiao-jacoco-agent.jar=outdir=coverage,autokey=smoke,includes=com.foo -jar your-app.jar
java -jar xiaoxiao-jacoco-cli.jar execinfo --execdir coverage     # 有探针数据说明挂上了
```

### 4.5 docker / k8s：免 scp 拿 classfiles（dumpclasses）

企业容器化部署通常**禁止 scp、不提供被测机 root 密码**，覆盖率报告的分母（classfiles）就成了老大难。
传统两条路在容器里都走不通：

- `classdumpdir` 落盘在容器内，本地 scp 不出来（且无 ssh 入口）；
- 构建产物 jar 在镜像仓库，拉取要凭证、还常比运行版本「新/旧」对不上。

本项目的 `dumpclasses` 命令绕开这两点：exec 早已能经 `tcpserver` 远程 dump（§3.3，不需进容器），
classfiles 则直接由 agent 在内存里缓存原始字节、经同一条 tcp 通道流回 cli。

```bash
# 1) 挂 agent（output=tcpserver，classcache 默认开；注意 includes 不能用逗号分隔多个值，见 §8）
java -javaagent:/abs/xiaoxiao-jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,includes=com.foo.* -jar your-app.jar

# 2) 远程拉 exec（与容器无关，只需网络通 6300）
java -jar xiaoxiao-jacoco-cli.jar dump --address 172.xx.xx.10 --port 6300 --destfile coverage/coverage-1.exec

# 3) 远程拉 classfiles（同一通道，免 scp / 免镜像）
java -jar xiaoxiao-jacoco-cli.jar dumpclasses --address 172.xx.xx.10 --port 6300 --outdir libs --zip libs.zip

# 4) 本地出报告
java -jar xiaoxiao-jacoco-cli.jar report coverage/coverage-1.exec --classfiles libs --html reports
```

适用前提：被测机 agent 是 `xiaoxiao-jacoco-agent` 且 `classcache` 未关（默认开）。
若 classfiles 版本与运行实例不一致，报告会**静默**把该类覆盖归零（见 §7 限制），务必保证拉的是当前运行版本的字节码。

---

## 五、跨构建增量：保留未改接口的覆盖率（方法级携带）

默认按 `classId` 合并，**只能在「类的字节码没变」时**跨构建保留覆盖。若两个接口在同一个类里、
其中一个被改（如 `WebController` 的 `/login` 与 `/query`，改了 `/query`），整个类重编译、
`classId` 变了 → 旧 build 里 `/login` 的探针在新 build 报告里被静默丢弃。

解决：用**方法级 hash 携带**（不依赖 git），并在**探针层**做增量注入，最终报告仍是
**与官方 `jacococli report` 完全一致的标准 JaCoCo 报告**。

```bash
# 第 1 步：Build 0001 跑完，采集基线（JSON）
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage \
     --classfiles /path/to/build1-classes --baseline-out baseline-0001.json

# 第 2 步：Build 0002 部署后只跑了部分接口，生成「携带合并」标准报告
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage \
     --classfiles /path/to/build2-classes --sourcefiles /path/to/build2-src \
     --baseline baseline-0001.json --html reports
```

思路：
1. Build N 跑完采基线：每个方法的源码 hash（`MethodHasher`，排除行号等调试属性）映射到「哪些行被覆盖」。
2. Build N+1 跑部分接口后，离线用 JaCoCo 插桩原始 class + ASM 扫描出「方法→探针→行」映射，
   对 `className#methodHash` 命中的方法，按**相对行偏移**把基线覆盖行的探针置 `true`，
   再喂回 JaCoCo 原生 `HTMLFormatter` → 未改方法的覆盖显示为绿色 `covered`。

只携带 build N **真实覆盖**的行，不会伪造覆盖。基线按 `className#methodHash` 索引，
**同一类内**方法体未变才携带，避免不同类里同体方法（`toString`/`equals`/getter、空构造等）
因 methodHash 碰撞而跨类误携带。

---
