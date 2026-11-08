# 24 · goal 三件套：create/get/update + 目标条

> 2026-11-07 01:56｜基线 `add591c`（M6 旧版导入之后）。
> 目标：把 `docs/06` C 组里的 `goal` 从「未做」推进到「工具可用、持久化、UI 可见」，
> 同时把工具总数从 11 提到 **14**。
>
> **2026-11-07 更新**：§三.1（自动续轮）与 §三.2（`/goal` 面板）已由 `docs/28` 补齐；
> §三.3（目标进 system 上下文）、§三.4（blocked 收尾指令）仍未做。

---

## 一、一句话结果

真机 + 真实 API 跑通完整链路：

```
用户：先调用 get_goal 读取当前目标，然后调用 update_goal 把目标暂停（用返回的 id 和 revision）。
模型：get_goal            → {"goal":{"id":"goal-8c6ba110-…","revision":1,"phase":"active",…}}
     update_goal(pause)  → {"goal":{…,"phase":"paused","revision":2},"activation":"disarmed"}
聊天页顶部目标条：把原生版工具补到 20 个… ｜ 已暂停 · 第 0/256 轮
```

证据：`docs/shots/m7-goal-tools.png`（目标条 + 两张工具卡 + 模型汇报）。

单测：`:core:session:test` 的 `GoalStoreTest` **9 个**、`:core:tools:test` 的 `GoalToolsTest` **7 个**，全绿。

---

## 二、做了什么

### 2.1 领域模型（`core:model/Goal.kt`）

对齐上游 `@deepseek-ai/dsh-goal`：

| 类型 | 字段 |
|---|---|
| `GoalSnapshot` | `id / revision / objective / phase(active·paused·blocked·complete) / maxGoalRounds / blockedReason?` |
| `GoalView` | snapshot + `roundsStarted / createdAt / updatedAt / activation(armed·disarmed)` |
| `GoalException(code, message)` | 稳定错误码（`GOAL_STALE_REVISION` / `GOAL_INVALID_TRANSITION` / …） |

### 2.2 持久化与折叠（`core:session/GoalStore.kt`）

每条变更写一条 `goal/change`，形状**取自真机旧会话实测**：

```json
{"type":"goal/change","seq":405,"time":…,"data":{"kind":"goal/change","version":1,
 "operation":"create",
 "goal":{"id":"goal-d5a1a9db-…","revision":1,"objective":"…","phase":"active","maxGoalRounds":60},
 "roundsStarted":0,"createdAt":…,"updatedAt":…}}
```

- 读 = 扫最后一条 `goal/change`（`operation:"clear"` 是墓碑 → 当前无目标）；
- `roundsStarted` = 事件里的基数 + 之后 `user/message.source.kind="goal"` 且 `goalId` 匹配的条数
  （与上游 projection 同一规则）；
- `activation` 不落盘：`active && rounds < max` → `armed`，否则 `disarmed`。

### 2.3 工具（`core:tools/goal/GoalTools.kt`）

schema 来自 `prompt/0.2.2-rc.1/tools.json`（无需手写），实现只做行为：

| 工具 | 行为 |
|---|---|
| `get_goal()` | 无目标 → `{"goal":null}`；否则紧凑 JSON（id/revision/objective/phase/roundsStarted/maxGoalRounds[/blockedReason] + activation） |
| `create_goal(objective, max_goal_rounds?)` | objective trim 非空；cap 默认 256；已有目标 → `GOAL_ALREADY_EXISTS` |
| `update_goal(goal_id, revision, action, …)` | 先比对 id+revision（过期 → `GOAL_STALE_REVISION`），再按 action 改；每次成功 revision+1 |

action 细节（与上游 README/代码对齐）：

- `edit`：objective / max_goal_rounds 至少一个有效（空串、0 视为省略）；
- `pause`：仅 active → paused；
- `resume`：**paused 只能由用户恢复**（模型调用报 `GOAL_TOOL_RESUME_PAUSED`）；blocked → active；active → 报错；轮次耗尽 → 报错；
- `complete`：任意非 complete 阶段 → complete；
- `blocked`：必须给非空 `blocked_reason`；**连续 goal 轮次 < 3 一律拒绝**（`GOAL_TOOL_BLOCK_THRESHOLD`，与上游 `blockedAfterConsecutiveRounds=3` 一致）；成功后写 `blockedReason{code:"model-reported", message}`。

