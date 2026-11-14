# 169 · M85-F3：浮动窗 Surface 插口（去掉宿主对 ui.chat 私有组件的 import）

> 2026-11-14 09:3x。按 `docs/144` §8 / `docs/164` §4 的 M85-F 第三项：
> 浮动窗（`app/.../device/floating/**`、`FloatingWindowManager`、`FloatingFullscreenScreen`）
> 仍 import `ui.chat` 公共组件（C3-3c 记录的技术债）。本片把共用 UI 组件下沉到共享设计模块，
> 并给面板/全屏页加 **Surface 内容插口**：插件可以用 18 原语提供整块悬浮窗内容，
> 不提供时继续用原生悬浮窗界面（**不删原生聊天页**）。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13）；本批未发布（0.3.2 / versionCode 5）。

---

## 1. 结论

1. **宿主不再 import 私有聊天 UI**：`QuotedAttachment` / `parseQuotedAttachment` /
   `QuotedAttachmentChip` / `consumeInnerScroll` 从 `:ui:native-shell` 的
   `xin.dshaa.nativeapp.ui.chat` 下沉到 `:ui:design` 的 `xin.dshaa.ui.design.chat`；
   `app/device/floating/**` 只 import 共享设计模块，`ui:shell-api` 的契约类型不变。
2. **浮动窗面板/全屏页有 Surface 插口**：`FloatingSlotPlugin.surfaceTree(slot)` 返回 Surface 树时，
   宿主用 `SurfaceTreeView` 渲染整块内容，动作经 `onSurfaceAction(slot, action)` 回到插件；
   返回 null 时走原 `FloatingWindowScreen` / `FloatingFullscreenScreen`（默认行为不变）。
3. **演示插件可换面板**：`builtin.floating.demo` 启用后面板显示「悬浮窗面板 · Surface 插口」
   （插件用 text/divider/button 拼的），点「测试动作」写 `DshFloatingSlot` 日志；
   停用插件后立即恢复原生聊天面板。
4. **M84-B 142/142 PASS**（M85-F3 APK，见 §4）。

---

## 2. 实现

### 2.1 组件下沉（去掉宿主的私有 UI import）

- `git mv` 两个文件到 `:ui:design`：
  - `ui/native-shell/.../ui/chat/QuotedAttachment.kt` → `ui/design/.../ui/design/chat/QuotedAttachment.kt`
  - `ui/native-shell/.../ui/chat/NestedScrollGuard.kt` → `ui/design/.../ui/design/chat/NestedScrollGuard.kt`
- 包名改为 `xin.dshaa.ui.design.chat`；`ChatScreen` 补 3 个 import；
  `FloatingFullscreenScreen` / `FloatingWindowScreen` 改为 import 共享模块。
- 结果：`app` 源码对 `xin.dshaa.nativeapp.ui.chat.*` 的 import 为 **0**；
  浮动窗仍保留自己的聊天 UI（原生实现不删），只是不再依赖内置 UI 插件的私有包。

### 2.2 浮动窗 Surface 插口

- `FloatingSlotPlugin` 新增：
  - `fun surfaceTree(slot: FloatingSlot): SurfaceTree? = null`；
  - `fun onSurfaceAction(slot: FloatingSlot, action: String): Boolean = false`。
- `FloatingSlots` 新增 `surfaceTree(slot)`（后注册覆盖）与 `handleSurfaceAction(slot, action)`；
  `revision` 变化时浮动窗立即重组。
- `FloatingSurfaceSlot`（新）：用 `SurfaceTreeView` 渲染插件树；
  v1 数据源为空（静态 text/button/divider…），没有壳宿主的 chat 绑定。
- `FloatingChatWindow`：`WINDOW` / `FULLSCREEN` 分支先问插口，有树用 Surface，
  没有树走原生屏；球/气泡/OCR 不受影响。
- 演示：`FloatingDemoPlugin` 只给 `PANEL` 提供树（避免把全屏聊天整页换掉）；
  `FULLSCREEN` 走同一个入口，代码已接，未单独做演示插件。

---

## 3. 真机验收

| # | 场景 | 操作 | 结果 | 证据 |
|---|---|---|---|---|
| 1 | 启用演示插口 | `--es dsh_builtin_on builtin.floating.demo` | `DshFloatingSlot registered demo.floating` | logcat |
| 2 | 打开悬浮球/面板 | `--ez dsh_floating true` 后点球 | 面板显示「悬浮窗面板 · Surface 插口」+ 测试动作按钮（Surface 树，不是原生聊天面板） | `docs/shots/m85f-20-floating-surface-panel.png` |
| 3 | Surface 动作 | 点「测试动作」 | `surface action slot=PANEL action=floating.demo.action plugin=demo.floating` | `docs/m85f-f3-logcat.txt` |
| 4 | 停用恢复原生 | `--es dsh_builtin_off builtin.floating.demo` | 面板立即恢复原生聊天界面（同一窗口） | `docs/shots/m85f-21-floating-native-panel.png` |
| 5 | 源码扫描 | `git grep "xin.dshaa.nativeapp.ui.chat" -- app` | 0 命中 | `docs/m85f-source-scan.txt` |
| 6 | 无崩溃 / 无障碍 | 全程 | 无 `FATAL EXCEPTION` / `ANR in`；无障碍 Bound | logcat / dumpsys |

---

## 4. M84-B 142/142（M85-F3 APK）

- 脚本 `tools/m84b-page-check.py`，只改 `OUT`（`docs/m84b-check-m85f3.json`），142 条断言未改；
- 结果：**142/142 PASS**，time `2026-11-14 09:36:48`，原始 log `docs/m85f-f3-142.log`；
- 浮动窗 Surface 插口只在 demo 插件启用时生效（默认关），不影响页面归属/开关回归。

---

## 5. 文件

- 移动：`ui/native-shell/.../ui/chat/{QuotedAttachment,NestedScrollGuard}.kt` → `ui/design/.../ui/design/chat/`
- 新增：`app/device/floating/FloatingSurfaceSlot.kt`
- 修改：`app/device/floating/{FloatingSlots,FloatingChatWindow,FloatingDemoPlugin}.kt`、
  `app/device/floating/ui/{window/FloatingWindowScreen,fullscreen/FloatingFullscreenScreen}.kt`、
  `ui/native-shell/.../ui/chat/ChatScreen.kt`（import）
- 证据：`docs/shots/m85f-2*.png`、`docs/m85f-f3-logcat.txt`、`docs/m85f-source-scan.txt`

---

## 6. 没做 / 限制（不许当成已完成）

1. 浮动窗 Surface 内容**只支持静态树**：没有壳宿主的 chat/state 数据源，`bind` 在浮动窗读不到；
   需要绑定的聊天界面仍用原生实现（不删、不退化）。
2. `FULLSCREEN` 插口代码已接，但演示插件只注册 `PANEL`；全屏页的 Surface 替换没有单独真机截图。
3. JS `ctx.floating.*` 仍未开放（`docs/115 §E`）：插口目前由宿主 Kotlin 插件（内置 demo）注册；
   第三方 JS 注册悬浮窗内容留到后续批次。
4. 未发布；发布树/tag/Release 一律未动。
