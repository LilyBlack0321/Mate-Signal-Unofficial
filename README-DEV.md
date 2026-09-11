# 开发说明

面向需要修改本仓库的开发者。一般使用者请阅读 [README.md](README.md)。

---

## 仓库结构

```
.
├─ common/                     1.20.1 与 1.21.1 共用的源码（唯一一份）
│  ├─ src/main/
│  │  ├─ java/me/shiny/matesignal/   带 //#if 条件标记的源码
│  │  └─ resources/                  LICENSE.md、CREDITS.txt（打进所有 jar）
│  └─ tools/
│     ├─ PrepareShared.java          条件标记渲染器
│     ├─ render-shared.ps1           单独运行渲染器
│     ├─ annotate-shared.ps1         生成条件标记（一次性，已应用）
│     └─ verify-shared2.ps1          校验共享树渲染结果
├─ versions/
│  ├─ v1_20_1/                 1.20.1 Forge：构建配置 + 平台资源
│  ├─ v1_21_1/                 1.21.1 NeoForge
│  └─ v1_12_2/                 1.12.2 Forge：独立完整源码
├─ tools/
│  ├─ build-all.ps1            一次构建三个平台
│  └─ verify/                  12 组校验工具
├─ setup.ps1                   首次克隆后的环境准备
├─ screenshots/           README 用的截图
└─ PUSH-INSTRUCTIONS.md        发布流程
```

每个 `versions/*/` 下只有**该平台独有的东西**：`settings.gradle`、`build.gradle`、
`gradle.properties`，以及平台资源（`mods.toml` / `neoforge.mods.toml` / `mcmod.info`、
`pack.mcmeta`、`pack.png`）。Java 源码在构建时渲染得到，**不重复提交**。

`.gitignore` 排除了 `common/build/`——那是渲染产物。

---

## 为什么 1.20.1 与 1.21.1 共用源码

实测两边的真实代码差异只有 8 处：

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

其余全是 import 和注释差异。

维护两份近乎相同的副本，正是这两个平台此前产生偏差的原因：1.21.1 的配置屏一度残留了
1.20.1 的注册表调用，且构建过程不会发现。因此改为共用一份源码，差异用条件标记隔离。

**没有引入 Stonecutter 之类的多版本插件**：8 处差异用约 200 行渲染器就能覆盖，
比引入插件更少机制、更好调试，也不增加构建依赖。

**1.12.2 完全独立**：与 1.20.1 只有不到一半代码相同（`MateSignal` 差异 66%、
`Config` 差异 115%），纳入共享树会降低两侧的可读性。

---

## 条件标记

```
//#if 1.20.1
...仅 1.20.1...
//#else
...仅 1.21.1...
//#endif
```

标记是整行注释，渲染后从输出中移除。**不支持嵌套**（会直接报错，而不是静默渲染错）。

`prepareShared` 是每个版本项目里的 Gradle 任务，每次构建都会重新渲染到
该版本的 `build/generated/shared/`。两个平台渲染到各自的 build 目录，不会互相覆盖。

也可以单独运行渲染器：

```powershell
.\common\tools\render-shared.ps1 -Version 1.21.1
```

---

## 校验

`tools/verify/verify.ps1`，12 组：

