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

`reports/<key>/index.html` 每个 key 一份，格式与官方 `jacococli report` 完全一致。
加 `--merge` 额外出一份全员并集 `reports/all/index.html`。

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
| `jmx=true\|false` | 注册 `org.jacoco:type=Runtime` MBean，支持运行时 dump/reset | `false` |

未知参数**只打印告警不致命**（官方会对未知 key 直接 FATAL）。

> ⚠️ **头号坑：`includes` 是全匹配，不是前缀匹配。**
> JaCoCo 的 `WildcardMatcher` 用 `Pattern.matcher(s).matches()`，所以每个匹配项必须覆盖**整个** VM 类名
> （`com/foo/Bar` 这种斜杠形式）。因此：
>
> | 写法 | 实际效果 |
> |---|---|
> | `includes=web3Server` | ❌ 只匹配「类名**恰好**等于 `web3Server`」的类 → 几乎必然 **0 个类插桩、覆盖率全空** |
> | `includes=web3Server*` | 只匹配类名以 `web3Server` **开头**的（仅当它位于包名开头） |
> | `includes=*web3Server*` | ✅ 类名任意位置包含 `web3Server` |
> | `includes=com.remote3.web3.*` | ✅ 该包及其子包下所有类（推荐） |
>
> `includes` 匹配的是 **VM 类名**，不是 URL 路径（`/web301/testWeb3`）、不是模块名、不是包名简写。
> 写错时 agent 启动日志会直接点名并给出改法；拿不准就先 `includes=*` 跑通，再看 §3.3.1 的自检输出。

### 2.2 xiaoxiao-jacoco 扩展参数（按 key 分离）

| 参数 | 说明 | 默认 |
|---|---|---|
| `outdir=DIR` | 按 key 输出 exec 的目录 | `coverage` |
| `autokey=KEY` | 冒烟模式：所有线程合并进单一 KEY | 不启用 |
| `headerkey=NAME` | 按 HTTP 请求头 NAME 归属 | 不启用 |
| `async=true\|false` | 异步（线程池 / @Async / CompletableFuture）key 传递，见 §2.6 | `true` |
| `cleanup=24h` | 定期清理 outdir 下的旧 exec/class（`30m` / `2h` / `1d`，纯数字按小时） | 不启用 |
| `debug=true` | 打印被插桩的类名与每次请求归属明细（排障用，默认只打前 3 次归属） | `false` |

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

MBean 对象名 `org.jacoco:type=Runtime`，方法：`dump()` / `reset()` / `getVersion()` /
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

自研线程池（任务先进自己的队列、再由别的线程取出执行，绕开了 JDK 提交入口）可手动包一层：

```java
executor.execute(CoverageTracer.wrap(myTask));   // Runnable
executor.submit(CoverageTracer.wrap(myCallable)); // Callable
```

关闭后实测影响：线程池 / CompletableFuture 里的覆盖**全部丢失**（`async=false` 对照组结果为 0）。

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
| `--execdir <dir>` | 读该目录下所有 `*.exec`，按文件名里的 `<key>` 逐个出报告 |
| `--perkey` | 显式按 key 拆分（默认在 execdir 模式下自动开启） |
| `--merge` | 额外出一份所有 key 的并集报告 `all/` |
| `--baseline-out <json>` | 采集方法级基线（跨构建增量用，见 §5） |
| `--baseline <json>` | 用基线做「方法级携带」，生成增量对比报告 |

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
#        不是 URL 路径（/web301/testWeb3）、不是模块名（web3Server）、不是包名简写。
#     -> 该进程里已加载的类，包名样例：[com/remote3/web3/*]
#        建议把 agent 参数改成 includes=com/remote3/web3/*
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
java -jar xiaoxiao-jacoco-cli.jar report --execdir coverage \
     --classfiles /path/to/classes --sourcefiles /path/to/src --html reports
# -> reports/user-A/index.html   reports/user-B/index.html
```

### 4.2 长跑服务：tcpserver + 跨机抓取（本机 192.168.6.130 ← 被测机 172.xx.xx.10）

被测机上（**`address` 必须填被测机自己的 IP**，否则只绑回环，你连不上）：

```bash
# includes 必须写【VM 类名】通配，写成 web3Server（模块名/URL 片段）会一个类都匹配不到
java -javaagent:/abs/xiaoxiao-jacoco-agent.jar=outdir=coverage,includes=com.remote3.*,inclnolocationclasses=true,output=tcpserver,address=172.xx.xx.10,port=6300,headerkey=X-Coverage-Key \
     -jar web3-1.0-SNAPSHOT.jar
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
# jconsole 连上 -> org.jacoco:type=Runtime -> dump()
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
其中一个被改（如 `Web3Controller` 的 `/login` 与 `/query`，改了 `/query`），整个类重编译、
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

