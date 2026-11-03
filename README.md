# EML-HeadlessBOT

无头（headless）Minecraft 26.1.2 机器人客户端 —— **v1.1.0**

一个没有窗口、不加载渲染/声音/字体系统的完整协议客户端：能登录服务器、进世界、收发聊天与命令、攻击/挖掘/丢弃物品、维护真实方块实体与容器状态，并内置自动登录、自动寻路、推广播报与珍珠按钮动作。

## 它是什么

实现路线是把改动做成**运行期字节码补丁**，而不是维护一棵反编译源码树：

- 编译期依赖一个由 `eml.engine.OpenJarTool` 生成的"放宽访问标志"副本 jar（`libs/minecraft-<版本>-open.jar`，构建时自动生成），让 donor 模板可以书写对私有/最终成员的访问；
- 运行期加载的是**未经修改的官方 jar**；私有成员之所以合法，是因为 donor 方法体被内联进目标类自身（成为其 nestmate），而不是因为访问标志真的被改过；
- 补丁由 `-javaagent` 在类加载时施加，`eml.template` 下的 Java 方法就是补丁内容——它们经过 javac 类型检查，不是手写指令。

内存占用（JFR 实测）：空闲约 **86 MB** 活堆、进世界后约 **170 MB**；默认 `-Xmx384m` + 8 字节对象头时 RSS 约 **340 MB**。`botinfo` 的 `used` 会更高，因为大堆 + 无内存压力时 G1 不主动回收垃圾，所以要用它的 `live heap` 行比较，而不是 `used`。

## 快速开始

**环境要求**：JDK 25（构建与运行都需要；`build.gradle.kts` 声明 Java 25 toolchain）、可访问 Mojang 与 Maven Central 的网络。Gradle wrapper 已随仓库提供，**无需预装 Gradle**。

```bash
# 构建
gradlew build

# 运行（首次会自举：建目录、下载本体、带 -javaagent 重启自己）
java -jar build/libs/EML-HeadlessBOT-1.1.0-26.1.2.jar
```

首次启动的自举（`eml.bootstrap.Bootstrap`）会自动完成：创建 `plugins/` 等目录；若 `versions/<版本>/<版本>.jar` 缺失则从 `piston-meta.mojang.com` 按版本清单下载**并校验 SHA-1**（库的适用性按清单自身的 `rules` 判定；跳过 LWJGL/natives）；然后带 `-javaagent` 重启自己（父进程继承退出码，避免无终端环境下 JVM 提前退出）。全过程只读写 gameDir。

### 命令行参数

| 参数 | 说明 |
| --- | --- |
| `--username <name>` | 用户名（省略则交互询问，并写入 `<gameDir>/bot.yml`） |
| `--server <host> --port <n>` | 启动后自动连接；`--address` 是 `--server` 的别名 |
| `--online <true\|false>` | 正版验证模式 |
| `--viewDistance auto\|<n>` | 上报给服务器的视距（区块半径）。`auto`（默认）以服务器为准：登录包里的服务器视距会被直接采用；写数字 2–32 则作为上限（服务器仍可再压） |
| `--language <code>` | 控制台语言：`en_us` / `zh_cn` / `zh_tw` / `ru_ru` / `ja_jp`；省略时取 `bot.yml` 的 `language`，再退到系统区域，最后 `en_us` |
| `--gameDir <dir>` | 运行目录（`plugins/`、`logs/`、`versions/`、`libraries/`、`lang/`、`debug/` 都在这里；**不要**在 `build/` 里跑，`gradlew clean` 会连配置一起删） |
| `--language` / `--defaultUser` | 后者只是交互询问用户名时的默认值 |

### 运行目录布局

自举只读写 gameDir（默认进程工作目录）：

```
<gameDir>/
├── bot.yml                 # 主配置（用户名/服务器/视距/重连/调试/JVM 参数）
├── autologin.yml           # 自动登录规则（首次由内置模板生成）
├── promo.yml               # 推广播报配置（首次由内置模板生成）
├── superpearl.yml          # 珍珠按钮坐标与停靠点（首次生成）
├── plugins/                # 外部插件 jar（每个 jar 一个 plugin.yml）
├── versions/  libraries/   # 官方本体与库（自举下载，含 Mojang 字节码）
├── logs/  debug/           # 日志与 JFR 输出
└── lang/                   # 可覆盖的语言文件
```

## 控制台命令

