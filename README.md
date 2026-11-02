# EML-HeadlessBOT

无头（headless）Minecraft 26.1.2 机器人客户端
## 它是什么

一个没有窗口、不加载渲染/声音/字体系统的完整协议客户端：能登录服务器、进世界、收发聊天与命令、攻击/挖掘/丢弃物品、维护真实方块实体与容器状态。JFR 实测**回收后活堆约 170 MB** ；`botinfo` 的 `used` 会更高，因为大堆 + 无内存压力时 G1 不主动回收垃圾，所以要用它的 `live heap` 行比较，而不是 `used`。

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

## 启动参数：`bot.yml` 的 `jvm` 块

同一个 `bot.yml` 里，`jvm:` 是给**运行 bot 的那个 JVM** 的参数；默认只有一项：

```yaml
jvm:
  - "-Xmx512m"
```

- 一个参数一行（YAML 列表）；也接受写成一行 `jvm: "-Xmx1g -XX:+UseSerialGC"`（按空格拆开）。
- 这些参数**追加**在 bootstrap 自己需要的参数之后（`-javaagent`、`-Deml.gameDir`、`-Djdk.rc.repository.path` …）。JVM 对重复项按"后者生效"处理，所以在这里写 `-Xmx1g`、`-XX:+UseSerialGC` 就是**覆盖**默认值，而不是叠加。
- 块里没有任何堆上限（`-Xmx` / `-XX:MaxHeapSize` / `-XX:MaxRAMPercentage` / `-XX:MaxRAMFraction`）时，自动补 `-Xmx512m`；写了就以文件为准。默认 512 MB 的依据是活堆实测约 170 MB（见上）；视距开大、插件多就调高，想在一台机器上多开就调低。
- `-cp` / `-classpath` / `--class-path` / `-p` / `--module-path` / `-m` / `--module` / `-jar` 会被拒绝——它们会顶掉 bootstrap 拼好的 classpath。块内容非法（不是 `-` 开头、命中上面这些）时**整个块被忽略**并回退到默认值，同时打出带下标的错误，进程照常启动。
- 改完下次启动生效。bot 在连接时会把整个 `bot.yml` 重写一遍，`jvm:` 块会原样写回（默认值也会显式写出来，所以文件里看到的就是实际生效的堆）。
- 单次运行可以用 `-Deml.jvmArgs="-Xmx1g -XX:+UseSerialGC"` 覆盖（排在 `jvm:` 之后，同样后者生效）。
- 只在 bootstrap 自己拉起子进程时生效。如果启动方式已经让 Mojang 运行时在 classpath 上（IDE 运行、自己写 `java -cp ... top.mcocet.hlbot.EML`），这个块不会被应用，日志里会写明。
- 早先的独立文件 `<gameDir>/jvm.args` 已不再读取；如果它还在，启动时会打一条警告提示把内容搬进 `jvm:` 块。
