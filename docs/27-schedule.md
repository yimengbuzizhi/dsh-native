# 27 · schedule_*：会话内提醒（工具 16 → 20）

> 2026-11-07 02:5x｜基线 `3103f1c`（M9 聊天页 UX 之后）。
> 目标：补上「模型能不能让手机到点做事」这一环——提醒是手机 agent 最自然的主动行为，
> 也是 `06-功能对等清单` C 组 `schedule` 那一行；工具数从 16 提到 **20**。

---

## 一、一句话结果

真机 + 真账号，**全链路跑通**：

```
用户：用 schedule_create 建一个 90 秒后的提醒：标题「喝水」，内容「起来喝口水」
模型：schedule_create({"title":"喝水","prompt":"起来喝口水","after_seconds":90})
      → {"id":"sched-5900d694-…","status":"active","nextRunAt":"2026-11-06T18:39:07.698Z",
         "nextRunAtLocal":"2026-11-07 02:39:07"}
… 90 秒后（02:39，没有用户操作）…
App ：把 prompt 作为 user 消息投递进同一会话，并自动开新一轮
模型：schedule_list({}) → {"tasks":[],"count":0} → 回复「提醒已按时送达，一次性失效」
```

随后同一会话里再验三件套：`schedule_list`（拿到 id）→ `schedule_update`（改标题，`updatedAt` 变、
`nextRunAt` 保持）→ `schedule_delete`（`{"deleted":true}`）→ `schedule_list`（`count:0`）。

证据（`docs/shots/`）：
`m10-schedule-1.png`（模型建提醒 + 工具卡）、`m10-schedule-2.png`（到点自动投递 + 新回合）、
`m10-schedule-3.png`（一次性失效后的回复）、`m10-schedule-4.png`（每 10 分钟提醒 + list）、
`m10-schedule-5.png`（聊天页「更多 → 提醒」菜单）、`m10-schedule-6.png`（提醒面板）、
`m10-schedule-7.png`/`m10-schedule-8.png`（update/delete 回合）。

测试：`ScheduleTimeTest` 12 + `ScheduleToolsTest` 7 + `ScheduleStoreTest` 7 = **26 个全绿**。

---

## 二、契约来源：为什么是「提醒 API」而不是 npm 的 rrule 版

同一个问题有两份上游，必须选一份：

| 来源 | 工具 | 形态 |
|---|---|---|
| 桌面会话夹具 `prompt/0.2.2-rc.1/tools.json`（**我们一直在发**） | `schedule_create` / `schedule_list` / `schedule_update` / `schedule_delete` | 会话内提醒；参数 `after_seconds` / `every_seconds` / `daily` / `weekly` / `cron` / `at`；无 output schema |
| npm `@deepseek-ai/dsh-tool-schedule@0.2.2-rc.1`（`.upstream/oracle`） | `schedule_create/list/view/edit` | 宿主级任务；RFC 5545 rrule；跨会话 target；有 output schema |

**拍板：按夹具的提醒 API 实现。** 理由：
1. 契约 #2 的基准是「我们实际发出去的 schema」——`tools.json` 已经在发这四个工具；
   再按 npm 版实现会出现「schema 发 create/list/update/delete、实现却是 create/list/view/edit」的直接违约。
2. 桌面 0.2.2-rc.1 的 asar 里确实已经是 rrule 版（`schedule_edit` + `deliveryLimits`），说明夹具抓的是
   更早的桌面 composition；两个版本都「上游过」，但本项目对拍基线是夹具，先与基线一致。
3. 手机场景下「提醒投递到当前会话」比「宿主级跨会话任务」更贴合交互，也少一层会话创建/排队语义。

> 待办：等上游 stable 把 rrule 版定稿，再整体迁移（`upstream.toml` 是唯一版本源，见 docs/12）。

---

## 三、行为语义（按 tools.json 文案逐条实现）

### 3.1 触发方式