控制台基于 JLine：带补全、历史与高亮；输出行会打印在输入行上方。所有命令都在下方"内置功能"或"插件系统"中有对应说明。

| 命令 | 作用 |
| --- | --- |
| `help` | 列出所有已注册命令 |
| `status` | 用户、版本/协议、连接、维度、视距（本地/服务器）、玩家坐标与血量、资源包策略、聊天记录条数 |
| `botinfo` | 内存明细（含 full GC 后的活堆）、类/元空间/线程、CPU、磁盘、服务器 brand/ping/包速率、以及被"韧性守卫"跳过的包数量 |
| `connect <host[:port]>` | 连接服务器 |
| `disconnect [reason]` | 主动断开 |
| `say <message>` | 发送公屏聊天 |
| `cmd <command...>` | 发送服务器命令（不带前导 `/`） |
| `slot <0-8>` | 切换快捷栏槽位 |
| `jump` | 按一下跳跃键 |
| `sprint [on\|off]` / `sneak [on\|off]` | 持续按住疾跑/潜行 |
| `pos` / `coords` / `position` | 坐标、维度、朝向 |
| `swing [off]` | 挥手（`off` = 副手） |
| `drop [all]` | 丢弃手持物；`all` 丢整组 |
| `attack [stop]` | 开始/停止左键连续攻击 |
| `look <yaw> <pitch>` | 直接设置视角 |
| `mine <x> <y> <z>` | 挖掉指定方块 |
| `gamemode <survival\|creative\|adventure\|spectator>` | 本地切换游戏模式 |
| `respawn` | 死亡后手动复活 |
| `msgs [count]` | 最近的聊天/系统消息（默认 10 条） |
| `jfr <start\|stop>` | JFR 采样，输出到 `debug/*.jfr` |
| `plugins [reload\|enable\|disable\|load <jar>\|unload <name>]` | 插件列表与生命周期管理 |
| `goto ...` | 内置寻路（见下） |
| `sp ...` | 珍珠按钮动作（见下） |
| `autologin ...` | 自动登录（见下） |
| `promo ...` | 推广播报（见下，需 `promo.yml` 的 `enabled: true`） |
| `quit` / `end` / `stop` | 退出机器人 |

## 配置：`<gameDir>/bot.yml`

首次启动时交互询问用户名并逐项写回；此后每次连接都会重写整份文件（包含注释），所以文件里看到的就是实际生效的值。命令行参数优先于文件。

```yaml
username: Test
server: 127.0.0.1
port: 25565
password: ""              # 进服密码，供 AutoLogin 以 {{password}} 引用（明文保存）
online: false             # false = 离线模式（用户名派生稳定 UUID）
viewDistance: auto        # auto = 以服务器为准（默认）；数字 2-32 = 上限
language: zh_cn
autoRespawn: false        # 死亡后自动复活；false 时用 respawn 命令
autoJump: false           # 原版自动跳跃；默认关闭，避免与脚本寻路打架
pingBeforeConnect: true   # 登录前先做状态 ping
pingTimeoutSeconds: 5
autoReconnect: true       # 非主动断线后自动重连
reconnectDelaySeconds: 5  # 首次重试前的等待
reconnectAttempts: 0      # 0 = 无限重试
debug:
  enabled: false          # 总开关
  packets: false          # 每个入站包类名
  connect: true           # 断线原因 + 调用者帧
  errors: true            # 管道异常完整栈
  ticks: false            # 心跳：tick/TPS/连接阶段/维度/玩家
plugins:
  disableOnError: true    # 插件抛异常即停用，而不是留在半死状态
  failThreshold: 1        # 累计失败多少次后停用（1 = 立即）
resourcePacks: accept     # accept = 一律应答"已接受/已加载"（无头端不解包）；decline = 拒绝可选包
jvm:
  - "-Xmx384m"
  - "-XX:+UseCompactObjectHeaders"
```

调试开关也可用 `-Deml.debug.<name>=true` 单次覆盖。`status` 里的 `view dist` 会同时显示本地请求值与服务器实际值。

## 内置功能

内置功能**不需要插件 jar**，也不占用 `plugins/` 目录：它们要么直接向控制台注册命令（`goto`、`sp`），要么以"内置插件"身份挂到插件 API 上但配置文件放在 gameDir 根目录（`autologin.yml`、`promo.yml`）。

### 自动登录：`AutoLogin`

