# 截图 / Screenshots

README 引用的截图。文件名与 README 中的引用一一对应，替换时保持文件名不变即可。

Screenshots referenced by the README. Keep the file names unchanged when replacing.

| 文件名 / File name | 内容 / Content | 用于 / Used by |
|---|---|---|
| `01-cherry-grove.jpg` | 樱花林群系提示 / Cherry grove biome message | `README.md` |
| `02-zombie.jpg` | 附近有僵尸 / Nearby zombie | `README.md` |
| `03-drowning.jpg` | 溺水 / Drowning | `README.md` |
| `01-cherry-grove.zh-CN.jpg` | 同上，中文 / Same, Chinese | `README.zh-CN.md` |
| `02-zombie.zh-CN.jpg` | 同上，中文 / Same, Chinese | `README.zh-CN.md` |
| `03-drowning.zh-CN.jpg` | 同上，中文 / Same, Chinese | `README.zh-CN.md` |

## 说明 / Notes

- 中英文各一套，用于说明气泡文本跟随 Minecraft 内的语言设置。
  Two sets, one per language, demonstrating that bubble text follows the
  language selected in Minecraft.
- 场景编号在两个语言中保持一致：1 = 群系，2 = 生物，3 = 溺水。
  Scene numbering is identical across languages: 1 = biome, 2 = mob, 3 = drowning.
- 游戏内按 F2 截图，文件位于 `.minecraft/screenshots/`。
  In-game screenshots via F2, stored in `.minecraft/screenshots/`.

## 重新拍摄时 / When re-shooting

```powershell
# 1.21.1 示例，逐条触发单个事件便于取景
/matesignal test biome
/matesignal test mob
/matesignal test drowning
```

`/matesignal test all` 会排队 13 个事件、每秒一个，不适合取景。
