# 推送到 GitHub —— 操作手册

这个目录（`repo\`）已经是一个可以直接推送的仓库内容：49 个文件，无 git 元数据，无构建产物。
下面每一步都可以直接复制执行。**本机没有安装 git**，所以需要你自己执行这些命令。

本手册按你之前的选择来写：**fork 上游，单 main 分支，三个平台作为仓库内的三个子项目**（不是三个分支）。

---

## 0. 先做一件事：填上你的源码地址

发布前必须把 `REPLACE_WITH_YOUR_PUBLIC_SOURCE_URL` 换成本仓库的真实地址。
MateEngine Pro License v2.0 第 4 条要求发布衍生版本时**公开并提供完整源码链接**，这个占位符就是那个链接。

共 3 个地方（推送前先填，或者推送后改完再 commit 一次都行）：

| 文件 | 位置 |
|---|---|
| `versions/v1_20_1/gradle.properties` | `mod_description` 结尾 |
| `versions/v1_21_1/gradle.properties` | `mod_description` 结尾 |
| `versions/v1_12_2/gradle.properties` | `mod_description` 结尾 |
| `versions/v1_12_2/src/main/resources/mcmod.info` | `url` 字段 |

仓库地址在推送之前是未知的，所以有两种顺序，任选：

- **先推后填**：先推上去拿到 `https://github.com/<你>/<仓库名>`，再替换、再 commit、再 push。
- **先填后推**：如果你已经想好仓库名（例如 fork 后仍是 `Mate-Signal`），现在就填。

批量替换（在 `repo\` 目录下执行，把 URL 换成你自己的）：

```powershell
$url = 'https://github.com/YOUR_NAME/YOUR_REPO'
Get-ChildItem -Recurse -Include gradle.properties,mcmod.info |
  ForEach-Object {
    (Get-Content $_ -Raw) -replace 'REPLACE_WITH_YOUR_PUBLIC_SOURCE_URL', $url |
      Set-Content $_ -NoNewline -Encoding UTF8
  }
Select-String -Recurse -Pattern 'REPLACE_WITH_YOUR_PUBLIC_SOURCE_URL'   # 应该没有任何输出
```

---

## 1. 安装 git

任选一种：

```powershell
winget install --id Git.Git -e
# 或下载 https://git-scm.com/download/win
```

装完**重开一个终端**（PATH 需要刷新），然后确认：

```powershell
git --version
```

首次使用还需要设置身份（只需一次）：

```powershell
git config --global user.name  "你的名字"
git config --global user.email "你的邮箱"
```

---

## 2. 在 GitHub 网页上 fork

1. 打开 <https://github.com/shinyflvre/Mate-Signal>
2. 右上角 **Fork** → **Create fork**
3. 得到 `https://github.com/<你的用户名>/Mate-Signal`

> 如果 GitHub 上已经存在同名仓库，fork 会失败。可以在 fork 页面把名字改成
> `Mate-Signal-Ports` 之类，然后第 3 步的 URL 跟着改。

---

## 3. 把 `repo\` 的内容推上去

按你之前的选择：**保留 upstream 的提交历史，我们的移植版放在 `main` 分支上**是不行的——
我们的目录结构和上游完全不同（上游是单个 1.21.x 项目，我们是 `common/` + `versions/` 三平台）。
所以用一条独立分支承载移植版最干净。推荐 **`ports` 分支**，`main` 保持与上游同步，
以后上游更新可以直接 `git merge upstream/main` 而不冲突。

```powershell
# 3.1 克隆你 fork 出来的仓库（先只拿默认分支）
cd <workspace>
git clone https://github.com/YOUR_NAME/Mate-Signal.git <clone-dir>
cd <clone-dir>

# 3.2 记录上游，方便以后同步
git remote add upstream https://github.com/shinyflvre/Mate-Signal.git

# 3.3 建一条与上游历史无关的移植分支
git checkout --orphan ports
git rm -rf .            # 清空工作区里的上游文件（-rf 只影响索引和工作区）

# 3.4 把 repo\ 的内容复制进来（注意结尾的 . 和 \*）
Copy-Item -Recurse -Force <repo-root>\* .
Copy-Item -Force <repo-root>\.gitignore .

# 3.5 确认目录结构对，然后提交
git add -A
git status --short
git commit -m "MateSignal ports: 1.20.1 Forge, 1.21.1 NeoForge, 1.12.2 Forge"

# 3.6 推送
git push -u origin ports
```