规则写在 `<gameDir>/autologin.yml`（首次启动自动从内置模板生成），密码来自 `bot.yml` 的 `password`，规则中可用 `{{password}}` 引用。它是「规则-动作」引擎：命中 `when` 条件就按顺序执行 `actions`，风格对齐 XinConnect。

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

主机名不区分大小写；只写主机名时匹配该主机的任意端口，写 `主机:端口` 时只匹配该端口。按配置顺序取第一个匹配项；匹配项的 `templates/rules` **替换**顶层配置，未匹配的服务器仍使用顶层配置。各服务器共用 `bot.yml` 的密码，也可分别覆盖 `variables`、`enabled` 和 `debug`。

可用变量：`{{password}}`、`{{username}}`（= `{{bot}}`）、`{{host}}`、`{{port}}`、`{{server}}`，以及正则捕获组 `{{g1}}`…。

预配置已包含 `2b2t.xin` 的 XinMetaPlugin 兼容流程：标题含"注册"时执行 `reg {{password}} {{password}}`，含"登陆"时执行 `l {{password}}`，出现"登陆成功"后进入 `LOGGED_IN` 阶段，使用快捷栏第 3 格（`selectSlot: 2`）的物品，并在进服菜单中点击名称含 `Game/戏/队/入` 的槽位。此类规则使用 `TITLE` 信号；动作栏仍使用 `OVERLAY` 信号。

条件（`when`）支持 `types / contains / notContains / equals / startsWith / regex / stage / notStage / sender / overlay / ignoreCase` 以及 `all / any / not` 组合；`types` 可取 `SYSTEM_CHAT / PUBLIC_CHAT / OVERLAY / TITLE / SERVER_CHANGE / DISCONNECT / WINDOW_OPEN / WINDOW_SLOT`。动作支持 `command / chat / stage / setVariable / log / selectSlot / useItem / closeContainer / containerButton / containerClick`；字符串字段支持 `{{变量}}` 插值。`WINDOW_OPEN` 提供容器标题，`WINDOW_SLOT` 提供物品名称及 `{{containerId}}`、`{{slot}}` 变量；`containerClick` 的槽位可写成 `slot: "{{slot}}"`。规则修饰：`priority`（小优先）、`once`、`cooldownMs`、`stopOnMatch`。

控制台命令：

| 命令 | 说明 |
| --- | --- |
| `autologin status` | 显示 enabled/debug/当前阶段/规则数/当前服务器档案/文件 |
| `autologin reload` | 重新读取 autologin.yml |
| `autologin templates` | 列出内置模板 |
| `autologin debug [on\|off]` | 切换调试模式；省略参数时取反，显示信号、命中规则和实际动作 |
| `autologin try <文本>` | 模拟一条系统聊天信号，便于调试规则匹配 |

调试模式可通过 `debug: true` 默认开启，也可在控制台临时切换；切换后重载配置仍保持当前选择。动作日志中的密码会显示为 `******`。YAML 里写含反斜杠的正则时建议用单引号，例如 `regex: '(?i)position[: ]+(\d+).*queue'`。

### 推广播报：`promo`（EMLPga）

从 EMLPga 插件移植而来的内置定时播报，配置在 `<gameDir>/promo.yml`（首次启动从 jar 内模板生成）。整条链路是**一个 tick 驱动的状态机**（`AdCampaign`）：每 4 tick 询问一次"有没有到点的事"，没有线程、没有 sleep 循环，`stop` 在下一个 pump 即生效；断线时轮次既不发送也不消耗，而是停在原地等下一次检查。

**结构**

| 组件 | 职责 |
| --- | --- |
| `PgaSettings` | 唯一的配置键表；缺键/坏值一律退回默认值而不是抛异常 |
| `PlayerRoster` | 在线名单、黑名单过滤、轮询游标、进服检测 |
| `AdCampaign` | 轮次/间隔/抖动时钟，文本合成与投递 |
| `BurstSender` | 与私聊轮次并行的公屏高频循环（只在 private 模式下工作） |
| `Greeter` | 新玩家出现在 tab 列表时的欢迎语（每秒检查一次） |
| `EpaCommand` | `promo` 子命令表；帮助文本由表生成 |

**两种投递模式**

- `public`：每轮把整份 `messages` 依次发到公屏（`randomOne: true` 时每轮只随机一条）；
- `private`：每轮从"在线且不在黑名单"的玩家里轮询取一人，用服务器自己的 `msg <player> <text>` 私聊；没有人可发时**不消耗轮次**，只把下一次检查推后一个间隔。