| 参数 | 语义 | 校验 |
|---|---|---|
| `after_seconds` | 创建后 N 秒一次性触发 | 整数、≥1 |
| `every_seconds` | 固定间隔，按创建时刻对齐（update 改间隔时按保存时刻重对齐） | 整数、**≥60**，否则 `frequency_too_high` |
| `daily{time,time_zone}` | 每天本地 `HH:mm:ss[.SSS]` | 时间可解析；时区是 `UTC`/IANA |
| `weekly{time,time_zone,weekdays}` | 指定 ISO 星期（周一=1…周日=7，不重复） | 1..7、去重 |
| `cron{expression,time_zone}` | 五段 Vixie cron（`*`、`N`、`N-M`、`N/S`、`N-M/S`、逗号列表；月/星期可用英文缩写） | 五段；日与星期都受限时**任一匹配**（Vixie 语义） |
| `at` | RFC 3339 带偏移字符串，或 `{date,time,time_zone}` | 必须在未来，否则 `not_future` |

每次创建/更新必须**恰好给一个** timing 参数（`invalid_timing`）。

### 3.2 夏令时（java.time + 显式缺口检测）

- 本地时间**不存在**（春季跳变）→ 跳过该次，顺延到下一个匹配日：
  `America/New_York` 每天 02:30，2026-03-08 跳过 → 03-09 02:30 EDT。
- 本地时间**重复**（秋季回拨）→ 只触发一次，取**较早**的瞬间：
  2026-11-01 01:30 只发 05:30Z，第二天的 01:30 才再发一次。
- 单测里两种情形都钉死（`ScheduleTimeTest`）。

### 3.3 投递

- 提醒属于 **App（宿主）**，持久化在 `files/schedules.json`，**不进 v4 会话**；会话只是投递目标。
- 到点后：把 `prompt` 作为 **user 消息**写进目标会话，并自动开新一轮（等价于用户自己发了一句话）。
- **停机/后台错过**：只补**最近一次**；投递后直接把 `nextRunAt` 推到未来（`every/daily/weekly/cron`），
  一次性（`after_seconds`/`at`）投递后 `active=false`。
- 多条同时到期 → 排队逐轮投递（上一轮结束再发下一条）。

---

## 四、原生实现

| 文件/模块 | 职责 |
|---|---|
| `core/model/Schedule.kt` | `ScheduleTiming` / `ScheduledTask`（宿主级任务）/ `ScheduleException`（稳定错误码） |
| `core/tools/schedule/ScheduleTime.kt` | 参数解析 + 校验 + `nextOccurrence()`（daily/weekly/cron/every/after/at）+ cron 解析 + 本地化渲染 |
| `core/tools/schedule/ScheduleTools.kt` | 四个工具：参数 → host → 稳定 JSON；`timing` 原样回显输入嵌套形状 |
| `core/tools/Tool.kt` | 新增 `ScheduleHost`（App 注入），`ToolContext.scheduleHost` |
| `core/session/ScheduleStore.kt` | `schedules.json` 的 CRUD + 投递记账（临时文件 + rename 原子写）+ `removeForSession` |
| `app/AppViewModel` | `scheduleHost`（当前会话 + 时间计算 + 持久化）；5 秒轮询 `tickSchedules()`；回合结束/打开会话时补投；删会话连带删提醒 |
| `app/ui/chat/ChatScreen` | 原来的「更多」按钮接上菜单：**提醒** → 面板（标题 + 下次触发 + 删除） |
| `app/build.gradle.kts` | minSdk 24 打开 core library desugaring（`java.time` 需要） |

输出 JSON（模型侧，本实现设计；夹具无 output schema 可对照）：

```json
{"id":"sched-…","title":"…","prompt":"…","timing":{"kind":"every_seconds","every_seconds":600},
 "sessionId":"…","status":"active","nextRunAt":"2026-11-06T18:50:20.619Z",
 "nextRunAtLocal":"2026-11-07 02:50:20","createdAt":"…","updatedAt":"…"}
```

