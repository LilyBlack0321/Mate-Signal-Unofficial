# MateSignal 非官方版

[English](README_EN.md) | **简体中文**

MateSignal 是一个 Minecraft 客户端模组，让游戏和 **[MateEngine](https://github.com/shinyflvre/Mate-Engine)** 联动。

MateEngine 是一个 3D 桌面宠物软件，可以把 VTuber 或 3D 模型放在桌面上，支持跳舞、坐在窗口边缘之类的互动。装上这个模组之后，你在游戏里做了什么，桌上的角色就会跟着说点什么。

比如你去睡觉，它可能会在桌面上说一句“晚安”。

> **这是非官方移植版。** 原模组由 Shiny 开发，只支持 1.21.x。本移植版把它带到了
> 1.20.1 和 1.12.2，并为 1.21.1 提供了 NeoForge 版本。
> 与 MateEngine 项目及原作者无隶属关系。

---

## 功能

游戏里发生特定事件时，桌宠会冒出对应的气泡：

- 天亮、天黑
- 附近有怪物
- 血量或饱食度过低
- 溺水
- 死亡
- 开始下雨
- 上床睡觉
- 合成出物品
- 吃东西
- 击杀生物
- 第一次到达某个群系

一共 13 种事件，三个平台发出的内容完全一致，可以对接同一个 MateEngine。

怪物名和群系名会跟着**你在 Minecraft 里选的语言**走：英文玩家看到英文，中文玩家看到中文。模组本身不假定任何语言。

---

## 支持的版本

| Minecraft | 加载器 | Java | 模组文件 |
|---|---|---|---|
| 1.20.1 | Forge 47.x | 17 | `matesignal-forge-1.20.1-1.1.0-1.20.1.jar` |
| 1.21.1 | NeoForge 21.1.x | 21 | `matesignal-neoforge-1.21.1-1.1.0-1.21.1.jar` |
| 1.12.2 | Forge 14.23.5.2860 | 8 | `matesignal-forge-1.12.2-1.1.0-1.12.2.jar` |

1.21.10 和 1.21.1 的 Forge / Fabric 版本请用[原模组](https://github.com/shinyflvre/Mate-Signal)。

---

## 安装

1. 装好对应版本的加载器（[Forge](https://files.minecraftforge.net/) 或 [NeoForge](https://neoforged.net/)）
2. 按上表下载对应版本的 jar
3. 丢进 `.minecraft/mods` 文件夹
4. 确保 MateEngine 正在运行
5. 启动游戏

模组是客户端专用的，装在服务器上没有用，服务端也不需要装。

---

## 配置

1.20.1 / 1.21.1 可以在游戏里的**模组列表 → MateSignal → 配置**直接改，也可以编辑
`config/matesignal-client.toml`。1.12.2 是 `config/matesignal.cfg`。

可调的选项：

- 怪物检测半径
- 哪些生物会触发“附近有怪物”提示
- 逐个开关上面那 13 种事件

模组 ID 保持 `matesignal` 不变，配置文件和原模组通用，两个版本互换不会丢设置。

---

## 客户端命令

| 命令 | 作用 |
|---|---|
| `/matesignal test all` | 依次发一遍全部 13 种事件，一秒钟一条 |
| `/matesignal test <事件名>` | 只发指定事件，支持前缀匹配 |
| `/matesignal list` | 列出所有事件名 |
| `/matesignal reload` | 重新读取群系名覆盖表（仅 1.12.2） |

想确认模组和 MateEngine 通了没有，进游戏敲 `/matesignal test all`，桌宠应该会连着冒一串气泡。不需要进世界，在主菜单就能测。

---

## 使用提醒

**别在公开或竞技服务器上用。** 桌宠会说“背后有苦力怕”这种话，在部分服务器上这算作弊。自己玩或者和朋友开私服再用。

---

## 如果没反应

按顺序检查：

1. **MateEngine 在运行吗** —— 模组只是往本机发数据，接收端不在就什么都不会发生
2. **UDP 32145 端口通不通** —— 模组往 `127.0.0.1:32145` 发 UDP 包，防火墙拦了就不行
3. **用 `/matesignal test all` 手动触发** —— 能出气泡说明链路是通的，问题在事件检测；不出气泡说明链路断了
4. **看日志** —— 模组加载失败会在 `logs/latest.log` 里留下错误

---

## 从源码构建

需要 Windows 和 PowerShell。三个平台的 Gradle 和 JDK 不一样：

| 平台 | Gradle | JDK |
|---|---|---|
| 1.20.1 | 8.14.3 | 17 或 21 |
| 1.21.1 | 8.14.3 | 21 |
| 1.12.2 | 4.10.3 | 8 |

```powershell
# 一次构建三个平台，产物在 dist\
.\tools\build-all.ps1

# 或者只构建一个
cd versions\v1_20_1
gradle build
```

脚本会自己查找工具链，找不到会告诉你要设哪个环境变量（`GRADLE_8_HOME`、`GRADLE_LEGACY_HOME`、`JDK8_HOME`、`JDK21_HOME`）。也可以先跑 `.\setup.ps1` 给每个平台生成 `gradlew`。

技术细节——仓库结构、共享源码的设计、8 处真实差异、校验手段——都在 [README-DEV.md](README-DEV.md)。

---

## 截图

![桌宠气泡](screenshots/01-bubble.png)

![入夜提示](screenshots/02-night.png)

![附近有怪物](screenshots/03-mob.png)

![合成完成](screenshots/04-craft.png)

---

## 致谢

- **原模组与 MateEngine**：Shiny（Johnson Jason）
  - [Mate-Signal](https://github.com/shinyflvre/Mate-Signal)
  - [Mate-Engine](https://github.com/shinyflvre/Mate-Engine)
- **Fabric 移植版**（本移植的参考）：[VeridonNetzwerk](https://github.com/VeridonNetzwerk/Mate-Signal-Fabric)
- **本移植版**：[LilyBlack0321](https://github.com/LilyBlack0321)

原版有个一直没修的小毛病：缺氧提示发的事件名是 `drowning_half`，而 MateEngine 只认 `drowning`，所以那条提示从来没生效过。本移植版修掉了，另外还修了时间跳变误判入夜、专有名词发英文 ID、1.12.2 群系名无法翻译这几处。完整列表见 [README-DEV.md](README-DEV.md)。

---

## 许可证

以 **MateEngine Pro License v2.0** 分发，全文见 [LICENSE.md](LICENSE.md)。

简单说：这个模组免费，源码公开，保留原作者署名，不冒充官方作品。本项目不包含任何广告或付费内容。