`burst` 是第二条节奏更快的公屏循环，用于"主模式在私聊时保持公屏活跃"：`durationSeconds` 内每 `intervalSeconds` 发一条，然后暂停 `checkSeconds` 再开下一个窗口。`syncWithMain: true` 时直接镜像 `messages`。

**文本管线**（`AdText`，顺序固定）：占位符 → 数学粗体数字（可选）→ 随机后缀（可选）。

- 占位符：`#name#`（私聊对象/新玩家名）、`#bot#`（自己）、`#players#`（在线人数）、`#time#`（发送时刻 `HH:mm`）；
- `text.mathFont.enabled` 把连续 ≥ `minDigits` 的数字替换为数学粗体（U+1D7CE 起），很多服务器字体缺这个区块会显示方块，所以默认关闭；`burstMathFont` 单独控制 burst；
- `text.suffix.enabled` 追加 `length` 位随机尾（字母表剔除 `0/O/1/l/I`），用于绕过"重复文本被静音"；另有 `skipRepeats: true` 防止连续两条完全相同。

**配置键**

```yaml
enabled: true              # false 时 promo 命令根本不会注册
language: zh_cn            # zh_cn / en_us；缺键回退 zh_cn，再回退键名
schedule:
  publicIntervalSeconds: 40
  privateIntervalSeconds: 10
  mode: public             # public | private
  gapSeconds: 4            # 同一轮内两条之间的间隔
  jitterSeconds: 5         # 轮间隔抖动 [base-j, base+j]，0 = 关闭
  gapJitterSeconds: 3
  randomOne: false
  skipRepeats: true
text:
  mathFont: { enabled: false, burstMathFont: false, minDigits: 5 }
  suffix: { enabled: true, length: 4 }
greeting:
  enabled: false
  format: "hi，#name#"
burst:
  enabled: false
  intervalSeconds: 2
  durationSeconds: 30
  checkSeconds: 5
  syncWithMain: false
messages: []               # 主列表；burstMessages 为空时回退到这里
burstMessages: []
blacklist: []
```

**命令**（`promo` 与旧的 `promo epa` 前缀都接受）

| 分组 | 命令 |
| --- | --- |
| 总览 | `promo help` · `promo status` · `promo lang` |
| campaign | `promo start` · `promo stop` · `promo forcestop`（同时把 `enabled` 写回 false） · `promo reload` |
| 文案 | `promo list` · `promo add <text>` · `promo remove <index\|text>` · `promo set <index> <text>` · `promo clear` |
| 节奏 | `promo mode <public\|private>` · `promo interval <sec>` · `promo publicinterval <sec>` · `promo privateinterval <sec>` · `promo gap <sec>` · `promo jitter <sec>` · `promo gapjitter <sec>` · `promo random <on\|off>` · `promo skiprepeats <on\|off>` |
| 文本 | `promo suffix <on\|off>` · `promo suffixlen <n>` · `promo mathfont <on\|off>` · `promo mathmin <digits>` |
| 欢迎 | `promo greeting <on\|off>` · `promo greetingformat <format>` |
| 手动发送 | `promo say <index>` · `promo pm <player> [index]` · `promo players` |
| 名单 | `promo blacklist <add\|remove\|list> [name]` |
| burst | `promo burst <start\|stop\|status>` · `promo burstlist` · `promo burstadd <text>` · `promo burstremove <index\|text>` · `promo burstset <index> <text>` · `promo sync <on\|off>` · `promo burstinterval <sec>` · `promo burstduration <sec>` |

所有会改配置的子命令都会立即落盘；`promo reload` 会重新读文件并重建语言表与命令表（否则 `help` 仍显示旧语言）。手动发送与定时发送走同一个文本管线，因此占位符、粗体、后缀规则完全一致。`burst start` 只有在 `mode: private` 且主 campaign 正在运行时才会被接受，否则给出原因。

### 自动寻路：`goto`

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

### 珍珠按钮：`sp`（SuperPearl）

走到预设的珍珠按钮前按下，让卡住的末影珍珠把等待中的玩家送回基地，然后走回停靠点。配置在 `<gameDir>/superpearl.yml`（首次运行自动生成，含字段说明注释；用 `sp set` / `sp del` / `sp return` 改动时会连注释一起写回）。

```yaml
enabled: false
return: "0, 64, 0"        # 按完之后停靠的点；省略 = 按完即结束
buttons:
  base: "10, 64, 20"      # 名字: x, y, z[, face]
  wall: "11, 64, 20, WEST"
```