### 如果你更想只保留 `main` 一条分支

那就不要 `--orphan`，直接在 `main` 上覆盖内容：

```powershell
git clone https://github.com/YOUR_NAME/Mate-Signal.git <clone-dir>
cd <clone-dir>
git checkout main
# 删掉上游的旧文件
Get-ChildItem -Force | Where-Object { $_.Name -ne '.git' } | Remove-Item -Recurse -Force
Copy-Item -Recurse -Force <repo-root>\* .
Copy-Item -Force <repo-root>\.gitignore .
git add -A
git commit -m "Replace with multi-version port tree (1.20.1 / 1.21.1 / 1.12.2)"
git push
```

两种方式的区别只有一个：`ports` 分支能继续用 `git merge upstream/main` 吸收上游更新；
覆盖 `main` 则与上游历史分叉，之后同步要靠手工。**移植版建议用 `ports` 分支。**

---

## 4. 推送后：把仓库地址填回去

如果第 0 步没填，现在填：

```powershell
cd <clone-dir>
$url = 'https://github.com/YOUR_NAME/Mate-Signal'
Get-ChildItem -Recurse -Include gradle.properties,mcmod.info |
  ForEach-Object {
    (Get-Content $_ -Raw) -replace 'REPLACE_WITH_YOUR_PUBLIC_SOURCE_URL', $url |
      Set-Content $_ -NoNewline -Encoding UTF8
  }
git add -A
git commit -m "Point mod metadata at the public source repository"
git push
```

---

## 5. 验收：确认推上去的东西能构建

在一台干净的机器（或删掉 `_tools` 的临时副本）上：

```powershell
cd <clone-dir>
.\setup.ps1                 # 冒烟测试渲染器
.\tools\build-all.ps1       # 构建全部三个平台
```

需要 `GRADLE_8_HOME` / `GRADLE_LEGACY_HOME` / `JDK8_HOME` / `JDK21_HOME`
（脚本会告诉你缺哪个）。产物落在 `dist\`。

---

## 6. 发布到 CurseForge

MateEngine Pro License v2.0 第 4 条允许把衍生版本发布到第三方平台，附加条件是：

- ✅ **不得收费** —— 上传时把 CurseForge 的付费/分成选项关掉
- ✅ **保留许可证与署名** —— jar 里已带 `LICENSE.md` 与 `CREDITS.txt`，`mods.toml` 的
  `license` 字段已写 `MateEngine Pro License v2.0`
- ✅ **公开完整源码** —— 就是第 4 步填进去的那个链接
- ✅ **不得声称官方** —— `mod_credits` 已注明 "Not affiliated with or endorsed by the
  MateEngine project"，CurseForge 的项目描述里也建议写一句

三个 jar（`dist\` 里）分别对应三个游戏版本，CurseForge 上建一个项目、三个 file 即可，
游戏版本选 1.20.1 / 1.21.1 / 1.12.2。

---

## 附：为什么不是一个版本一个分支

三个平台**共用一份源码**：`common\` 里是唯一的副本，用 `//#if` 标记区分版本差异
（真正需要分叉的只有 8 处）。拆成三个分支意味着 `common\` 会被复制三份、
以后每次修 bug 要改三遍、还要人工同步——这正是上游那份 Fabric 移植版踩过的坑。

分支策略和平台数量无关，和「代码是否共享」有关。共享 → 一个分支三个子项目；
完全不共享（比如你以后要移植到完全不同的加载器且无法共享）→ 才考虑分分支。

`1.12.2` 目前就是「不共享」的：它的 `versions\v1_12_2\src\main` 是一份独立完整源码，
因为 1.12.2 与 1.20.1 的差异率（`MateSignal` 66%、`Config` 115%）远超共享的收益。
即使如此它也放在同一个分支里，因为**构建产物是一个仓库、一次发布**，
而不是两份需要分别维护的历史。