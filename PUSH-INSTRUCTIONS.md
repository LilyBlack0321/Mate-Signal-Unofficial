# 发布到 GitHub —— 操作手册

本仓库在本地已经初始化好 git 并完成了首次提交，**剩下的只有"建仓库 + 推送"两步**。

---

## 当前状态

| 项目 | 值 |
|---|---|
| 本地仓库 | `<repo-root>` |
| 分支 | `main` |
| 提交 | 1 个（57 个文件） |
| git 身份 | `LilyBlack <2496414612@qq.com>` |
| 目标地址 | https://github.com/LilyBlack0321/Mate-Signal-Unofficial |

源码链接已经写进三个 `gradle.properties` 的 `mod_description` 和 1.12.2 的 `mcmod.info`，
并已打进三个 jar——这是 MateEngine Pro License v2.0 第 4 条的要求。

---

## 第 1 步：在 GitHub 上建一个空白仓库

打开 <https://github.com/new>：

| 字段 | 填什么 |
|---|---|
| Owner | `LilyBlack0321` |
| Repository name | `Mate-Signal-Unofficial` |
| Public / Private | **Public**（许可证要求源码公开） |
| Add a README file | **不勾** |
| Add .gitignore | **None** |
| Choose a license | **None** |

最后三项都不勾。勾了会生成一个初始提交，与本地提交冲突，push 会被拒绝。

## 第 2 步：推送

```powershell
cd <repo-root>
git remote add origin https://github.com/LilyBlack0321/Mate-Signal-Unofficial.git
git push -u origin main
```

第一次 push 会弹出浏览器让你授权（Git Credential Manager）：

1. 选 **Sign in with your browser**
2. 浏览器里选你的 GitHub 账号 → **Authorize**
3. 回到终端，push 自动继续

凭据会被记住，以后 push 不再询问。

---

## 如果 push 被拒绝

| 报错 | 原因与处理 |
|---|---|
| `remote contains work that you do not have locally` | 建仓库时误勾了 README/gitignore/license。删掉那个仓库重建，或执行 `git pull --rebase origin main` 后再 push |
| `Repository not found` | 仓库名或用户名拼错；或仓库是 Private 而凭据没权限 |
| `Authentication failed` | 凭据过期。执行 `git credential-manager erase` 后重新 push 触发登录 |

---

## 日常更新

改完代码后：

```powershell
cd <repo-root>

# 重新构建并校验（可选，但建议）
.\tools\build-all.ps1
.\tools\verify\verify.ps1 -SkipLive

git add -A
git commit -m "说明这次改了什么"
git push
```

`.gitignore` 已经排除了 `build/`、`.gradle/`、`.gradle-user-home/`、`dist/`
和 `gradle.properties.local`，所以 `git add -A` 不会误提交构建产物或你的本机代理配置。

---

## 发布到 CurseForge

MateEngine Pro License v2.0 第 4 条允许发布到第三方平台，附加条件：

- ✅ **不得收费** —— 上传时关闭 CurseForge 的付费/分成选项
- ✅ **保留许可证与署名** —— jar 内已含 `LICENSE.md` 与 `CREDITS.txt`，
  `mods.toml` 的 `license` 字段是 `MateEngine Pro License v2.0`
- ✅ **公开完整源码** —— 就是上面那个仓库地址
- ✅ **不得声称官方** —— `credits` 已注明 "Not affiliated with or endorsed by the
  MateEngine project"，建议在 CurseForge 项目描述里也写一句

三个 jar（`dist\` 里）对应三个游戏版本：建一个项目、三个 file，
游戏版本分别选 1.20.1 / 1.21.1 / 1.12.2。

---

## 附：为什么三个平台放在同一个分支

`common/` 里是 1.20.1 与 1.21.1 **共用的唯一一份源码**，用 `//#if` 标记隔离
8 处真实差异。拆成三个分支意味着这份源码被复制三份、每次修 bug 要改三遍、
还要人工同步——这正是上游那份 Fabric 移植版踩过的坑。

分支策略和平台数量无关，和「代码是否共享」有关。共享 → 一个分支多个子项目。

`1.12.2` 目前是「不共享」的（`versions/v1_12_2/src/main` 是独立完整源码，
因为它与 1.20.1 的差异率高达 66%~115%），但它仍然放在同一个分支里，
因为**构建产物是一个仓库、一次发布**，而不是两份需要分别维护的历史。