`face` 是 `UP/DOWN/NORTH/SOUTH/WEST/EAST`，省略（或写 `auto`）时从方块状态自己读出按钮朝向，所以地板按钮、天花板按钮、墙面按钮都不需要人工指定。

| 命令 | 作用 |
| --- | --- |
| `sp back [name]` | 执行一次（重读配置后入队）；`name` 省略时：只有一个按钮就用它，否则用名为 `base` 的那个 |
| `sp set <name> <x> <y> <z> [face\|auto]` | 新增/覆盖按钮 |
| `sp list` / `sp del <name>` | 查看/删除按钮 |
| `sp return [<x> <y> <z>\|off]` | 查看、设置或清除停靠点 |
| `sp on` / `sp off` | 总开关（关闭时命令仍可用，只是拒绝 `sp back`） |
| `sp status` | enabled、阶段、目标、候选格进度、已用 tick、按钮数、停靠点 |
| `sp stop` | 中止当前动作并松开按键 |
| `sp reload` | 重读 superpearl.yml |
| `sp help` | 逐条列出子命令 |

**行为要点**

- 线程：`sp` 在控制台线程只碰配置与请求队列；选点、点击、行走都在客户端线程的 `tick` 里做，因此不会读到半应用的方块状态，也不需要额外加锁。
- 选点（`standingSpots`）：在按钮周围 ±3×±2 格内找可站立格，要求眼到按钮的**实际瞄准点**在 4.0 格可达范围内（排除贴脸 <1.0 格、并留 0.35 格余量），且该格到按钮**有视线**；候选按离身体的距离排序，一个走不到就换下一个。
- 确认（`pressing`）：点击后观察按钮的 `pressed` 状态——这是客户端唯一能证明"点击真的到服务器"的信号。只有点击**从未生效**才重试，最多 3 次；按钮原本就被红石持续供能时（看不到反应）直接视为完成，不重复点。
- 收尾：`afterPress` 若无停靠点就此结束；有则请求一次导航，走到 1.5 格内算到位，导航中途失败会给出原因。
- 保底：单一动作（同一时间只允许一个 `sp back`）、每次请求都重读配置、30 分钟看门狗（不是预算）、死亡/无世界/未加载区块都会中止并说明。

## 插件系统

插件是从 `<gameDir>/plugins/` 加载的 jar，模型对齐 Bukkit：`plugin.yml` + `Plugin` 子类 + 注解式事件 + 生命周期。

### plugin.yml

```yaml
name: MyPlugin            # 必填，唯一
main: com.example.myplugin.MyPlugin   # 必填，Plugin 子类，公开无参构造
version: 1.0.0            # 可选，默认 1.0.0
description: "..."
apiVersion: "1.0"         # 可选；主版本与 BotPlugins.API_VERSION 不符时拒绝加载
author: someone           # 或 authors: [a, b]
depend: [OtherPlugin]     # 硬依赖：先于本插件加载，缺失则报错
softdepend: [MaybePlugin] # 软依赖：存在则优先加载
```

同名插件只会加载第一个，重复的会在日志里点名。jar 内其余资源可自由使用；`dataFolder` 指向 `plugins/<name>/`。

### 生命周期

| 阶段 | 时机 | 该做什么 |
| --- | --- | --- |
| `onLoad()` | 启动扫描后、仅 LOADED | 读配置、准备对象；**不要**碰游戏状态 |
| `onEnable()` | 客户端进入世界（连接建立） | 注册监听器与控制台命令、启动定时任务 |
| `onDisable()` | 每次断线、`plugins disable`、卸载前 | 撤销后台工作；监听器/命令/任务由框架自动收回 |
| `onUnload()` | 重载或关闭时（`onDisable` 之后） | 释放静态缓存；类加载器随后关闭 |

插件在断线后**仍保持已加载**，重连会再次 `onEnable()`，所以定时器句柄在重连后要重新创建（框架会把旧句柄标为 cancelled，但引用仍非 null）。

### 事件

```java
public final class MyListener implements Listener {
   @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
   public void onChat(ChatMessageEvent event) {
      if (event.message().startsWith("!")) {
         event.setCancelled(true);
      }
   }
}
// onEnable 中：this.getApi().registerEvents(new MyListener(), this);
```

`@EventHandler` 要求方法 public、返回 void、恰好一个具体事件参数；`priority` 取 `LOWEST…MONITOR`（`MONITOR` 只观察）。可取消事件实现 `Cancellable`。分发是同步的、按优先级顺序，在触发线程（通常是客户端线程）上执行。

