# 149 · M85-B2-3：ask_user_question 答题卡、plan 审批、goal 状态条

> 2026-11-13 23:1x。设计基线 `docs/144` §8 M85-B；上一片 `docs/148`（折叠/耗时）。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。
> `builtin.shell.whale` 升到 **1.3.4**；默认仍是 legacy 原生壳（`dsh_shell=native`）。

---

## 1. 这一片做了什么

把 `ask_user_question`（含 plan 审批）做成 Surface 答题卡，并把 goal 状态展示进壳；
仍然没有宿主聊天组件，只补了通用协议能力。

- **宿主命令 `answer`**：`{answers:[{id, selected[], custom}]}` → `resolveQuestions`；
  空数组 = 跳过全部（与 legacy「跳过」一致）；提交后清空通用 draft。
- **state 带完整问题**：`chat.pendingQuestions.items[]` 含 id/header/question/multiSelect/
  detail/options[]；`chat.view` 回传壳自己的选项状态，壳用 `Object.keys` 自己组 `selected`。
- **通用协议补充**：
  - `setShellView` 增 `clearPrefix`（单选组切换：清同前缀兄弟项，宿主不解释语义）；
  - `maxHeight` 布局属性（给需要内部滚动的 plan detail）；
  - `list lazy:false`（放在 `scroll` 里的小列表用 Column，避免 LazyColumn 嵌套无限高约束）。
- **壳 UI（1.3.4）**：
  - 问题卡：header、问题正文、选项 chip（单选/多选、选中态）、plan detail markdown（可滚动）、
    单题「其他回答」输入框（复用 `chat.draft`）、提交/跳过/停止；
  - 答题时暂时隐藏会话横条/消息列表/上下文/附件入口/输入区，让长 plan 也放得下；提交或跳过后恢复；
  - goal 条：objective + 阶段/轮次（`进行中 · 第 0/256 轮`），只读展示；
  - 计划模式徽标（`计划模式`）。

---

## 2. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 单选提问 | 模型调 `ask_user_question` → 问题卡「Color / Which color?」+ 三选项；选 Red → 提交 → 最终回复「You prefer Red」 | `m85b2-25-question-answered.png`、`m85b2-question-logcat.txt` |
| 2 | 多选提问 | 问题卡显示「可多选」；选 Red + Green → 提交 → 模型回复「You selected Green and Red」 | `m85b2-28-multiselect-answered.png`、logcat |
| 3 | 单选互斥 | 先点 Red 再点 Green → 只有 Green 保持选中（`clearPrefix` 生效） | `m85b2-question-logcat.txt`（`answerOption ... Red`、`... Green`） |
| 4 | 跳过 | 不选直接点「跳过」→ 工具结果为空答案 → 最终回复「You skipped the color question」 | `m85b2-35-question-skip.png` |
| 5 | 自定义回答 | 单题输入「Purple」→ 提交 → 模型回复「You answered Purple」（自由回答，不属于选项） | `m85b2-36-custom-field.png`、`m85b2-37-custom-typed.png`、`m85b2-38-custom-answered.png` |
| 6 | plan 审批 | `exit_plan_mode` → 「Plan review」卡：Approve / Keep planning + 完整 plan markdown（可滚动）；选 Approve 提交 → 工具成功、退出计划模式、下一 step 继续 | `m85b2-30-plan-card.png`、`m85b2-31-plan-options.png`、`m85b2-32-plan-approved.png` |
| 7 | 计划模式徽标 | 计划模式会话里显示「计划模式」；审批通过后消失 | `m85b2-30-plan-card.png`、`m85b2-32-plan-approved.png` |
| 8 | goal 状态条 | `--es dsh_goal` 新会话显示「M85B2 goal display test / 进行中 · 第 0/256 轮」 | `m85b2-29-goal-strip.png` |
| 9 | 无崩溃 | 连续答题/审批/goal/切壳，logcat 无 `FATAL`；dispatch 均 `COMPLETED` | `m85b2-question-logcat.txt` |

关键日志：

```text
DshBundled: builtin.shell.whale ... 1.3.4
DshShell: action=answerOption row=color_single<US>Red index=0
DshShell: tap action=answerSubmit
DshTurnHook: tool-end name=ask_user_question ... error=false chars=54
DshTurnHook: tool-start name=exit_plan_mode
DshTurn : turn end after 21138ms
```

单测：`:core:plugins:test` **119/119**；`:app:assembleDebug` 通过。

---

## 3. 明确没做 / 下一片

- **自定义回答只覆盖单题且无 plan detail**；多题同时自定义、多题里的「其他回答」未做（legacy 每題都有输入框）。
- **答题时是卡内模态**：会话横条/消息/输入区暂时隐藏；多题/超长 plan 在卡内滚动。
- **goal 只有只读展示**：暂停/继续/编辑/清空仍是 legacy 面板；壳不提供 goal 控制命令。
- **日程/提醒**显示未迁；后台会话「在等你」横幅未迁。
- **1000 条消息压测**未跑（B2-4）；两壳逐功能对拍清单未写（B2-4）。
- **默认壳仍是 native**：本片用 `--es dsh_shell builtin.shell.whale` 验收，验完回 `native`。

---

## 4. 文件

- 协议：`core/plugins/SurfaceSpec.kt`（`answer`、`maxHeight`、`list lazy`）、`SurfaceSpecTest.kt`
- 渲染：`app/ui/surface/SurfaceRenderer.kt`（Question/Option 行、`lazy:false`、`maxHeight`）
- 壳宿主：`app/ui/surface/PluginShellScreen.kt`（问题数据/选中态/自定义输入/`answer` 状态）
- 命令/state：`app/chat/AppViewModel.kt`（`answer`、`clearPrefix`、questions items、view、goal）
- 内置壳：`app/src/main/assets/builtin-plugins/builtin.shell.whale/{index.js,package.json}`（1.3.4）
