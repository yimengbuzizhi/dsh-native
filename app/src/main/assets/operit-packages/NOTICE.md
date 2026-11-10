# Operit 自带工具包（ToolPkg）—— 来源与许可

本目录的 `*.js` 文件**原样**取自 Operit：

- 上游：https://github.com/AAswordman/Operit
- 路径：`app/src/main/assets/packages/*.js`
- 检出提交：`d16211b`（2026-11-06 克隆到 `D:\dsh\native\.thirdparty-src\Operit`）
- 许可：GNU Lesser General Public License v3.0（见同目录 `LICENSE`）

鲸歌把这些文件当作「插件包」导入，由 `core/plugins/ToolPkg.kt` + `core/plugins/ToolPkgTools.kt`
提供的 `Tools.*` / `OkHttp` / `toolCall` 兼容层执行，**没有修改包内源码**。
安装入口在「包管理 → 包」tab（`app/data/OperitBundledPackages.kt`）。

支持范围与真机结论见 `docs/94-Operit包管理与Tools兼容层.md`。