- `schedule_list` → `{"tasks":[…],"count":n}`（只列 active）；`schedule_delete` → `{"id":…,"deleted":true}`。
- `nextRunAtLocal`：给了 `time_zone` 就按它渲染，否则按**设备时区**（用户看到的本地时间）。
  第一版曾固定 UTC，模型因此把 02:39 的提醒说成「UTC 18:39」——已修，见 `m10-schedule-1.png` 前后的差异。

---

## 五、测试与验收

- `core:tools`：`ScheduleTimeTest`（解析/错误码/DST 两种/ISO 星期/cron 日字段/every 对齐）12 个；
  `ScheduleToolsTest`（输出形状、错误码、list 只列 active、update 必填字段、delete、无 host、registry=20）7 个。
- `core:session`：`ScheduleStoreTest`（持久化重读、按会话隔离、改文本不动 nextRunAt、跨会话拒绝、
  投递推进/结束、removeForSession、坏文件降级）7 个。
- 真机：见 §一；`schedules.json` 落盘内容与 v4 `tool/result` 都逐条核对过（会话 dump）。

---

## 六、已知问题 / 下一步

1. **投递只在 App 前台、且目标会话打开时发生**（轮询 5 秒）。后台/杀进程不投递（不改系统闹钟/通知——
   红线：用户靠手机铃声起床，见交接文档八）。若要后台，下一步是 WorkManager（非精确、15 分钟粒度）
   或前台服务；都需先跟用户确认，不能碰 AlarmManager 的精确闹钟。
2. **没有 pause/resume**：夹具 API 只有 update（改 timing/title/prompt）与 delete；暂停语义等于删掉重建。
3. **输出 JSON 形状是自定的**（夹具无 output schema）：迁移到 rrule 版时要整体换掉。
4. **发现一个 v4 小 bug（非本功能引入）**：App 重启后打开旧会话再发消息，`turn` 从 1 重新编号
   （应为 2），`seq` 是对的。已记录，留给 `core:engine` 抽取时一起修。
5. 提醒面板只能看/删；没有「暂停」「立即触发」按钮（等上表第 2 条）。

---

## 七、顺手修掉的门 2 隐患

M8 文档说「夹具已按 16 工具重录」，但盘上的夹具实际是 **15 工具**（first request 16039 字节，
当前 16 工具请求 16431 字节，差值恰好 = `read_image` schema 的 392 字节）——所以 `verify` 从 02:07 之后
必然对不上。本轮用 20 工具重录（`record`）并 `verify` 通过：

```
run-1 114 行 vs run-2 114 行 → PASS
```

夹具里现在是 20 个工具、2 个请求；`verify/run` 结束仍会自动还原 App 设置（base_url=官方、approval=ask）。

---

## 八、顺带修的聊天页两处小问题

1. **打开旧会话不落到最新一条**：M9 的 `LazyColumn` 默认停在顶部，41 条导入会话/5000+ 消息一打开先看到几天前。
   修法：`LaunchedEffect(state.sessionId)` → `scrollToItem(lastIndex)` + `scrollBy(100_000f)`（最后一条是长回复时也能落到真底部）。
   证据：`m10-schedule-9.png`（修前：最新回复的开头）→ `m10-schedule-10.png`（修后：真正的底部）。
2. **审批白名单加 `schedule_list`**（只读）：ask 模式下让模型「看看有哪些提醒」不该弹窗；
   `schedule_create/update/delete` 仍然要批（写状态）。

工作台统计已随工具数变成 **20**：`m10-workbench-20tools.png`。

---

## 九、验收边界（本轮没重验的）

- M9 的**会话内搜索跳转**与**长按复制**：代码在 `3103f1c`，本轮只做了代码走读；真机上用 adb 坐标点搜索图标不稳
  （会落进系统手势区），留到有人值守时肉眼验一次。
- `cron` / `weekly` / `at` 只在 JVM 单测里钉死（含 DST 两情形），没有真机等到点：
  它们与 `daily` 共用同一个 `nextOccurrence()` 和同一条投递链路，而投递链路已被 `after_seconds` 真机验证。
- 设备状态：测试后已由 `verify` 还原为 base_url 官方、approval=ask；本轮没有动闹钟/铃声/勿扰/通知。
