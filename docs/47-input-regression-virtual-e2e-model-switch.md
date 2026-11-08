# 47 · 输入框真机收口 + 虚拟屏工具全流程 + 模型下拉/用量行

日期：2026-11-08 00:09–00:35（第二十四棒）

## 一、模型配置输入框：真机回归发现两个新问题

上一轮（docs/46 §五）只编译过，没真机跑。本轮真机（PLT120 / Android 15，720×1570@320dpi）
逐项验证，结论：

已修好的（确认有效）：
- 最大输出：清空后输入 `123456`，编辑期一直是 `123456`，不再第一位就 clamp 成 1024；
  焦点移到下一项后落盘 `max_tokens=123456`；
- 模型名称：清空后编辑期保持空（不再立刻回填 `deepseek-flash`）；
  输入 `test-model-x` 失焦后落盘 `model=test-model-x`，回到 deepseek-flash 也正常。

本轮发现并修的两个：
1. **键盘没有「完成」动作**：`SettingsTextField` 的 `KeyboardOptions` 没给 `imeAction`，
   所以 subtitle 写的「点完成生效」不成立；按回车只是插入换行（被过滤掉），不落盘。
   → 三个输入框都加 `imeAction = ImeAction.Done`，`onDone` 里 `clearFocus()` 触发落盘。
2. **输入完直接按返回会丢编辑**：失焦才落盘，而返回键先收键盘、再退出页面，焦点不会先失。
   实测输入 `7777` 后连按两次返回，`max_tokens` 仍是旧值。
   → 加 `DisposableEffect.onDispose` 兜底：页面退出时把三个字段都提交一次（幂等）。
   现在「完成键 / 失焦 / 离开页面」三处都会落盘。

代码：`ui/settings/ModelConfigScreen.kt`（commit 输入框部分）。

## 二、虚拟屏 AI 工具：7 步全流程真机验证

用真机模型跑（display 20，1080×1920）：
1. `start_virtual_display`（不带 app）→ 返回 `display=20`；
2. `launch_app com.android.settings` → 虚拟屏进设置；
3. `capture_screenshot display=20` → `virtual-20-20261108-001537.png`；
4. `tap {display:20,x:496,y:140}` 点搜索框；`capture_screenshot` 确认；
5. `input_text {display:20,text:"关于手机"}` → `text set on display 20 (4 chars, clipboard paste)`，
   截图确认搜索框出现中文「关于手机」，并返回「关于手机管家」「关于本机」等搜索结果；
6. `press_key {display:20,key:"back"}` → 正常返回；
7. `close_all_virtual_displays` → 之后 `dumpsys display` 里没有 `ShowerVirtualDisplay`，
   `ps -A | grep shower` 为空，主屏不受影响。

结论：剪贴板粘贴路径对中文可用；`input -d` 的 tap/swipe/keyevent 都按 display 生效；
Home/返回的语义与 docs/46 一致。截图存 `docs/shots/47-virtual-e2e.png`。

已知：第一次跑时 turn 在 `input_text` 后无声结束（当时手机侧弹出 QQ/智能侧边栏抢了前台），
补发一条「继续 step5」就正常跑完；长任务在 App 退到后台时是否会被系统掐断，
下一轮要专门验（可能需要前台服务/唤醒锁兜底）。

## 三、输入条：模型下拉直接切 + 用量行

1. **最近模型**：`SettingsStore.recentModels`（prefs `recent_models`，换行分隔，最多 6 个）。
   只记录用户在模型配置页真正保存过的名字，不内置猜的 model id。
   `setModel()` 里自动 remember，最新的排最前。
2. **模型面板**顶部还是「当前模型」卡，下面多一段「最近使用」：点一下直接切。
   `AppViewModel.switchModel()` 同时改 `settings.model`（下一次请求用）和 `state.model`
   （顶栏副标题 + 输入条 pill）——以前只改 settings，顶栏会顶着旧名字（用户说的 header 不一致）。
3. **用量行**：`state.usageText`（引擎按轮 `setUsage`，格式 `213 in / 16 out`）显示在输入卡上方右侧，
   没有数据就不显示。对应 DSH 桌面输入条下面那行 stats 的移动端精简版。

截图：`docs/shots/47-model-switch.png`、`docs/shots/47-usage.png`。

## 四、首启权限引导复核（群反馈 8）

`--ez dsh_reset_onboarding true` 冷启重走：
- 欢迎页 → 权限清单页显示「已就绪 5/6」，Shizuku 已授权 / 悬浮窗 / 通知 / 所有文件访问打勾，
  无障碍「未启用」并有「去开启」按钮 → 结束页「可以开始了 · 还有 1 项没开，之后能在设置里补齐」。
  截图 `docs/shots/47-onboarding.png`。
- 复核时发现本机无障碍服务实际没启用（`settings get secure enabled_accessibility_services` 为空），
  已用 adb 写回 `xin.dshaa.nativeapp/.device.a11y.DshAccessibilityService`，
  `dumpsys accessibility` 确认 Bound services 里有「DSH 无障碍」。
  说明：无障碍被系统/清理关掉时，主屏 click/tap 会失败；虚拟屏走 shell 不受影响。

## 五、QQ 群

- 上一棒 00:07 已把 `DSH-native-20261108-0007.apk`（93.58MB）发到群 11316535。
- 本轮 00:28:33 把新包 `DSH-native-20261108-0035.apk`（93.58MB，00:20 构建）发到群，
  00:29 在群里同步了修复清单：输入框 Done/返回落盘、模型下拉切模型、用量行、
  虚拟屏 7 步全流程（含中文）、首启权限引导已在。
  （发送方式：QQ「文件」按钮 → 打开对话框里粘贴绝对路径 → 回车；直接点「发送(S)」会被
  坐标缩放影响，实际按钮坐标 = 预览图坐标 × 1920/1708。）
- 群里 00:20 前后有「@无忆报 哪种」「无忆报撤回了一条消息」等聊天；无新增技术反馈。
- 「还缺 Root 权限」（执笔画江山）：虚拟屏确实要 Shizuku/root；在本机 Shizuku 正常时全流程通过。
  不改方向、不承诺 root 方案；文档与工具描述已写明前置条件。

## 六、构建 / 验证

- `:app:assembleDebug` BUILD SUCCESSFUL，APK 98,124,413 B（00:20）。
- 真机装了同一包；输入框、模型下拉、用量行、虚拟屏 7 步均在本包上验过。
- 单测未重跑（本轮只改 app 层 UI/设置，无 core 改动）。

## 七、下一轮

1. 长任务后台存活：App 退后台/被系统冻结时 turn 是否中断；需要时加前台服务/唤醒锁。
2. 输入条继续贴 Operit：长按发送菜单（需先找 Operit 对应实现，没找到就不发明）、
   上下文占用百分比（要模型 context window 数据）。
3. 推理强度「彻底关闭」（群反馈 9）：要确认上游 `output_config.effort` / `thinking` 的关闭语义，
   不假做 UI。
4. 插件本地导入（群反馈 4）、图标/菜单继续统一（反馈 3）、项目页对齐 Operit 2（反馈 5）。
