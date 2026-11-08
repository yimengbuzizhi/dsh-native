# 41 · RikkaHub 外壳重做：聊天落地 + 抽屉会话列表 + 工作区切换

> 2026-11-07 晚｜基线 `e324572`｜用户指令（21:40）：
> **「仿照 RikkaHub 的外壳：App 打开直接落在聊天页；聊天页左上角三条杠 → 侧边栏；侧边栏里是当前工作区的会话列表，可以切换工作区；设置里也能切工作区。」**
> 并明确「看一下 RikkaHub 的源码」。
> 本轮仍**没有连接手机**（用户「先搬功能，先不测试」），验收 = 源码 / 构建 / 单测。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 落地页 | 工作台（Operit 抽屉导航） | **聊天页**（空草稿，第一条消息才落盘） |
| 左上角 | 返回箭头（回列表/退出聊天） | **☰ 打开侧边栏**（RikkaHub 同款） |
| 侧边栏 | Operit 式一级页列表（工作台/工具箱/…） | **当前工作区的会话列表**（按天分组、当前高亮、长按改名/删除）+ 工作区切换器 + 功能/设置/关于 |
| 工作区 | 只有隐式的 `files/workspace` | **一等公民**：清单持久化、抽屉/设置两处切换、新建/重命名/删除、按 `cwd` 过滤会话 |
| 其他页面 | 抽屉一级页 | 保留，收进抽屉底部「功能」面板（2 列网格） |
| 顶栏 | 返回 + 标题 + 搜索 + 更多 | ☰ + 标题/副标题 + **新对话** + 搜索 + 更多 |
| 返回键 | 聊天 → 关聊天回列表 | 抽屉 → 关；二级页 → 设置；一级页 → 聊天；聊天页 → 退出 App（子代理回放 → 父会话） |

---

## 二、RikkaHub 源码精读（AGPL：只学设计，不复制代码）

GitHub 直连 `git clone` 在本机被重置（`Recv failure`），`codeload` 走代理拿到 `master` zip，
解包在 `.thirdparty-src/RikkaHub`（gitignored）。读的是这些文件：

| RikkaHub 文件 | 读到的设计 | 我们的落点 |
|---|---|---|
| `ui/pages/chat/ChatPage.kt`（TopBar，L344-430） | 顶栏 = 左 ☰（胶囊底）+ 中间「标题 / 助手·模型」两行 + 右侧「新消息」和「⋯」；`BackHandler(drawerState.isOpen)` 单独处理抽屉 | `ChatScreen.kt` 顶栏：`Menu` 图标 + 标题/副标题 + `AddComment`（新对话）+ 搜索 + 更多 |
| `ui/pages/chat/ChatPage.kt`（`ModalNavigationDrawer` / `PermanentNavigationDrawer`） | 手机用 Modal 抽屉；大屏用常驻抽屉；抽屉内容完全复用 | 只做手机 Modal（`ModalNavigationDrawer` 包住整个外壳，任何页面都能从左缘滑出） |
| `ui/pages/chat/ChatDrawer.kt`（L300-465） | 抽屉 300dp；`Column(padding 8dp)`；顶部身份区 → 会话列表（weight 1）→ 底部圆形动作条（`DrawerAction`：primaryContainer 圆底 20dp 图标 + 小字） | `ui/shell/DshDrawer.kt`：304dp；品牌/助手行 → 工作区卡 → 新对话 → 会话列表 → 底部「功能/设置/关于」 |
| `ui/pages/chat/ConversationList.kt` | 日期分组头（`labelLarge` 粗体主色）；会话行 `combinedClickable`（点击 + 长按菜单）；选中态 `surfaceColorAtElevation(8.dp)`；行内 12dp/6dp 紧凑内边距 | 抽屉会话行：`RoundedCornerShape(10dp)`、选中 `brandSoft` + 主色文字、长按弹「重命名 / 删除（回收站）」；分组「今天 / 昨天 / 7 天内 / 更早」 |
| `AGENTS.md`（Design Language） | MD3 Expressive、大圆角、整行可点、少阴影 | 沿用我们已有的 `DshTheme2` token（不放宽到 Expressive API，避免 Compose BOM 不匹配） |

**许可证**：RikkaHub 是 AGPL-3.0，本轮**一行代码都没有复制**，只照信息架构/排版自己写 Compose；
NOTICE.md 的 RikkaHub 条目已写明「只参考设计，不复制任何代码」。

---

## 三、工作区语义（与上游 v4 对齐）

- **工作区 = 会话 `header.cwd` = 工具的可写根**（`ToolContext.workspace`）。不是新造的概念，
  只是把原来隐式的 `files/workspace` 显式化，允许多个。
- 目录布局：默认 `files/workspace`；新建的放 `files/workspaces/ws-<8hex>`（不透明 id，改名不用搬目录）。
- 清单：`files/workspaces.json`（`{workspaces:[{id,name,path}]}`）；当前选择存 `dsh` SharedPreferences
  的 `workspace_path`。
