# 21 · M4 收尾：present / 运行时上下文 / 抽屉统计 / 跟随滚动

> 2026-11-07 00:55｜基线 `5a27115`。

## 一、做完的

| 项 | 说明 | 证据 |
|---|---|---|
| `present` 工具 | 校验路径（最多 4 个、必须是文件），返回文本；UI 渲染成「已产出 N 个文件」卡：扩展名块 + 文件名 + 说明 + **打开**（FileProvider + ACTION_VIEW，按扩展名猜 mime） | `docs/shots/m4-present.png` |
| FileProvider | `AndroidManifest` 注册 `xin.dshaa.nativeapp.files` + `res/xml/file_paths.xml`（files-path 全量） | 同上 |
| 运行时上下文 | 每次请求在最前面带一条 user 消息（docs/port/02 §2.5 逐字模板）：`Current runtime context...` + 工作目录 + `danger-full-access` + 审批策略（ask / never 二选一映射） | 真机问「工作目录/沙箱策略」回答正确（`m4-runtime-context.png`） |
| 抽屉统计 | 三宫格改成 Operit 式「图标 + 数值 + 标签」（会话/工具/插件），工具数接真实值 | `m4-drawer-stats.png` |
| 聊天跟随滚动 | 只有贴底时才自动跟随；用户上翻后出现「回到底部」按钮 | 代码 |

工具总数 11（+present）；`androidApiTools()` 里 `present` 的 schema 直接来自 tools.json，无需改动。

## 二、踩到的坑

1. **PowerShell 5/7 的 `>` 重定向把二进制写成 UTF-16**：`adb exec-out ... > request.json` 得到双倍大小文件。
   正确做法：`cmd /c "adb ... exec-out ... > file"`，或 `adb pull`（本机 run-as 目录不能直接 `cp` 到 /data/local/tmp）。
2. **`Select-String -Path <dir>` 不支持 `-Recurse`**：要先 `Get-ChildItem -Recurse | Select-String`。
3. **`cd` 不影响 .NET API 的进程 CWD**：`[IO.File]::ReadAllText(相对路径)` 会解析到别的目录，一律用绝对路径。

## 三、下一步

1. `todo_write` 面板（现在只写 ctx.state，没有 UI）。
2. 门 2：录制 SSE 原始字节 + 回放器（本地端口）+ 差异报告。
3. 旧数据只读迁移（/sdcard/DeepSeekHarness 备份包）。
4. 门 4：Operit quickjs / terminal / showerclient 编入验证。
5. 主题系统（6 套预设 + token 覆盖）。
6. 会话列表「只读首帧」性能优化（现在有摘要缓存，但首次仍全量解压）。