失败返回 `ToolResult.err(message, meta={code})`——模型看到的文案与上游一致，UI 侧可拿到稳定错误码。

### 2.4 会话接入（`app`）

- `AppViewModel` 挂 `GoalHost`（读写当前 `SessionSession`），注入 `ToolContext`；
- `openSession` / 每轮 `finally` 重新折叠 goal → `ChatSessionState.goal`；
- 聊天页顶栏下方新增**目标条**：图标 + objective（单行）+ `阶段 · 第 N/max 轮`（active 蓝 / paused 橙 /
  blocked 红 / complete 绿）；blocked 时追加原因；
- 调试通道：`--es dsh_goal "<目标>"` 新建会话并写 active goal；和 `--es dsh_send` 一起用时在**同一个**
  goal 会话里发送（真机验收用）。

### 2.5 提示词

`tools.json` / `sections.json` 里本来就有 `tool:goal` 段（`requires: create_goal`）。
注册工具后 `PromptPack.systemFor()` 自动把该段拼进 system——与上游按工具集动态挂载的行为一致，
不需要手工加提示词。工具总数 14，system 里也相应出现 goal 指引。

---

## 三、没做的（诚实清单）

1. **goal-round-driver（自动续轮）没做**：goal 目前是「持久目标 + 状态机」，不会在回合结束后
   自动发下一条 goal 轮次；所以 `roundsStarted` 恒为 0，`blocked` 在真机上暂时无法触发
   （阈值 3 的判定本身已实现并有单测）。上游 `dsh-goal-round-driver` 的调度/steering/
   activation 语义留到下一步。
2. **`/goal` 命令与 goal 控制面板没做**：恢复 paused 目前只能改文件或后续加 UI（模型被明确禁止 resume paused）。
3. **goal 没进 system 的「当前目标」上下文**：上游把 goal 放在 projection 里，模型每轮能看到进度；
   我们现在只在工具返回里给。
4. **blocked 的「结束指令」**（`<goal_complete>/<goal_blocked>` 让模型收尾）没做。

---

## 四、踩到的坑

1. **Kotlin 属性初始化顺序**：`toolContext` 先于 `goalHost` 声明 → `Variable 'goalHost' must be initialized`；
   把 host 提到 context 前面即可（对象方法体里引用 `handle` 没问题，初始化时不求值）。
2. **`ToolResult.err` 没有 meta 参数**：goal 工具需要带 `code`，把 companion 的 `err` 扩成
   `err(text, meta = emptyMap())`（向后兼容）。
3. **调试通道互斥**：最早写成 `dsh_goal` 与 `dsh_send` 二选一，结果 goal 会话根本没被创建，
   模型 `get_goal` 读到 null。改成「先建 goal 会话，再在同一会话 send」。
4. **真机前台被 QQ 抢走**：安装后 `am start` 偶发没到前台（任务还在但 top 是 QQ），
   `am start` 不带 extras 直接 `bring to front` 即可，别误判成崩溃。
5. **真实 API 下模型会重复调 `get_goal`**：第一次 goal 为 null 时它又读了一次再解释；
   工具本身没问题，属于模型行为。

---

## 五、怎么验

```powershell
# 单测
.tools\gradle-8.9\bin\gradle.bat :core:session:test :core:tools:test

# 真机（debug 包；会自动批准工具，验完记得 --ez dsh_ask_approval true 还原）
adb shell am start -n xin.dshaa.nativeapp/.MainActivity `
  --es dsh_account_token <token> --ez dsh_auto_approve true `
  --es dsh_goal "把原生版工具补到 20 个，并逐条真机验收" `
  --es dsh_send "先调用 get_goal 读取当前目标，然后调用 update_goal 把目标暂停（用返回的 id 和 revision）。只做这两步。"
```

---

## 六、下一步

1. `goal-round-driver`：回合结束后按 activation 自动续轮（带 maxGoalRounds 上限、用户 interrupt 立即停）。
2. `blocked` 收尾指令 + `/goal` 面板（恢复 paused、编辑目标、清空）。
3. 继续补工具：`read_image`、`web_search`、`list_agents`/`send_message`、`schedule_*`。