## 六、目录

```
xiaoxiao-jacoco/
  pom.xml                         # parent，packaging=pom，管理依赖与插件版本
  setup-m2.sh                     # 首次构建前把 lib/ 里的定制 jacoco/asm 装进 ~/.m2
  lib/                            # asm 9.10.1 + jacoco core/report 定制版
  xiaoxiao-jacoco-agent/
    pom.xml                       # 依赖 asm + org.jacoco.core（不含 report），shade 成 fat agent
    src/main/java/peruser/
      PerUserAgent.java           # premain/agentmain 入口
      Options.java                # 官方全部参数 + 扩展参数解析
      AgentArgParser.java         # 支持双引号包裹的 k=v 解析
      PerUserTransformer.java     # ClassFileTransformer：过滤 + 织入
      InstrumenterFlow.java       # 复刻 JaCoCo 内部插桩流程
      ThreadLocalProbeArrayStrategy.java / ThreadProbeStore.java
      CoverageTracer.java         # 公共 API：begin/end 一个工作单元
      RequestKeyHook.java / RequestKeyWeaver.java   # headerkey 请求头归属
      CoverageStore.java          # 按 key 的 exec 读写/合并
      IAgentOutput.java + FileOutput / NoneOutput / TcpServerOutput / TcpClientOutput / Outputs
      JmxSupport.java / JacocoRuntime.java / JacocoRuntimeMBean.java
  xiaoxiao-jacoco-cli/
    pom.xml                       # 依赖 core + report + asm，Main-Class=peruser.cli.CommandLine
    src/main/java/peruser/cli/
      CommandLine.java            # 命令分发
      CliArgs.java / CliUsageException.java
      ReportCommand.java / MergeCommand.java / DumpCommand.java
      InstrumentCommand.java / ClassInfoCommand.java / ExecInfoCommand.java / VersionCommand.java
      AnalyzePaths.java           # exec/class/source 路径扫描
      BaselineStore.java / BaselineReport.java / MethodHasher.java / MiniJson.java
```

---

## 七、适用与限制

- **目标 JVM 需 Java 8+**（两个模块都以 `--release 8` 编译，class major version 52，已在 JDK 1.8 验证可挂载）。
  若目标报 `UnsupportedClassVersionError`，说明用的是旧版 jar，重新 `mvn clean package` 即可。
- 能用「双实例双 agent」时优先官方方案（更简单、零改造）。
- 依赖 JaCoCo 0.8.15 内部包（`org.jacoco.core.internal.*`），随版本可能变动，升级需回归验证。
- **异步（线程池 / @Async / CompletableFuture）**：`async=true`（默认）已覆盖 JDK 标准提交入口，
  见 §2.6。仍不支持的是**并行流** `parallelStream`（走 `ForkJoinTask.fork`，不经过提交入口）
  与**跨进程 / 跨线程队列转交**（MQ 消费者、任务落库再由别的线程捞起）—— 这类需要业务侧
  在消费端重新 `CoverageTracer.begin(key)`。
- **类加载隔离**：被插桩类在运行时需能看见 `peruser.ThreadProbeStore`（agent jar 由 `-javaagent`
  机制加在**系统 classpath**，大多数应用 OK）。agent **只对 `peruserrt` 这个小包**调用
  `appendToBootstrapClassLoaderSearch`（用于给 JDK 线程池织入 key 传递，该包自包含、无状态，
  注入前后都只有一份）。但 `peruser.*` **故意不加** bootstrap 搜索：
  被插桩的应用类（系统 / 应用类加载器，均向上委派到系统类加载器）解析 `peruser.*` 时，与 agent 用的是
  **同一份** `ThreadProbeStore` 实例。一旦误加 bootstrap 搜索，bootstrap 里会再存在一份，应用类命中
  bootstrap 副本而 agent 读系统 classpath 副本 → 探针写进一份、读出另一份 → 覆盖率全空（5 字节空 `.exec`）。
  若目标用隔离类加载器（部分插件框架）导致应用类看不到 `peruser.*`，需另行把 agent jar 暴露给该加载器。
- **asm 冲突**：两个 fat jar 都内嵌 asm 9.10.1。若目标应用自带不同 asm 版本且在同一 classpath，可能冲突；
  把目标应用的 asm 对齐到 9.10.1 即可。
- **性能**：每个被插桩类每次方法调用多一次 `ThreadProbeStore.getProbes`，高频路径有轻微开销；
  用 `includes=` 限定只插桩关心的包可显著降低。
- **`--classfiles` 一定要用磁盘上的原始（未插桩）class**（agent 只改内存运行时类，磁盘字节不变），
  否则对不上探针 id。

---