按包分布的事件：

| 包 | 事件 |
| --- | --- |
| `events.chat` | `ChatMessageEvent`、`SendChatEvent`、`SendCommandEvent` |
| `events.connection` | `LoginSuccessEvent`、`DisconnectEvent`、`RespawnEvent` |
| `events.player` | `PlayerJoinedEvent`、`PlayerLeftEvent`、`PlayerDeathEvent`、`HealthChangedEvent`、`ExperienceChangedEvent`、`HeldItemChangedEvent`、`ContainerOpenedEvent`、`ContainerClosedEvent`、`ContainerSlotUpdatedEvent` |
| `events.level` | `BlockUpdatedEvent`、`ChunkLoadedEvent`、`ChunkUnloadedEvent`、`ExplosionEvent` |
| `events.entity` | `EntityJoinLevelEvent`、`EntityLeaveLevelEvent`、`MobEffectAddedEvent`、`MobEffectRemovedEvent` |
| `events.recipe` | `RecipesUpdatedEvent`、`RecipeUnlockedEvent` |

### 调度器

`getScheduler()`（= `api.scheduler()`）以**客户端 tick** 计时（1 tick = 50 ms），任务归属于插件，插件禁用/卸载时自动取消：

```java
this.scheduler().run(this, task);                        // 下一 tick，客户端线程
this.scheduler().runLater(this, task, 20);               // 20 tick 后
this.scheduler().runTimer(this, task, 1, 4);             // 每 4 tick 重复
this.scheduler().runAsyncLater(this, blocking, 1);       // 工作线程；不得碰游戏状态
```

任何任务体抛异常都会被记为插件故障（见下），不会掀掉 tick 循环。

### 控制台命令

```java
public final class MyCommand implements BotCommand {
   @Override public String name() { return "mycmd"; }
   @Override public String usage() { return "mycmd <arg>"; }
   @Override public String description() { return "does something"; }
   @Override public void execute(List<String> args) { /* 打印用 api.print */ }
}
// onEnable 中：this.getApi().registerCommand(new MyCommand(), this);
```

`BotCommand` 的签名里没有任何 `net.minecraft` 类型；需要访问世界时用 `api.runOnClient(...)` 跳到客户端线程。

### 故障隔离（Paper 风格）

`bot.yml` 的 `plugins` 块控制：`onLoad/onEnable` 抛异常、事件处理器抛异常、定时任务抛异常、控制台命令抛异常，都会累计为该插件的故障；达到 `failThreshold` 且 `disableOnError: true` 时插件被停用并标记为 `FAILED`，自动的"进服全启用"会跳过它。`plugins enable <name>` 是操作者的显式重试，会清除隔离。`plugins` 列表会显示每个插件的状态与失败摘要。

### 内置功能与插件的区别

`promo` 与 `AutoLogin` 在代码上也是 `Plugin` 子类，但用 `Plugin.attach(name, configFile, api)` 直接接线：没有 jar、不进 `plugins/`、配置放在 gameDir 根目录，也不受 `plugins` 故障策略管理。`goto` / `sp` 则直接向控制台注册，连 `Plugin` 都不是。

## 插件 API 速览

`BotApi` 是所有扩展的入口（`getApi()`），不含 `net.minecraft` 类型的部分可以直接使用：

| 分类 | 方法 |
| --- | --- |
| 身份/连接 | `username()` · `isConnected()` · `isServerConnected()` · `connect(host, port)` · `disconnect(reason)` · `sendChat(msg)` · `sendCommand(cmd)` · `serverAddress()` |
| 线程 | `runOnClient(Runnable)` · `clientNow()` · `player()` · `self()`（`BotPlayer` 快照） |
| 世界 | `world()` · `blockAt(x,y,z)` · `isChunkLoaded(x,z)` · `setViewDistance(n)` · `viewDistance()` |
| 控制 | `controls()`（按键、瞄准、挖掘、放置、工具选择、载具、交互） |
| 背包 | `inventory()`（容器点击协议、堆叠移动、热键交换、按钮、创造模式发物、配方） |
| 寻路 | `navigation()`（`walk/fly/ride/stop/status/mode`，只入队，下一个 tick 在客户端线程执行） |
| 监控 | `onlinePlayers()` · `metrics()` · `consoleSince(seq, limit)` · `consoleSequence()` · `tickCount()` · `skippedPackets()` |
| 输出/路径 | `print` · `printError` · `printBlock` · `gameDirectory()` |
| 扩展点 | `registerEvents` · `registerCommand` · `scheduler()` · `plugins()` · `reloadPlugin(s)` |

