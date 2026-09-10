# MateSignal 非官方版 — 多版本移植（1.20.1 / 1.21.1 / 1.12.2）

**MateSignal 的非官方移植版**，把原版 Minecraft 模组的支持范围扩展到三个新目标平台。

发布名称为 **MateSignal Unofficial**（`mod_name`），但**模组 ID 保持 `matesignal` 不变**——
这样它和上游版共用同一份配置文件 `config/matesignal-client.toml`，
两个版本互换时设置不会丢失，也不会在 `mods.toml` 里出现两个 `modid` 冲突。

MateSignal 是 Minecraft 与 **[MateEngine](https://github.com/shinyflvre/Mate-Engine)**（3D 桌面宠物应用）之间的客户端桥：
模组监听游戏内事件，通过本机 UDP（`127.0.0.1:32145`）把 JSON 事件发给 MateEngine，桌宠据此做出反应。

> ⚠️ **非官方作品。** 本仓库与 MateEngine 项目及其作者无隶属或背书关系。
> 请勿在公开或竞技服务器使用——桌宠会提示"背后有苦力怕"这类信息，可能被视为作弊。

---

## 支持的平台

| 目标 | 加载器 | Java | 状态 |
|---|---|---|---|
| Minecraft 1.20.1 | Forge 47.x | 17 | 已验证 |
| Minecraft 1.21.1 | NeoForge 21.1.x | 21 | 已验证 |
| Minecraft 1.12.2 | Forge 14.23.5.2860 | 8 | 已验证 |

三个端口发出**完全一致的 13 种事件**，可对接同一个 MateEngine 实例。

---

## 仓库结构

```
.
├─ common/                      共享源码（1.20.1 与 1.21.1 共用）
│  ├─ src/main/
│  │  ├─ java/me/shiny/matesignal/   带 //#if 条件标记的源码
│  │  └─ resources/                  LICENSE.md、CREDITS.txt（打进所有 jar）
│  └─ tools/
│     ├─ PrepareShared.java          条件标记渲染器
│     ├─ render-shared.ps1           单独运行渲染器
│     ├─ annotate-shared.ps1         生成条件标记（维护用）
│     └─ verify-shared2.ps1          校验共享树
├─ versions/
│  ├─ v1_20_1/                 1.20.1 Forge（构建配置 + 独有资源）
│  ├─ v1_21_1/                 1.21.1 NeoForge
│  └─ v1_12_2/                 1.12.2 Forge（完全独立，见下）
├─ tools/
│  ├─ build-all.ps1            一次构建全部三个平台
│  └─ verify/                  协议与合规校验工具
├─ setup.ps1                   首次克隆后的环境准备
├─ PUSH-INSTRUCTIONS.md        推送到 GitHub 的逐步操作手册
├─ LICENSE.md                  MateEngine Pro License v2.0（原文）
└─ CREDITS.txt                 来源与署名
```

每个 `versions/*/` 下只有**该平台独有的东西**：`settings.gradle`、`build.gradle`、
`gradle.properties` 与平台资源（`mods.toml` / `neoforge.mods.toml` / `mcmod.info`、`pack.mcmeta`、`pack.png`）。
Java 源码由构建时渲染得到，不重复提交。

### 为什么 1.20.1 与 1.21.1 共用源码

实测两边的**真实代码差异只有 8 处**：

| # | 差异 | 1.20.1 | 1.21.1 |
|---|---|---|---|
| 1 | 配置规格类型 | `ForgeConfigSpec` | `ModConfigSpec` |
| 2 | 实体注册表 | `ForgeRegistries.ENTITY_TYPES` | `BuiltInRegistries.ENTITY_TYPE` |
| 3 | 客户端 tick | `TickEvent.ClientTickEvent` + `MinecraftForge.EVENT_BUS.register` | `LevelTickEvent.Post` + `NeoForge.EVENT_BUS.addListener` |
| 4 | 配置屏注册 | `ConfigScreenHandler` | `IConfigScreenFactory` |
| 5 | 客户端命令 | `@SubscribeEvent` | `addListener` |
| 6 | 可食用判定 | `use.isEdible()` | `use.has(DataComponents.FOOD)` |
| 7 | 滚动回调 | 3 参数 | 4 参数 |
| 8 | 实体查询 | `getEntities(null, box)` | `getEntities((Entity) null, box)` |

其余全是 import 与注释差异。维护两份近乎相同的副本正是两个端口此前**互相跑偏的原因**
（1.21.1 的配置屏一度残留了 1.20.1 的注册表调用），所以共用一份源码，差异用条件标记隔离。

**没有引入 Stonecutter 之类的多版本插件**：8 处差异用一个约 200 行的渲染器就能覆盖，
比引入插件更少机制、更好调试、也不增加构建依赖。

**1.12.2 完全独立**：与 1.20.1 只有不到一半代码相同（`MateSignal` 差异 66%、`Config` 115%），
硬塞进共享树只会让两边都难读。

### 条件标记语法

```
//#if 1.20.1
...仅 1.20.1...
//#else
...仅 1.21.1...
//#endif
```

标记是整行注释，渲染后会从输出中移除。**不支持嵌套**（会直接报错而不是静默渲染错）。

---

## 构建

需要 JDK 17/21（1.20.1 与 1.21.1）和 JDK 8（1.12.2）。各端口的 Gradle 与插件版本不同：

| 端口 | Gradle | 插件 |
|---|---|---|
| 1.20.1 | 8.14.3 | ForgeGradle 6 |
| 1.21.1 | 8.14.3 | ModDevGradle |
| 1.12.2 | 4.10.3 | ForgeGradle 3 |

**共享源码不需要手工渲染**：每个版本项目里有一个 `prepareShared` 任务，
构建时会自动把 `common/` 渲染到该版本的 `build/generated/shared/`。
所以直接构建即可：

```powershell
# 一次构建全部三个平台（产物汇到 dist\）
.\tools\build-all.ps1

# 或者只构建一个
cd versions\v1_20_1 ; gradle build
cd versions\v1_21_1 ; gradle build
cd versions\v1_12_2 ; gradle build          # 需要 JDK 8 + Gradle 4.10.3
```

`tools\build-all.ps1` 需要知道工具链在哪，按以下顺序查找，找不到会明确告诉你要设哪个变量：

| 变量 | 用途 |
|---|---|
| `GRADLE_8_HOME` | Gradle 8.14.3（1.20.1 与 1.21.1） |
| `GRADLE_LEGACY_HOME` | Gradle 4.10.3（1.12.2；ForgeGradle 3 无法在现代 Gradle 上运行） |
| `JDK17_HOME` / `JDK21_HOME` | 1.20.1 / 1.21.1 的 JDK |
| `JDK8_HOME` | 1.12.2 的 JDK |

也可以先跑 `.\setup.ps1` 生成各端口自己的 `gradlew`，之后用 `.\gradlew build`。

### 代理等本机设置

`gradle.properties` 里**不含任何代理配置**——代理属于你的网络，不属于这个模组。
需要时复制 `gradle.properties.local.example` 为对应端口目录下的 `gradle.properties.local`，
填好后 `build-all.ps1` 会自动读取其中的 `systemProp.*` 并传给 Gradle JVM。
该文件已在 `.gitignore` 中，不会被提交。

---

## 配置

| 配置文件 | 说明 |
|---|---|
| `config/matesignal-client.toml` | 1.20.1 / 1.21.1 设置 |
| `config/matesignal.cfg` | 1.12.2 设置 |
| `config/matesignal-biomes.txt` | 1.12.2 群系名覆盖（见下） |

选项：检测半径、生物白名单，以及 12 个事件开关（昼夜、低血量、低饥饿、死亡、下雨、缺氧、入睡、合成、进食、击杀、群系）。

### 客户端命令

| 命令 | 作用 |
|---|---|
| `/matesignal test all` | 依次发送全部 13 种事件（每秒一条） |
| `/matesignal test <事件名>` | 只发指定事件，支持前缀匹配 |
| `/matesignal list` | 列出全部事件名 |
| `/matesignal reload` | 重读群系名覆盖表 |

`test` 命令的载荷与正常游玩**走同一套语言解析**，所以两边的专有名词显示一致。

---

## 语言处理

**模组是语言无关的**：你在 Minecraft 里选什么语言，MateEngine 就显示什么语言。
代码里没有任何地方假定中文——语言只是数据表的一个键。

| 版本 | 怪物名 | 群系名 |
|---|---|---|
| 1.20.1 / 1.21.1 | `EntityType.getDescription()` | `I18n.get("biome.<ns>.<path>")` |
| 1.12.2 | `EntityList.getTranslationName()` + `I18n.format()` | 四级解析（见下） |

### 1.12.2 为何需要内置表

该版本**无法翻译群系名**：名字是硬编码在每个 `BiomeXxx` 类里的英文串，
`en_us.lang` 里没有任何 `biome.*` 键，客户端 jar 也只带 `en_us`。原版自己就显示英文。

所以群系名按四级解析：

| 顺序 | 来源 |
|---|---|
| 1 | 用户覆盖文件 `config/matesignal-biomes.txt` |
| 2 | **当前语言**对应的内置表（`Lang` 里按语言码注册，目前有 `zh_cn` / `zh_tw`） |
| 3 | 模组自带的语言条目（`biome.<modid>.<path>` 等 4 种候选键） |
| 4 | 游戏原有名称 |

第 2 级是**门控**：`Lang.resolveTable(语言码)` 只对注册过的语言返回表，
其他语言一律返回 `null`，直接落到第 4 级。**英文玩家永远不会看到中文。**
要新增一门语言，只需在 `Lang.BIOME_TABLES` 里加一张表，不用改逻辑。

---

## 校验

`tools/verify/verify.ps1` 共 12 组检查，覆盖：

| 检查组 | 内容 |
|---|---|
| 昼夜跨越逻辑 | 13 条单元测试（含"关掉门限就会误触发"的反证） |
| 时间源审计 | 确认用到始终递增的时钟 |
| 名称本地化 | 各版本用对了本地化 API |
| 语言表门控 | 18 种非中文语言码一律取不到中文表 |
| 群系名覆盖解析 | 22 条边界用例 |
| 事件总线注册 | 游戏事件必须注册到游戏总线 |
| **事件接线完整性** | 事件处理器必须真的调到工作方法，而不是空方法 |
| 测试命令载荷表 | 与运行时发送的载荷一致，且名字必须是占位符 |
| 许可证合规 | v2.0 声明、非 MIT、署名、非官方标注 |
| 词表一致性 | 三个 jar 的 13 事件逐字节一致 |
| 重混淆 | 反汇编确认 SRG 名已应用 |

「事件接线完整性」是补上 §"共享源码重构中引入并修正的缺陷" 后新增的：
它反汇编每个端口的事件处理器，要求其中确实存在对工作方法的调用，
且工作路径确实会排空测试命令队列。**这条检查经过反证验证**——
把缺陷重新塞回去，它确实报 FAIL。

`common/tools/verify-shared2.ps1` 另外校验共享树：
1.20.1 渲染结果与已验证端口**逐字节一致**（仅在作者工作区有参照树时执行），
1.21.1 渲染结果**编译通过**。日常构建不需要它——`prepareShared` 每次构建都会渲染。

---

## 相对上游修正的缺陷

移植过程中对照 MateEngine 接收端源码，发现并修正了四处上游问题：

| # | 问题 | 说明 |
|---|---|---|
| 1 | 缺氧提示从未生效 | 上游发 `drowning_half`，而 MateEngine 只认 `drowning`/`low_air`/`air_low`，事件被静默丢弃 |
| 2 | 时间跳变误判为入夜 | 跨越判定不看时间怎么过去，`/time set` 或进世界都会误触发；已加 200 tick 流速门限 |
| 3 | 专有名词发英文 ID | 改为按游戏语言解析 |
| 4 | 1.12.2 群系名无法本地化 | 内置语言表补齐（见上） |

### 共享源码重构中引入并修正的缺陷（1.21.1 不工作）

把两个端口合并到共享树时，1.21.1 的事件入口被写成了空方法：

```java
// 错误
if (!event.getLevel().isClientSide()) { return; }
// 正确
if (!event.getLevel().isClientSide()) { return; }
onClientTick();
```

`onClientTick()` 调用漏了，于是 `LevelTickEvent.Post` 每次触发都立刻返回，
**模组在 1.21.1 上完全不会发出任何事件**——游戏能启动，但桌宠毫无反应。

它能通过编译，是因为空方法体是完全合法的 Java。发现它的方式是**字节码级对比**：
把新渲染出的类与已知可用的端口逐方法反汇编比较，`onLevelTick` 少了 4 条指令
（`aload_0` / `invokevirtual onClientTick`）。纯源码 diff 看不出来——两边都"正常"。

教训：多版本共享源码时，"能编译"不等于"接上了"。涉及事件注册、初始化、
入口方法这类**接线处**的改动，必须用可执行证据（反汇编或实机运行）验证，
不能只依赖编译通过。`tools/verify/verify.ps1` 的事件总线检查组即为此存在。

---

## 许可证

以 **MateEngine Pro License v2.0** 分发（`LICENSE.md`）。

上游的许可声明存在不一致：源码仓库的 `LICENSE.md` 是 MateEngine Pro License v2.0，
但其构建元数据写 MIT、成品 jar 内打包 CC0。**以仓库 LICENSE.md 为准**，本移植据此合规：

- 本模组**免费提供**，不出售、不设付费门槛
- 源码公开可查（本仓库）
- 保留原作者署名并声明为非官方移植
- 不冒充官方作品

详见 `CREDITS.txt` 与 `LICENSE.md`。
