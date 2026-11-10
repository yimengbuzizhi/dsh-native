# 48 · 长任务后台保活（前台服务 + 唤醒锁）与思考强度 off

日期：2026-11-08 00:36–01:00（第二十五棒）

## 一、先复现：退后台本身不是必死

上一棒（docs/47）留下的疑问是「长任务在 App 退后台/被冻结时会不会无声结束」。本轮先用
**未改动**的包（04b2837 / 00:20 构建）做基线，两个场景都跑完了：

| 场景 | 结果 | 证据 |
|---|---|---|
| 发 3×`sleep 40` 长任务 → 按 Home → 等 2 分钟 | turn 完整跑完（Round1/2/3 done + long task finished） | 00:33–00:35 截图 + 会话文件 00:35 落盘 |
| 发同样任务 → 立刻锁屏（screen off）→ 200 秒 | turn 完整跑完，128.8 秒 | logcat + 会话文件 |

⚠️ 但这两个「没复现」不能当成没问题：

1. 本机 **DSH 无障碍服务是启用的**，进程被系统绑定，本身就不容易被 cached-app freezer 冻住；
   用户没开无障碍时进程就是普通 cached 进程，更容易被冻/被 LMK（App 包体 ~98MB，内存压力大）。
2. 上一棒那次「turn 无声结束」发生在 QQ/智能侧边栏抢前台时，可能对应进程被回收（无法事后取证）。
3. 定时提醒在**后台**触发的 turn，根本没有前台状态，最容易被掐。

结论：**照平台标准做法加保活**，而不是赌系统不杀。

## 二、实现：TurnKeepAliveService（specialUse 前台服务 + 唤醒锁）

新文件 `app/.../chat/TurnKeepAliveService.kt`，Manifest 里 `foregroundServiceType="specialUse"`
（复用已有 `FOREGROUND_SERVICE_SPECIAL_USE` 权限），本轮补上了 **`android.permission.WAKE_LOCK`**。

- **起停时机**（`AppViewModel.syncKeepAlive()`）：
  - `startTurn()` 起服务；turn 结束（finally）时重新算；
  - 只要「turn 在跑 / 后台 bash 任务在跑（`jobRegistry`）/ 子代理在跑（`agentRegistry`）」任一成立就保持，
    都结束才 `stopService`；三个来源的 `onChange` 都挂了同步。
  - `onCleared()`（Activity 真被销毁）兜底停掉。
- **唤醒锁**：`PARTIAL_WAKE_LOCK`，`acquire(2h)` 上限兜底，`onDestroy` 释放。
- **通知**：`dsh-turn` 渠道、`IMPORTANCE_MIN` + 静音、`setOnlyAlertOnce`，文案跟着 `setStatus`
  更新（如「思考中…」「运行 bash…」），带一个「停止」按钮（`stopListener` → `vm.stop()`）。
  通知 id 4101。
- **失败兜底**：Android 12+ 后台起前台服务会抛 `ForegroundServiceStartNotAllowedException`（比如定时提醒
  在后台触发），`runCatching` 吞掉并打日志——turn 照跑，只是没有保活，不阻塞功能。

第一次验证时 logcat 抓到：

```
W/DshTurn: wake lock failed: Neither user 10310 nor current process has android.permission.WAKE_LOCK.
```

→ 说明只加了代码没加权限；补上 `WAKE_LOCK` 后第二次验证不再报错。

## 三、验证证据（设备锁屏状态下，纯 adb）

设备 00:37 锁屏后进入「口袋防误触」并要求 6 位 PIN，本轮**无法解锁**（见 §五），
所以全部用 adb 取证，没有 UI 截图。

1. turn 运行中（00:42:47）：
   ```
   * ServiceRecord{... xin.dshaa.nativeapp/.chat.TurnKeepAliveService}
     isForeground=true foregroundId=4101 types=0x40000000
     foregroundNoti=Notification(channel=dsh-turn ... flags=ONGOING|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE actions=1)
   ```
2. 通知渠道：`mId='dsh-turn' mName=任务运行中 mImportance=2 ... mSound=null`
3. logcat（`DshTurn`）：
   ```
   00:42:38 turn start session=- model=deepseek-flash
   00:42:38 foreground start
   00:44:47 turn end after 128847ms
   00:44:47 foreground stop
   ```
   —— 锁屏 + 口袋模式 + 前台服务，128.8 秒的长任务完整跑完，服务按预期自动收掉。
4. 安装后 `dumpsys package`：`android.permission.WAKE_LOCK: granted=true`。

## 四、思考强度「彻底关闭」（群反馈 9）：off 是真实语义，不是假 UI

**上游取证**（`.upstream` 里没有，从 `D:\dshdsh\resources\app.asar` 里抽的
`@deepseek-ai/dsh-llm-deepseek/lib/index.js`）：