- 会话归属：`refreshSessions()` 用 `header.cwd` 过滤；解码失败/空 cwd 的兜底摘要归默认工作区。
- 切换工作区时**原地重绑**：`workspace`、`toolContext.workspace/cwd`、技能根（`FileSystemSkills`）、
  `@file` 索引（`FileReferenceSearch`）全部换到新目录；`ToolContext.workspace/skills` 因此从 `val` 改成 `var`。
  子代理用 `toolContext.copy(workspace=…)`，不受影响；正在生成的对话会先停止（`cancelStream`）。
- 历史位置：旧版导入/别的工作区留下的 `cwd` 自动出现在清单里（`managed=false`），可切换回看、
  不可改名/删除；目录不存在时 UI 标「目录不可用」。
- 删除：目录 → `files/workspaces/.trash/`，该工作区的会话目录 → `files/sessions/.trash/`（`SessionRepository.trashWorkspace`），
  **不真删**；默认工作区不可删。

---

## 四、界面结构

```
ModalNavigationDrawer (gesturesEnabled)
├─ Drawer: DshDrawer (304dp)
│   ├─ 助手头像 + 名字 + 模型/工具数
│   ├─ 工作区卡（名字 / N 个会话 / 目录不可用）→ WorkspaceSwitcherSheet
│   │     ├─ 全部工作区（当前打勾；管理/历史位置分组）
│   │     ├─ 新建工作区（对话框）
│   │     └─ 管理工作区 → 设置 → 工作区页
│   ├─ 新对话（brand 实心）
│   ├─ 会话列表（当前工作区；按天分组；点击打开；长按改名/删除；右上角进全部会话页）
│   └─ 底部圆形动作：功能 / 设置 / 关于
│         └─ 功能面板（2 列）：工作台/工具箱/会话/子代理/任务/终端/虚拟屏/无障碍/插件/设置
└─ Content (screenName == null ? ChatScreen : 各一级页/设置二级页)
```

- `Screen` 枚举保留（功能面板/调试通道共用）；聊天用 `screenName == null` 表示。
- 设置页新增「工作区」分组 → `WorkspacesScreen`（切换/新建/重命名/删除 + 说明 + 数据目录）。
- 聊天顶栏右侧新增「新对话」；`ChatScreen` 的 `onBack` 参数改成 `onOpenDrawer`。

---

## 五、改动的文件

| 文件 | 改动 |
|---|---|
| `app/.../data/WorkspaceStore.kt` | **新增**：工作区清单持久化、当前选择、默认工作区、新建/改名/移除 |
| `app/.../ui/shell/DshDrawer.kt` | **新增**：抽屉、工作区切换 BottomSheet、功能面板、会话长按菜单 |
| `app/.../ui/settings/WorkspacesScreen.kt` | **新增**：工作区管理页 |
| `app/.../ui/App.kt` | 重写外壳：聊天落地、抽屉包全 App、返回键语义、功能面板导航、SubScreen.Workspaces |
| `app/.../ui/chat/ChatScreen.kt` | 顶栏 ☰ / 新对话；`onOpenDrawer` 参数 |
| `app/.../ui/settings/SettingsScreen.kt` | 「工作区」分组 + 当前工作区名 |
| `app/.../chat/AppViewModel.kt` | `workspace` 变 var + 工作区清单/过滤/切换/管理；`allRootSessions`；`canGoBackFromChat` |
| `core/tools/.../Tool.kt` | `ToolContext.workspace` / `skills` 改 `var`（切换工作区原地重绑） |
| `core/session/.../SessionRepository.kt` | `trashWorkspace(cwd)`：按工作区把会话目录移回收站 |
| `core/session/src/test/.../WorkspaceTrashTest.kt` | **新增** 2 条单测（只移目标工作区 / 空工作区 no-op） |
| `docs/06`、`NOTICE.md` | 清单与许可证记录 |

---

## 六、验证

- `:app:assembleDebug` **BUILD SUCCESSFUL**（2026-11-07）。
- `:core:session:test` 通过（含新增 2 条）。
- 真机验收（等设备）：冷启动落在聊天页；☰ 打开抽屉；切工作区后会话列表变化、新会话 cwd 正确；
  设置→工作区 新建/改名/删除；删除后 `.trash` 有目录；长按抽屉会话改名/删除。

## 七、已知风险 / 没做

1. **没在真机跑过**：抽屉手势、BottomSheet 与输入法、切换工作区时的竞态都只有代码层保证。
2. 历史位置（旧版 cwd）切换后，工具写文件会失败（目录不存在/不属于本 App）；UI 有「目录不可用」提示，
   但没禁止发消息——发消息本身能跑，文件类工具会报错。后续可加「只读工作区」硬门。
3. 工作区重命名只改显示名，不改目录名（有意为之）。
4. 抽屉暂无会话搜索框（进「全部会话」页搜）；RikkaHub 的文件夹/置顶/排序没搬（v4 没有这些字段）。
5. 聊天顶栏的 ☰ 在子代理只读回放里也是 ☰（返回父会话靠系统返回键）；如果真机觉得别扭再换成返回箭头。