`BotControls` 常用能力：`setForward/setBack/setLeft/setRight/setJump/setSprint/setSneak/releaseAll`、`aimAt`、`breakStep/cancelBreak`、`place`、`selectSlot`、`digTicks`、`bestToolSlot/selectBestTool/ensureToolFor`、`vehicles/mountNearest/mountPreferred`、`useItem`、`clearFlight`、`interactBlock`、`buttonFace/buttonPressed`、`canSee/canSeeFrom`、`aimPoint/aimPointFrom`。

`BotWorld`：`flags/hardness/isOpen/isSolid/isChunkLoaded/minY/maxY/forget/forgetAround/clear/cachedCount/isWater/isClimbable/isHazard/isStandable`。缓存跨区块卸载保留，位置变化由 `BlockUpdatedEvent` 失效。

线程契约：**事件处理器里可以直接读世界**；控制台命令、`sendChat`、自建线程都必须先 `runOnClient(...)`。`onlinePlayers()`、`metrics()`、`consoleSince()` 内部已经完成跳线程与超时回退。

## 架构

| 包 | 职责 |
| --- | --- |
| `top.mcocet.hlbot` | `EML`：jar 的 `Main-Class`，只做引导，不链接任何 Mojang 类型 |
| `eml.bootstrap` | 首次自举：目录布局、本体下载与校验、`jvm:` 参数解析（`JvmSettings`）、带 `-javaagent` 重启 |
| `eml.agent` / `eml.engine` / `eml.template` | 补丁代理、类写入与规则、以及经 javac 检查的补丁方法体 |
| `eml.action` | 原语门面：`BotPlayer`（快照）、`BotWorld`（地形缓存）、`BotControls`（按键/瞄准/挖掘/载具）、`BotInventory`（容器协议）、`BotActions` |
| `eml.hooks` | 引擎侧钩子：tick 与连接边沿、聊天历史、控制台捕获、韧性守卫（跳过渲染耦合包并计数）、资源包应答 |
| `net.minecraft.client.headless` | 会话、配置、控制台（JLine）、命令注册表、状态 ping、视距策略、分层语言 |
| `top.mcocet.hlbot.plugins` | 插件管理器、事件总线、调度器、`BotApi`、配置封装 |
| `top.mcocet.hlbot.autologin` | 规则-动作登录引擎 |
| `top.mcocet.hlbot.nav` | 网格 A* 与执行器（不含任何 `net.minecraft` 引用） |
| `top.mcocet.hlbot.promo` | 推广播报（内置插件形态） |
| `top.mcocet.hlbot.superpearl` | 珍珠按钮动作 |

`nav` 与 `superpearl` 都只依赖 `BotPlayer` 快照、`BotWorld`/`BotControls` 原语和一个消息 key 接口，屏幕输出、按键、实体交互都由 `eml.action.*` 那一层代为完成——这也是它们能脱离游戏类型单测的原因。

### 启动参数：`bot.yml` 的 `jvm` 块

`jvm:` 是给**运行 bot 的那个 JVM** 的参数；默认是这两项：

```yaml
jvm:
  - "-Xmx384m"
  - "-XX:+UseCompactObjectHeaders"
```

