# 维护与发布说明

## 当前状态

| 项目 | 值 |
|---|---|
| 本地仓库 | `<repo-root>` |
| 远端 | https://github.com/LilyBlack0321/Mate-Signal-Unofficial |
| 类型 | `shinyflvre/Mate-Signal` 的 fork |
| 默认分支 | `main`（即本移植版） |
| git 身份 | `LilyBlack <2496414612@qq.com>` |

`main` 与上游**没有共同祖先**：上游是 1.21.10 单版本项目，本仓库是三平台多版本结构。

**因此不要使用仓库页面上的 "Sync fork" 按钮。** 该按钮指向的上游历史与本仓库结构
不兼容，合并不会产生有意义的结果。上游若更新，人工比对后按需移植。

---

## 日常更新

```powershell
cd <repo-root>

git add -A
git commit -m "说明本次改动"
git push
```

凭据已存于 Windows 凭据管理器，`push` 不再询问账号。

`.gitignore` 已排除 `build/`、`.gradle/`、`.gradle-user-home/`、`dist/`、
`common/build/` 和 `gradle.properties.local`，因此 `git add -A` 不会误提交构建产物
或本机代理配置。

若 `push` 报 TLS 或连接错误，检查本机代理是否在运行：

```powershell
git config --global --get http.proxy
```

---

## 修改代码后的检查清单

```powershell
# 1. 构建三个平台
.\tools\build-all.ps1

# 2. 运行校验（MateEngine 未运行时）
.\tools\verify\verify.ps1

# 3. 若改动了 common/ 下的共享源码，额外确认渲染结果
.\common\tools\verify-shared2.ps1
```

改了事件名或新增事件时，必须对照 MateEngine 的
`Assets/MATE ENGINE - Scripts/Game APIs/AvatarMinecraftMessages.cs` 确认名称，
并用 `/matesignal test all` 实测。未识别的事件名会被静默丢弃，不会有任何报错。

---

## 截图

`screenshots/` 内目前是占位图。替换为真实截图时保持文件名不变即可，
README 中的引用无需改动。

| 文件名 | 内容 |
|---|---|
| `01-bubble.png` | 桌宠气泡 |
| `02-night.png` | 天黑提示 |
| `03-mob.png` | 附近有敌对生物 |
| `04-craft.png` | 合成完成 |

---

## 发布到 CurseForge

MateEngine Pro License v2.0 第 4 条允许发布到第三方平台，附带条件：

- **不得收费** —— 上传时关闭 CurseForge 的付费/分成选项
- **保留许可证与署名** —— jar 内已含 `LICENSE.md` 与 `CREDITS.txt`，
  `mods.toml` 的 `license` 字段为 `MateEngine Pro License v2.0`
- **公开完整源码** —— 即本仓库地址，已写入 `mod_description` 与 `mcmod.info`
- **不得声称官方** —— `credits` 已注明 "Not affiliated with or endorsed by the
  MateEngine project"，建议项目描述中也写明

上传对象为 `dist\` 下的三个 jar：

| 文件 | CurseForge 游戏版本 |
|---|---|
| `matesignal-forge-1.20.1-1.1.0-1.20.1.jar` | 1.20.1 |
| `matesignal-neoforge-1.21.1-1.1.0-1.21.1.jar` | 1.21.1 |
| `matesignal-forge-1.12.2-1.1.0-1.12.2.jar` | 1.12.2 |