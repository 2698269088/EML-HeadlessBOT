# EML-HeadlessBOT

无头（headless）Minecraft 26.1.2 机器人客户端
## 它是什么

一个没有窗口、不加载渲染/声音/字体系统的完整协议客户端：能登录服务器、进世界、收发聊天与命令、攻击/挖掘/丢弃物品、维护真实方块实体与容器状态。JFR 实测**回收后活堆约 150 MB** （甚至更低）；`botinfo` 的 `used` 会更高，因为大堆 + 无内存压力时 G1 不主动回收垃圾，所以要用它的 `live heap` 行比较，而不是 `used`。

实现路线是把改动做成**运行期字节码补丁**，而不是维护一棵反编译源码树：

- 编译期依赖一个由 `eml.engine.OpenJarTool` 生成的"放宽访问标志"副本 jar（`libs/minecraft-<版本>-open.jar`，构建时自动生成），让 donor 模板可以书写对私有/最终成员的访问；
- 运行期加载的是**未经修改的官方 jar**；私有成员之所以合法，是因为 donor 方法体被内联进目标类自身（成为其 nestmate），而不是因为访问标志真的被改过；
- 补丁由 `-javaagent` 在类加载时施加，`eml.template` 下的 Java 方法就是补丁内容——它们经过 javac 类型检查，不是手写指令。

| 命令行 | 说明 |
| --- | --- |
| `--username <name>` | 用户名（省略则交互询问，并写入 `<gameDir>/bot.yml`） |
| `--server <host> --port <n>` | 启动后自动连接 |
| `--online` | 正版验证模式 |
| `--viewDistance auto\|<n>` | 上报给服务器的视距（区块半径）。`auto`（默认）以服务器为准：登录包里的服务器视距会被直接采用；写数字 2–32 则作为上限（服务器仍可再压） |
| `--gameDir <dir>` | 运行目录（`plugins/`、`logs/`、`versions/`、`libraries/`、`lang/`、`debug/` 都在这里；**不要**在 `build/` 里跑，`gradlew clean` 会连配置一起删） |

首次启动的自举（`eml.bootstrap.Bootstrap`）会自动完成：创建 `plugins/` 等目录；若 `versions/<版本>/<版本>.jar` 缺失则从 `piston-meta.mojang.com` 按版本清单下载**并校验 SHA-1**（库的适用性按清单自身的 `rules` 判定；跳过 LWJGL/natives）；然后带 `-javaagent` 重启自己（父进程继承退出码，避免无终端环境下 JVM 提前退出）。全过程只读写 gameDir。

## 构建

Gradle（wrapper 已随仓库提供，**无需预装 Gradle**）。在仓库根目录执行：

```
gradlew build
```

产物：`build/libs/EML-HeadlessBOT-<版本>.jar`

| 任务 | 作用 |
| --- | --- |
| `generateOpenJar` | 用 `eml.engine.OpenJarTool` 从官方 jar 生成 `libs/minecraft-<版本>-open.jar`（仅编译期的放宽访问视图，方法体被丢弃，从不执行） |
| `compileJava` | 依赖上一步；放宽视图作为文件依赖进入 javac classpath |
| `fatJar` | 自身类 + 运行时库；客户端 jar 永不进入产物 |

**干净 checkout 不需要任何预置文件**：`versions/` 本体缺失时由生成器自行下载，`libs/` 完全由构建产生。首次构建只有 Gradle 发行版和依赖需要联网。

顺序靠任务依赖保证：`Maven` 那套“依赖解析先于生命周期”的限制在 Gradle 里不存在，所以单模块即可完成“先生成、再编译”，不需要拆模块。

依赖与 Gradle 发行版都使用**官方源**（Maven Central、`services.gradle.org`）：本仓库同时由 JitPack 构建，境外访问国内镜像比官方源更慢。`com.mojang:*` 只向 Mojang 官方仓库检索。

强制重新生成放宽视图：`gradlew generateOpenJar -Popenjar.force=true`

## 配置：`<gameDir>/bot.yml`

```yaml
username: Test
server: 127.0.0.1
port: 25565
password: ""             # 进服密码，供 AutoLogin 规则以 {{password}} 引用（明文保存）
online: false
viewDistance: auto        # 视距（区块半径）：auto = 以服务器为准（默认）；数字 2-32 = 上限
language: zh_cn
autoRespawn: false        # 死亡后自动复活；false 时用 respawn 命令
autoReconnect: false      # 非主动断线后自动重连
reconnectDelaySeconds: 5  # 首次重试前的等待
reconnectAttempts: 0      # 0 = 无限重试
debug:
  enabled: false          # 总开关
  packets: false          # 每个入站包类名
  connect: true           # 断线原因 + 调用者帧
  errors: true            # 管道异常完整栈
  ticks: false            # 心跳：tick/TPS/连接阶段/维度/玩家
```