| 检查组 | 内容 |
|---|---|
| 昼夜跨越逻辑 | 13 条单元测试，含"关掉门限就会误触发"的反证 |
| 时间源审计 | 确认用到始终递增的时钟 |
| 名称本地化 | 各版本用对了本地化 API |
| 语言表门控 | 18 种非中文语言码一律取不到中文表 |
| 群系名覆盖解析 | 22 条边界用例 |
| 事件总线注册 | 游戏事件必须注册到游戏总线 |
| 事件接线完整性 | 事件处理器必须真的调到工作方法，而不是空方法 |
| 测试命令载荷表 | 与运行时载荷一致，且名字必须是占位符 |
| 许可证合规 | v2.0 声明、非 MIT、署名、非官方标注、源码链接是当前地址 |
| 词表一致性 | 三个 jar 的 13 事件逐字节一致 |
| 重混淆 | 反汇编确认 SRG 名已应用 |

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\tools\verify\verify.ps1              # 完整校验（含 UDP 实测）
.\tools\verify\verify.ps1 -SkipLive    # MateEngine 正在运行时用这个
```

`-SkipLive` 会跳过 UDP 实测——MateEngine 运行时会占用 32145 端口。

`common/tools/verify-shared2.ps1` 另外校验共享树：1.20.1 渲染结果与已验证端口
**逐字节一致**（仅在作者工作区有参照树时执行），1.21.1 渲染结果**编译通过**。
日常构建不需要它——`prepareShared` 每次构建都会渲染。

---

## 相对上游修正的缺陷

对照 MateEngine 接收端源码（`Assets/MATE ENGINE - Scripts/Game APIs/AvatarMinecraftMessages.cs`）
发现的四处上游问题：

| # | 问题 | 说明 |
|---|---|---|
| 1 | 缺氧提示从未生效 | 上游发 `drowning_half`，而 MateEngine 只认 `drowning`/`low_air`/`air_low`，事件被静默丢弃 |
| 2 | 时间跳变误判为入夜 | 跨越判定不看时间怎么过去，`/time set` 或进世界都会误触发；已加 200 tick 流速门限 |
| 3 | 专有名词发英文 ID | 改为按游戏语言解析 |
| 4 | 1.12.2 群系名无法本地化 | 该版本群系名硬编码在 `BiomeXxx` 类里，`en_us.lang` 没有 `biome.*` 键；内置语言表补齐 |

### 共享源码重构中引入并修正的缺陷

把两个平台合并到共享树时，1.21.1 的事件入口被写成了空方法：

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
把新渲染的类与已知可用的端口逐方法反汇编比较，`onLevelTick` 少了 4 条指令。
纯源码 diff 无法发现该问题，因为两侧看起来都是正常的。

结论：多版本共享源码时，编译通过不等于调用链完整。涉及事件注册、初始化、
入口方法这类调用关系的改动，必须以可执行证据验证，不能仅依赖编译。
`verify.ps1` 中的「事件接线完整性」检查组即为此设立，并已通过反证测试确认其能检出该缺陷。

---

## 过期源码链接

仓库改名后，`gradle.properties` 中的源码链接已更新，但 jar 内仍是**旧地址**：
`processResources` 被 Gradle 判定为 UP-TO-DATE，未重新渲染资源。

当时的许可证校验只检查占位符是否存在，不检查地址内容，因此未能发现。

现在校验会将 jar 内的链接**与源码树声明的地址比对**。修改元数据后若校验报告
`outdated source URL`，重新构建对应平台即可。

---

## 语言处理

**模组与语言无关**：显示语言跟随 Minecraft 内的语言设置。
代码中不存在对特定语言的假定，语言仅作为数据表的键。

| 版本 | 实体名 | 群系名 |
|---|---|---|
| 1.20.1 / 1.21.1 | `EntityType.getDescription()` | `I18n.get("biome.<ns>.<path>")` |
| 1.12.2 | `EntityList.getTranslationName()` + `I18n.format()` | 四级解析（见下） |

1.12.2 的群系名按四级解析：

| 顺序 | 来源 |
|---|---|
| 1 | 用户覆盖文件 `config/matesignal-biomes.txt` |
| 2 | 当前语言对应的内置表（`Lang.BIOME_TABLES`，目前有 `zh_cn` / `zh_tw`） |
| 3 | 模组自带的语言条目（`biome.<modid>.<path>` 等 4 种候选键） |
| 4 | 游戏原有名称 |

第 2 级为**门控**：`Lang.resolveTable(语言码)` 仅对已注册的语言返回表，其他语言一律返回
`null`，直接落到第 4 级。因此非中文语言环境下不会出现中文名称。

新增语言只需在 `Lang.BIOME_TABLES` 中增加一张表，无需修改逻辑。

---

## 事件协议

模组向 `127.0.0.1:32145` 发送 UDP 数据报，载荷为 JSON。共 13 个事件：

```
time_day  time_night  low_health  low_hunger  rain_start  death
drowning  sleep_start  crafted  eat  kill_confirm  biome_discovery  mob_proximity
```

MateEngine 端的权威接收列表位于 `AvatarMinecraftMessages.cs`。**未识别的 `type` 会被静默丢弃**
（无 `else` 分支），因此事件名拼写错误不会产生任何报错，也不会产生任何输出。
这正是上游 `drowning_half` 缺陷长期未被发现的原因。

修改事件名时必须对照该接收列表，并通过 `/matesignal test all` 实测验证。
