# 139 · M84-N 投影 checkpoint 持久化

> 2026-11-13 21:4x（Asia/Shanghai）｜开发版仍是 **0.3.2 / versionCode 5**，本批未发布。
> 承接 H（投影值只在内存，App 重启靠「打开会话 → 重放整段日志」重新折）：这一片把
> `{sessionId, pluginId, key, stateVersion, lastSeq, state, view}` 落盘，重启后**只重投
> `seq > lastSeq` 的事件**。能力清单 `PluginCapabilities.VERSION = m84.6` 不变（27 条 26 implemented）。

---

## 一、做了什么

| 位置 | 变更 |
|---|---|
| `core:plugins/ProjectionCheckpoints.kt` | 新增纯 JVM 存储：单文件 `projection-checkpoints.json`，内存并发表 + 整份原子写盘（临时文件 + rename）；条目上限 400（超了按 `updatedAt` 丢最旧）；坏文件当空、不炸宿主；`removePlugin/removeSession/retainPlugins`。另有 `ProjectionCheckpointPolicy.usable(...)`（插件 id + key 仍声明 + stateVersion 一致 + lastSeq ≥ 0）。 |
| `PluginModule.RESIDENT_DISPATCH` | 新 `projection.snapshot`（每 cell 的 `state/seq/view/stateVersion`）与 `projection.restore`（灌回 cell）；`projection.event` 加 **seq 守卫**：`seq <= cell.seq` 的事件不再 fold（`apply` 不要求幂等），fold 后记录 `cell.seq` 与 `cell.view`。 |
| `PluginResidentRuntime` | 队列改成 `Event / Restore` 两类活；`attach` 与「运行时比会话晚起」共用 `replayInto`：有可用 checkpoint → 先 restore、seed 上次 view、只投 `seq > lastSeq`；否则整段重放。事件批处理完（队列空）落 checkpoint，节流 1.2s；`turn/end`、`tool/result` 强制落一次。停用插件时 `sync` 清它的 checkpoint；删除插件走 `forget()`；收尾中的 runtime 不再把 checkpoint 写回来（注册表守卫）。`dumpProjections` 增加 `checkpoints{enabled,total,current[]}`。 |
| `AppViewModel` | 构造时注入 `ProjectionCheckpoints(files/projection-checkpoints.json)`；`deletePlugin` 调 `forget`；`dumpProjections` 在带 `--es dsh_open_session` 时先等会话打开/恢复落定再写盘（调试通道，最多多等 3 秒）。 |

### 语义

```text
首次折叠某会话：  事件 1..N → cell{state, seq=N} → snapshot → 落盘 {lastSeq:N, state, view}
App 重启打开会话：restore(lastSeq=N) → 只投 seq>N → cell.state 从 N 继续折 → 再落盘
插件升级：        stateVersion 不一致 → 旧 checkpoint 整条作废 → 从会话开头重折
插件停用/删除：    checkpoint 清行（再启用从开头重折）
```

---

## 二、真机验收（OPPO A55 5G，PEMM00，Android 13）

会话 `6c909fb1-463f-4b23-95a6-c1efd824e8ad`（M84-M 那几轮），投影 `m84hTurns` / `m84iTurns`。

| # | 操作 | 证据 |
|---|---|---|
| 1 | 装 M84-N 后首次 `--es dsh_open_session <id>` | `replay plugin=m84h-demo ... events=14 checkpoint=none` → 折到 seq=13 → `checkpoint saved plugin=m84h-demo ... entries=[m84hTurns@13] total=1`（m84i 同） |
| 2 | force-stop 再打开同一会话 + dump | `checkpoint restore plugin=m84h-demo ... keys=[m84hTurns] lastSeq=13 replayed=0/14`、`checkpoint restored ... entries=1`；dump 里 `values` 有 `seq=13 view={"turns":1,...,"events":6}`、`checkpoints.total=2`（`docs/m84n-3.json`） |
| 3 | 同一会话再跑一轮（`M84N1`，provider=m84m.agent） | restore 后第一条新事件 `seq=14` 折出 **`turns=2, events=7`**（在旧值 1/6 上累加，证明 state 真恢复而不是重折）；随后 seq=16/17 继续折 |
| 4 | 再跑一轮（`M84N2`） | 回合边界强制落盘：`checkpoint saved ... [m84hTurns@19]`、结束时 **`[m84hTurns@25]`** / `[m84iTurns@25]` |
| 5 | force-stop 再打开 + dump | `checkpoint restore ... lastSeq=25 replayed=0/26`（两个插件都是 0 条重放）；dump `values seq=25 view={"turns":3,...,"events":12}`（`docs/m84n-4.json`） |
| 6 | `--es dsh_plugin_off m84h-demo` | `checkpoint dropped=1 (disabled plugins)`；最终 dump `checkpoints.total=1`（只剩 m84i，`docs/m84n-6.json`） |
| 7 | `--es dsh_plugin_on m84h-demo` 再打开会话 | `replay plugin=m84h-demo ... events=26 checkpoint=none`（旧 checkpoint 已清 → 从开头重折）→ `checkpoint saved ... [m84hTurns@25]`；m84i 仍 `restore lastSeq=25 replayed=0/26`（`docs/m84n-7.json`） |
| 8 | 设备上的 checkpoint 文件 | `docs/m84n-checkpoints.json`：两条 `{state, view, lastSeq:25, stateVersion:1}` |

单测：`ProjectionCheckpointsTest` 6 条（读写往返、按会话过滤/删插件、retain、上限丢最旧、坏文件、
Policy 的 stateVersion/key/lastSeq 判定）。

---

## 三、限制（照实说）

1. **只在打开会话后才折/存**：没打开过的会话不会产生 checkpoint（打开时整段重放一次并落盘）。
2. **节流 + 回合边界**：普通事件 1.2s 内只存一次，`turn/end`/`tool/result` 强制存；进程被杀/用户取消时
   最多丢最后一小段——重启多回放几条，不会错（seq 守卫）。
3. **stateVersion 不一致就整条作废**（从会话开头重折），没有迁移函数。
4. **单文件整份读写**：条目上限 400，超出按 `updatedAt` 丢最旧；没有跨进程锁（手机单进程够用）。
5. 投影状态以 `__dshJsonSafe` 之后的 JSON 落盘：函数/循环引用/undefined 会被清掉（和 wire 一样）。
6. 未做：`ui.registerView`（非上游预留）、M84-B 在 M/N 之后重跑（下一片）；本批未发版。