## 自动登录：`AutoLogin`

内置功能（不需要插件 jar）。规则写在 `<gameDir>/autologin.yml`（首次启动自动从内置模板生成），密码来自 `bot.yml` 的 `password` 字段，规则中可用 `{{password}}` 引用。它是「规则-动作」引擎：命中 `when` 条件就按顺序执行 `actions`，风格对齐 XinConnect。

```yaml
enabled: true
debug: false
credentials:
  password: ""            # 留空 = 使用 bot.yml 的 password
  email: ""
variables: {}
templates:
  - authme                # 内置模板：authme / register-then-login / join-login-fallback
rules: []                 # 自定义规则，与模板合并后按 priority 升序评估
servers: {}               # 可选：按服务器地址选用不同的模板和规则
```

需要按服务器区分登录流程时，在 `servers` 下以主机名或 `主机:端口` 为键：

```yaml
servers:
  "hub.example.com:25565":
    templates: [authme]
  "play.example.com":
    templates: [register-then-login]
    rules: []
```

主机名不区分大小写；只写主机名时匹配该主机的任意端口，写 `主机:端口` 时只匹配该端口。按配置顺序取第一个匹配项；匹配项的 `templates/rules` **替换**顶层配置，未匹配的服务器仍使用顶层配置。各服务器共用 `bot.yml` 的密码，也可分别覆盖 `variables`、`enabled` 和 `debug`。规则中还可使用 `{{host}}`、`{{port}}`、`{{server}}` 变量。

预配置已包含 `2b2t.xin` 的 XinMetaPlugin 兼容流程：标题含“注册”时执行 `reg {{password}} {{password}}`，含“登陆”时执行 `l {{password}}`，出现“登陆成功”后进入 `LOGGED_IN` 阶段，使用快捷栏第 3 格的指南针，并在进服菜单中点击名称含 `Game/戏/队/入` 的物品。此类规则使用 `TITLE` 信号；动作栏仍使用 `OVERLAY` 信号。

条件（`when`）支持 `types / contains / notContains / equals / startsWith / regex / stage / notStage / sender / overlay / ignoreCase` 以及 `all / any / not` 组合；`regex` 捕获组作为 `{{g1}}` 等变量。动作支持 `command / chat / stage / setVariable / log / selectSlot / useItem / closeContainer / containerButton / containerClick`；字符串字段支持 `{{变量}}` 插值。`WINDOW_OPEN` 提供容器标题，`WINDOW_SLOT` 提供物品名称及 `{{containerId}}`、`{{slot}}` 变量；`containerClick` 的槽位可写成 `slot: "{{slot}}"`。规则修饰：`priority`（小优先）、`once`、`cooldownMs`、`stopOnMatch`。

控制台命令：

| 命令 | 说明 |
| --- | --- |
| `autologin status` | 显示 enabled/debug/当前阶段/规则数 |
| `autologin reload` | 重新读取 autologin.yml |
| `autologin templates` | 列出内置模板 |
| `autologin debug [on\|off]` | 切换调试模式；省略参数时取反，显示信号、命中规则和实际动作 |
| `autologin try <文本>` | 模拟一条系统聊天信号，便于调试规则匹配 |

调试模式可通过 `debug: true` 默认开启，也可在控制台临时切换；切换后重载配置仍保持当前选择。动作日志中的密码会显示为 `******`。

注意：YAML 里写含反斜杠的正则时建议用单引号，例如 `regex: '(?i)position[: ]+(\d+).*queue'`，避免双引号下 `\d` 被当作转义。

## 启动参数：`bot.yml` 的 `jvm` 块

同一个 `bot.yml` 里，`jvm:` 是给**运行 bot 的那个 JVM** 的参数；默认是这两项：

```yaml
jvm:
  - "-Xmx384m"
  - "-XX:+UseCompactObjectHeaders"
```

