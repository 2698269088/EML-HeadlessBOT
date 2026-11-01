# EML-HeadlessBOT

无头（headless）Minecraft 26.1.2 机器人客户端
## 它是什么

一个没有窗口、不加载渲染/声音/字体系统的完整协议客户端：能登录服务器、进世界、收发聊天与命令、攻击/挖掘/丢弃物品、维护真实方块实体与容器状态。JFR 实测**回收后活堆约 170 MB** ；`botinfo` 的 `used` 会更高，因为大堆 + 无内存压力时 G1 不主动回收垃圾，所以要用它的 `live heap` 行比较，而不是 `used`。

实现路线是把改动做成**运行期字节码补丁**，而不是维护一棵反编译源码树：

- 编译期依赖一个由 `eml.engine.OpenJarTool` 生成的"放宽访问标志"副本 jar（`libs/minecraft-<版本>-open.jar`，构建时自动生成），让 donor 模板可以书写对私有/最终成员的访问；
- 运行期加载的是**未经修改的官方 jar**；私有成员之所以合法，是因为 donor 方法体被内联进目标类自身（成为其 nestmate），而不是因为访问标志真的被改过；
- 补丁由 `-javaagent` 在类加载时施加，`eml.template` 下的 Java 方法就是补丁内容——它们经过 javac 类型检查，不是手写指令。

## 数据与平台边界（硬约束）

1. 只碰两类数据：**MC 本体**（定位/下载/校验/加载）与 **BOT 自己的配置与运行数据**（gameDir 内的 `bot.yml`、`plugins/`、`versions/`、`libraries/`、`logs/`、`lang/`、`assets/`、`debug/`）。
2. **不读写项目目录之外的任何文件**：不探测启动器安装、不读 `APPDATA`/`user.home` 等环境变量或目录、不读其他用户数据。构建脚本同样只读 `<项目>/versions/` 下的本体。若确需外部路径，只接受使用者**显式指定**（`-Deml.clientJar`、`-Deml.assetsDir`），程序绝不自行发现。
3. **不与任何平台强关联**：源码里没有 `if (Windows)` 式分支、没有平台专属可执行文件名（重启自身时取 `ProcessHandle.current().info().command()`）、不注入只在某个 JVM 实现上存在的命令行标志。允许使用现成依赖项（JLine / SnakeYAML / Gson / SLF4J…），但仅限 pom 已声明的那些。
4. 规定的调用链：**下载本体 → 用 ASM 修改它 → 运行**。当前实现中"修改"发生在类加载期；`os.name`/`os.arch` 仅用于解析 Mojang 清单自带的 `libraries[].rules`（Mojang 自己按 OS 分类库），属于读取数据自身的分类信息，不是平台耦合。`

| 命令行 | 说明 |
| --- | --- |
| `--username <name>` | 用户名（省略则交互询问，并写入 `<gameDir>/bot.yml`） |
| `--server <host> --port <n>` | 启动后自动连接 |
| `--online` | 正版验证模式 |
| `--viewDistance <n>` | 上报给服务器的视距（默认 4，显著降低区块流量与卡顿） |
| `--gameDir <dir>` | 运行目录（`plugins/`、`logs/`、`versions/`、`libraries/`、`lang/`、`debug/` 都在这里；**不要**在 `target/` 里跑，`mvn clean` 会连配置一起删） |

首次启动的自举（`eml.bootstrap.Bootstrap`）会自动完成：创建 `plugins/` 等目录；若 `versions/<版本>/<版本>.jar` 缺失则从 `piston-meta.mojang.com` 按版本清单下载**并校验 SHA-1**（库的适用性按清单自身的 `rules` 判定；跳过 LWJGL/natives）；然后带 `-javaagent` 重启自己（父进程继承退出码，避免无终端环境下 JVM 提前退出）。全过程只读写 gameDir。

## 配置：`<gameDir>/bot.yml`

```yaml
username: Test
server: 127.0.0.1
port: 25565
online: false
viewDistance: 4
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