- 一个参数一行（YAML 列表）；也接受写成一行 `jvm: "-Xmx1g -XX:+UseSerialGC"`（按空格拆开）。
- 这些参数**追加**在 bootstrap 自己需要的参数之后（`-javaagent`、`-Deml.gameDir`、`-Djdk.rc.repository.path` …）。JVM 对重复项按"后者生效"处理，所以在这里写 `-Xmx1g` 就是**覆盖**默认值，而不是叠加。
- 两项默认值的依据（本机实测，空闲未连服务器）：`-Xmx512m` 的老默认是活堆 95 MB / RSS 384 MB；`-Xmx384m` + 8 字节对象头（JDK 25 的 `UseCompactObjectHeaders`，约 214 万个存活对象）后是活堆 **86 MB** / RSS **340 MB**。进世界后活堆约 170 MB，384 MB 把余量留给服务器的视距。
- 默认**不指定垃圾收集器**：同一个 384 MB 堆下，G1（JVM 默认）实测 340 MB RSS，`-XX:+UseSerialGC` 是 382 MB——单线程收集器一上来就把年轻代按堆的 1/3 提交，空闲 bot 根本用不到，停顿也更长。只有把堆压到 256 MB 时 SerialGC 才略优（336 MB），那是"视距写死"之后才值得的选择。注意**一个 JVM 只能有一个收集器**。
- 整个块缺失时用上面这两项；块存在但没有任何堆上限（`-Xmx` / `-XX:MaxHeapSize` / `-XX:MaxRAMPercentage` / `-XX:MaxRAMFraction`）时补 `-Xmx384m`；写了就以文件为准——对象头那一项**不会**被强行塞进你自己写的块。
- `-cp` / `-classpath` / `--class-path` / `-p` / `--module-path` / `-m` / `--module` / `-jar` 会被拒绝——它们会顶掉 bootstrap 拼好的 classpath。块内容非法（不是 `-` 开头、命中上面这些）时**整个块被忽略**并回退到默认两项，同时打出带下标的错误，进程照常启动。
- 改完下次启动生效。bot 在连接时会把整个 `bot.yml` 重写一遍，`jvm:` 块会原样写回（默认值也会显式写出来）。
- 单次运行可以用 `-Deml.jvmArgs="-Xmx1g -XX:+UseSerialGC"` 覆盖（排在 `jvm:` 之后，同样后者生效）。
- 只在 bootstrap 自己拉起子进程时生效。如果启动方式已经让 Mojang 运行时在 classpath 上（IDE 运行、自己写 `java -cp ... top.mcocet.hlbot.EML`），这个块不会被应用，日志里会写明。
- 早先的独立文件 `<gameDir>/jvm.args` 已不再读取。

## 构建

Gradle（wrapper 已随仓库提供）。在仓库根目录执行：

```
gradlew build
```

产物：`build/libs/EML-HeadlessBOT-1.1.0-26.1.2.jar`（版本号取自 `build.gradle.kts` 的 `version`，默认 `1.1.0-26.1.2`）。

| 任务 | 作用 |
| --- | --- |
| `generateOpenJar` | 用 `eml.engine.OpenJarTool` 从官方 jar 生成 `libs/minecraft-<版本>-open.jar`（仅编译期的放宽访问视图，方法体被丢弃，从不执行） |
| `compileJava` | 依赖上一步；放宽视图作为文件依赖进入 javac classpath |
| `fatJar` | 自身类 + 运行时库；客户端 jar 永不进入产物 |

**干净 checkout 不需要任何预置文件**：`versions/` 本体缺失时由生成器自行下载，`libs/` 完全由构建产生。首次构建只有 Gradle 发行版和依赖需要联网。

顺序靠任务依赖保证：Maven 那套"依赖解析先于生命周期"的限制在 Gradle 里不存在，所以单模块即可完成"先生成、再编译"，不需要拆模块。

依赖与 Gradle 发行版都使用**官方源**（Maven Central、`services.gradle.org`）：本仓库同时由 JitPack 构建（`jitpack.yml` 指定 `openjdk25`），境外访问国内镜像比官方源更慢。`com.mojang:*` 只向 Mojang 官方仓库检索。

强制重新生成放宽视图：`gradlew generateOpenJar -Popenjar.force=true`

## 更新日志

### 1.1.0

- 新增内置**推广播报**（`promo`，移植自 EMLPga）：公屏/私聊两种模式、抖动间隔、随机后缀与数学粗体、进服欢迎、burst 高频窗口、黑名单、完整的运行时子命令表。
- 新增内置**珍珠按钮动作**（`sp`）：按方块状态识别按钮朝向、按可达与视线挑选站位、以 pressed 状态确认点击、可选停靠点回走。
- 新增 `viewDistance: auto|<n>` 视距策略与 `status` 中的视距/资源包/韧性统计。
- 新增 `bot.yml` 的 `autoJump`、`pingBeforeConnect`/`pingTimeoutSeconds`、`plugins` 故障策略、`resourcePacks` 与 `jvm:` 块（原 `jvm.args` 废弃）。
- 新增插件 `ContainerSlotUpdatedEvent`、`BotInventory` 容器/配方 API、`BotApi` 的监控与视距方法、`BotControls` 的按钮/视线/瞄准 API。

### 1.0.0

首个对外版本：无头客户端本体、字节码补丁路线、AutoLogin、`goto` 寻路、插件系统与插件 API。

## 许可

见 [LICENSE](LICENSE)。