- 一个参数一行（YAML 列表）；也接受写成一行 `jvm: "-Xmx1g -XX:+UseSerialGC"`（按空格拆开）。
- 这些参数**追加**在 bootstrap 自己需要的参数之后（`-javaagent`、`-Deml.gameDir`、`-Djdk.rc.repository.path` …）。JVM 对重复项按"后者生效"处理，所以在这里写 `-Xmx1g` 就是**覆盖**默认值，而不是叠加。
- 两项默认值的依据（本机实测，空闲未连服务器）：`-Xmx512m` 的老默认是活堆 95 MB / RSS 384 MB；`-Xmx384m` + 8 字节对象头（JDK 25 的 `UseCompactObjectHeaders`，约 214 万个存活对象）后是活堆 **86 MB** / RSS **340 MB**。进世界后活堆约 170 MB（JFR 实测，见上），384 MB 把余量留给服务器的视距。
- 默认**不指定垃圾收集器**：同一个 384 MB 堆下，G1（JVM 默认）实测 340 MB RSS，`-XX:+UseSerialGC` 是 382 MB——单线程收集器一上来就把年轻代按堆的 1/3 提交，空闲 bot 根本用不到，停顿也更长。只有把堆压到 256 MB 时 SerialGC 才略优（336 MB），那是"视距写死"之后才值得的选择。要换收集器就改这一块，但注意**一个 JVM 只能有一个收集器**：块里和 `-Deml.jvmArgs` 各写一个会直接 `Multiple garbage collectors selected` 起不来。
- 整个块缺失时用上面这两项；块存在但没有任何堆上限（`-Xmx` / `-XX:MaxHeapSize` / `-XX:MaxRAMPercentage` / `-XX:MaxRAMFraction`）时补 `-Xmx384m`；写了就以文件为准——对象头那一项**不会**被强行塞进你自己写的块。
- `-cp` / `-classpath` / `--class-path` / `-p` / `--module-path` / `-m` / `--module` / `-jar` 会被拒绝——它们会顶掉 bootstrap 拼好的 classpath。块内容非法（不是 `-` 开头、命中上面这些）时**整个块被忽略**并回退到上面两项默认值，同时打出带下标的错误，进程照常启动。
- 改完下次启动生效。bot 在连接时会把整个 `bot.yml` 重写一遍，`jvm:` 块会原样写回（默认值也会显式写出来，所以文件里看到的就是实际生效的参数）。
- 单次运行可以用 `-Deml.jvmArgs="-Xmx1g -XX:+UseSerialGC"` 覆盖（排在 `jvm:` 之后，同样后者生效）。
- 只在 bootstrap 自己拉起子进程时生效。如果启动方式已经让 Mojang 运行时在 classpath 上（IDE 运行、自己写 `java -cp ... top.mcocet.hlbot.EML`），这个块不会被应用，日志里会写明。
- 早先的独立文件 `<gameDir>/jvm.args` 已不再读取；如果它还在，启动时会打一条警告提示把内容搬进 `jvm:` 块。

## 自动寻路：`goto`

内置控制台命令，实现在 `top.mcocet.hlbot.nav` 包（**不属于插件系统**，不需要启用任何插件）：

| 命令 | 作用 |
| --- | --- |
| `goto <x> <y> <z>` | 走到指定格子；`~` / `~n` 是相对当前坐标 |
| `goto <x> <z>` | 只给水平坐标：在该列上下找最近的可站立地面 |
| `goto fly <x> <y> <z>` | 鞘翅滑翔到目标点（胸甲要有鞘翅；有烟花会持续加速） |
| `goto ride <x> <z>` | 骑上最近的船/矿车/马开到目标点，然后下车 |
| `goto stop` | 停下并松开所有按键 |
| `goto status` | 当前模式 / 进度 / 已用 tick / 重算次数 |
| `goto ... nodig` | 只绕路不挖方块 |

**规划**：在 `BotWorld` 的地形缓存上跑网格 A*（`Pathfinder`）。移动集合是键盘真能做到的全部动作——同层直走/斜走（斜走要求两个正交邻格都空，避免切角）、上一格（跳）、**游泳**（水中任意方向，含上下浮沉）、**攀爬**（梯子/藤蔓/脚手架上下）、下落（陆地 1–3 格不摔伤，**落点是水则允许更深**）、**挖横行**、**挖竖井**（只在下方是干、实、可站的地面时）。可挖什么由手上的工具决定（`BotControls.digTicks` 按物品的破坏速度算 tick 数），所以带镐子时石头算普通一步，空手时不会选它。危险方块（岩浆/火/仙人掌/蛛网…）禁行，**没加载的区块一律视为不可通行**。