```js
const effort = options.reasoningEffort ?? ... ?? "high";
// 允许值只有 off / low / high / max
thinking: { type: effort === "off" ? "disabled" : "enabled" },
...effort === "off" ? {} : { output_config: { effort } },
```

即：**off ⇒ `thinking:{type:"disabled"}` 且完全不发 `output_config`**；默认 high。
这就是群友要的「彻底关闭」，不是把 UI 选项藏掉。

改动：

- `core/engine/TurnEngine.kt`：`thinking` / `outputConfig` 按 effort 组装（off 时 outputConfig=null）。
- `app/.../SettingsStore.kt`：`reasoning_effort` 归一化到 off/low/high/max，脏值回落 high。
- `app/.../ui/settings/ModelConfigScreen.kt`：「思考强度」改成**点开弹层四选一**
  （关闭 / 低 / 高（默认）/ 最高，说明文案照抄上游 REASONING_EFFORTS），不再一档档转。
  起因是发群里之后 beca_se 当场说「感觉思考强度的调节太麻烦了」——第一版是四档循环，确实难用，
  这版直接改成弹层（docs 里留个记录：群反馈先复现/理解再改，这条改得对）。
- 单测：`TurnEngineTest` 新增 `reasoningOffSendsDisabledThinkingAndNoOutputConfig`、
  `reasoningHighKeepsThinkingEnabledAndEffort`（`:core:engine:test` 通过）。

**真机请求体验证**（`--es dsh_dump_request`，prefs 临时改成 off）：

```json
{"model":"deepseek-flash", ..., "max_tokens":256000, "thinking":{"type":"disabled"}, "system":"...", "tools":[...]}
```

没有 `output_config` 字段；这一轮 1.8 秒正常结束（真实 API 接受 disabled）。
验完已把 `reasoning_effort` 恢复成用户原来的 `low`。

## 五、设备状态（交接必读）

- 00:37 我用 `input keyevent KEYCODE_POWER` 做锁屏实验，之后手机进入「口袋防误触模式」
  （提示「请移除遮挡物」），并且本身有 6 位 PIN —— 我**没有密码也无法绕过**，
  所以本轮后半段所有操作只能走 adb（`am start` 带调试参数、读会话文件、logcat、dumpsys）。
- 影响：**本轮没有 UI 截图**。设置页「思考强度」四档、通知栏保活通知的视觉，要等用户解锁后补。
- 下一棒开机先确认设备已解锁；如果还锁着，别反复试密码，直接问用户。

## 六、构建 / 测试 / 发包

- `:app:assembleDebug` BUILD SUCCESSFUL（首包 00:45，含保活+思考强度四档循环；
  00:52 重新构建，含思考强度弹层四选一），APK 98,124,413 B，已装机（`WAKE_LOCK` granted）。
- `:core:engine:test` 通过（含新增两条）。其余 core 模块本轮未改。
- 真机装的是 00:52 包；上面 §三/§四 的证据里：服务/通知/锁屏长任务在这之前一版（含保活）上取的，
  请求体（thinking disabled）在 00:46 那一版上取的；两版请求组装代码一致。
- QQ 群（11316535）：
  - 00:50 先发了 `DSH-native-20261108-0048.apk`（保活 + 四档循环）。
  - 00:55 发了 `DSH-native-20261108-0052.apk`（思考强度改弹层四选一），并补了一段说明：
    长任务保活、思考强度可关闭、WAKE_LOCK 权限。
  - 群文件里现在有 3 个包：0052（新）、0048、DSH.1（0048 的重复上传，QQ 自动改名；内容同 0048）。
  - 群里 00:48–00:55 一直有人说话（beca_se、克莱、执笔画江山、安可然），在聊
    「大部分 dsh 插件不用改太多」「适配复杂」「终端居然是 Operit 的」等，没有新 bug 报告。
  - 发文件的办法（本轮再确认一遍）：聊天工具栏「文件夹」图标（窗口内约 (320,383)）→「打开」对话框 →
    文件名框里贴绝对路径 → 回车。**回车就会立刻上传并发到群里**，不需要再点「发送(S)」；
    之前多点一次造成了 DSH.1 这个重复文件。

## 七、下一轮

1. **补截图**：设置页思考强度四档、通知栏保活通知、长任务跑的时候切后台（用户解锁后）。
2. 插件页本地导入（群反馈 4）：照 Operit 插件结构，先做「从本地文件导入」，别承诺「自带代理」。
3. 项目/工作区页对齐 Operit 2（反馈 5）、图标/菜单统一（反馈 3）。
4. 输入条长按发送菜单：没找到 Operit 对应实现前不要发明。
5. 群反馈先复现再改；不泄露令牌/密钥/内部路径。