**执行**：每 tick 用 `BotControls` 按原版按键（前进/后退/左/右/跳/疾跑/潜行）并用 `aimAt` 转身，服务端看到的仍是正常玩家移动，不发送任何自造包。跟随逻辑的规则是从 [Baritone](https://github.com/cabaletta/baritone) 的移动类里简化移植的**行为**（没有复制代码）：

- **判达看格子，不看精确落点**（`validPositions`）：人在目标格里、或跳到旁边台阶顶上，都算到了；冲刺越过的 1–3 格直接跳过。
- **台阶晚点跳、对齐了再跳**（`MovementAscend`）：对齐、距离 1.2 格内、头顶有空间时才按跳。
- **冲刺要看落点**（`MovementTraverse`）：直线、饱食度 > 6、越过下一格之后仍有地面才加速。
- **水里按需要上浮**：低于路点高度才按跳，同时按住疾跑（原版的游泳状态靠疾跑键维持）；梯子上则按跳上升、按潜行下降。
- **每 tick 清掉创造飞行**（`Movement.update()`）：飞行状态下跳跃键是"上升"，不清就会一路飞走（实测飞到 y=186 后所有目标都"没有可站立地面"）。空中发来的 `goto` 会先落地，再按地面高度重新解析目标。
- **地形会变**：每 2 秒丢弃一次地形缓存；每个路点独立预算（走 60 tick / 挖 220 tick）；失效或超时就重算——重算给当前格加代价而不是拉黑（`PathExecutor` 的 backoff），最多 4 次。

**两种出行模式**（不参与网格规划，是"定向航行"）：

- `fly`：起跳后在空中再按一次跳跃张开鞘翅 → 爬升到巡航高度（目标上方 30 格）→ 朝目标滑翔，低于巡航高度就抬头、过高就低头、速度不足或掉高就放烟花 → 距目标 45 格内转入俯冲，落地或入水即结束。
- `ride`：就近 8 格找船/矿车/马，走过去右键骑上 → 朝目标开（船用左右键转向，因为船的朝向不吃玩家视角；马用视角）→ 距目标 3 格内按潜行下车。

**失败处理**：掉线、死亡、整体超时、重算次数用尽、一直不落地、没有鞘翅、附近没有载具、骑不上载具，都会结束行程并在控制台说明原因。tick 内抛异常只中止行程，不会掀掉客户端 tick 循环。

**已知边界（有意为之）**：不搭桥、不跨维度、不做 parkour 跳跃、不主动绕开虚空；`fly` 是定向滑翔而不是地形感知的飞行规划（长距离/复杂地形可能撞山）；`ride` 只处理船/矿车/马三类、且必须已经在附近。单次规划上限 8000 个节点、96 格水平半径、垂直 ±12 格。

### 物品选择

挖掘和载具不再是"手上有什么用什么"：

- **工具**：`digTicks` 按**整个背包**（0–35 槽）里最好的工具估算耗时，并计入"工具不对要 5 倍时间"，所以规划阶段就不会选"空手挖石头"；真正开挖时 `breakStep` 先调 `ensureToolFor`：热键栏里有更好的就用它，没有就把背包里最合适的工具用**原版数字键换槽**换下来（走的是 `handleContainerInput` 的 SWAP，服务端自己执行），并把快坏的（剩 1 点耐久）工具视为没有。评分是 `getDestroySpeed`，对"正确的工具"再加一分，避免拿剑去砍石头这类等价速度的误判。
- **载具**：`mountPreferred` 不再只挑最近的，而是按行程需要打分——`ride` 会先用网格规划算一遍路线，**游泳步数 > 2 就认为这是一次涉水行程**，此时船/木筏加分、其它类型加 6 格距离惩罚；陆地上马优先（比矿车快），泡在水里的载具额外惩罚。打分只为选出"类型"，最后仍然是就近上那一个。

### 插件 API

新增能力同样开放给插件（都不需要 import 任何 `net.minecraft` 类型）：

```java
api.navigation().walk(x, y, z);          // 走（可挖），另有 walk(x,y,z,dig)、fly(...)、ride(...)、stop()
api.navigation().status();               // "walk 12/42 to 100, 64, 200 (120 ticks, 0 replan)"
api.navigation().mode();                 // idle / waiting / walk / fly / ride
api.controls().setLeft/setRight/setBack(true);   // 原先只有前进/跳/疾跑/潜行
api.controls().digTicks(x, y, z);        // 全背包最好的工具挖这格要几 tick（含不对工具的 5 倍惩罚），-1 = 挖不动
api.controls().bestToolSlot(x, y, z);    // 热键栏里最合适的槽位，-1 = 空手最好
api.controls().selectBestTool(x, y, z);
api.controls().ensureToolFor(x, y, z);   // 必要时从背包换下来并切到手上
api.controls().vehicles(8);              // List<VehicleChoice(type, distance, waterborne)，最近的在前
api.controls().mountPreferred(8, preferBoat);    // 按类型偏好上载具
api.controls().mountNearest(8);          // 纯就近
api.controls().useItem();                // 右键当前手持（滑翔中放烟花）
api.controls().clearFlight();            // 关掉创造飞行
api.world().isWater / isClimbable / isHazard / isStandable(x, y, z);
api.self().gliding() / .flying() / .vehicle() / .foodLevel() / .elytraEquipped() / .fireworkSlot();
```

`navigation()` 的每个调用只入队，导航器在下一个 tick 的客户端线程上执行，所以插件从事件回调、定时任务、甚至自己的线程调用都安全；新请求会替换进行中的行程。

架构上，`top.mcocet.hlbot.nav` 里**没有任何 `net.minecraft` 引用**：它只吃 `BotPlayer` 快照、`BotWorld`/`BotControls` 原语和一个消息 key 接口，屏幕输出、按键、实体交互都由 `eml.action.*` 那一层代为完成。
