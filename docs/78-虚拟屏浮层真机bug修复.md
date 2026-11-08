# 78 · 虚拟屏浮层真机 bug 修复（贴边黑屏 / 全屏不可用 / 大黑边）

> 2026-11-09 00:5x｜用户真机反馈（原话）：
> 「点那个缩小虚拟屏那个缩到缩小，然后再点回来，那个屏幕显示接有问题了」
> 「如果还有一个大黑边，那个大黑边在刚建立完那个QQ虚拟屏之后就有」
> 「这个时候点击那个显示全屏就整个手机屏幕都用不了了，点击都没有用」
>
> 结论：4 个问题（3 个用户主诉 + 1 个悬浮窗内部消息显示）已定位根因并修复，真机回归通过。
> 本轮是第四十八棒，m53。

## 一、复现路径

1. 让 AI 通过模型回合调 `start_virtual_display(app=QQ)`（真机已复现，预览窗出现在屏幕上）；
2. 点预览窗 → 中央控制层出现（贴边 / 全屏 / 关闭）；
3. 点「贴边」缩成右侧 96×96 把手，再点把手恢复 → **视频区永久黑，只剩左侧黑条**；
4. 此时点「全屏」→ overlay 铺满 720×1570，但视频区是透明/死的，**整块屏幕的触摸都被浮层吃掉**，
   看起来就是「手机点什么都没反应」。

## 二、根因

### 2.1 贴边再恢复 → 黑屏（主诉 1）

贴边时 `OverlayCard` 走的是 `isSnapped -> SnapHandle()` 分支，把包含 `ShowerSurfaceView` 的整棵
`Row` 从组合里移除：

- `surfaceDestroyed` → `ShowerController.setBinaryHandler(null)`，视频管线停；
- 恢复时新建 `ShowerSurfaceView` / 新 `ShowerVideoRenderer`，`setBinaryHandler` 只重放
  `earlyBinaryFrames` 里最近 ≤120 帧；这些帧**不含 SPS/PPS**（参数集只在开流时发过一次），
  新解码器 `MediaCodec.configure` 起不来 → 画面永久黑。

Operit 的做法是「贴边缩到 1dp 但保持附着」（docs/76 §十一 V18），我们上一轮没照做。

### 2.2 黑屏状态点全屏 → 整屏不可用（主诉 2）

全屏 overlay 是 `TYPE_APPLICATION_OVERLAY` 且不是 `FLAG_NOT_TOUCHABLE`，铺满整屏后所有触摸都
先落到它上面；视频区已经是死 surface，触摸既不转发也不响应。右上角三个控制键是唯一出口。
用户看到的就是「整个手机屏幕都用不了了」。

### 2.3 大黑边（主诉 3）

- 预览窗左边无条件留了 56dp 的自动化面板（`Color(0xCC111820)`），没有自动化任务时就是一条纯黑
  空条——用户说的「大黑边」。
- 同时窗口宽高分别取屏幕的 40%（288×602），而视频是 1088×1920（0.567），288/602=0.478，
  视频被横向压扁，QQ 文字变窄。

### 2.4 悬浮窗把内部注入消息当用户气泡（顺手发现）

`source.kind:"time-context"`（`Time sampled while preparing turn 1…`）、`goal`、`tool-jobs` 等是
宿主注入给模型的内部 user 消息；主界面 `ChatScreen` 有专门的小行样式，但 WINDOW / FULLSCREEN
两种悬浮窗直接按 USER 气泡渲染，整段内部 prompt 变成一大段蓝色气泡（真机截图 78-41 之前）。

## 三、修复

| 文件 | 改动 |
|---|---|
| `app/.../device/shower/VirtualScreenOverlay.kt` | ① 贴边时保持 `ShowerSurfaceView` 附着（缩到 1dp，CenterStart/End），不再把视频从组合里移除；② 自动化面板只在 `automationVisible` 时占 56dp；③ 视频区按真实宽高比算（高度取可用屏高 40% → 1088×1920 得到 341×602），视频尺寸晚到时重算；④ 默认位置改右下角（8dp margin，扣导航栏），避免和悬浮窗默认位置重叠；⑤ `hasDisplay=false` 页加「关闭预览」按钮，全屏断流也有出口；⑥ 自动化显示/隐藏时重排窗口 |
| `thirdparty/shower/.../ShowerController.kt` | 缓存最近 SPS/PPS；`setBinaryHandler` 先补发参数集，再重放 buffered frames（surface 重建 / 预览关掉再打开也能重新起解码器） |
| `app/.../device/floating/FloatingMessageFilter.kt`（新） | 内部消息判定：`goal` / `tool-goal` / `tool-jobs` / `subagent-settled` / `agent-message` / `plan-mode` / `skill-catalog` / `skill-invocation` / `time-context` / `compact-checkpoint` |
| `FloatingWindowScreen.kt`、`FloatingFullscreenScreen.kt` | 消息列表先 `filterNot { it.isInternalNotice() }`，悬浮窗只显示真人用户/助手/工具结果 |

第三方模块改动记录补在 docs/40 §2.3。

## 四、真机验证（2026-11-09 00:48–00:52）

设备 PLT120 / Android 15；debug APK；虚拟屏 1088×1920；QQ。

| 场景 | 结果 | 证据 |
|---|---|---|
| 修复前：AI 开的预览窗居中盖住悬浮窗，左侧黑条 | 复现 | `docs/shots/78-before-preview-overlap.png` |
| 修复前：贴边→恢复后视频消失，只剩黑条 | 复现 | `docs/shots/78-before-video-gone.png` |
| 修复前：该状态点全屏 → 整屏是透明浮层，只有控制键可见 | 复现 | `docs/shots/78-before-fullscreen-dead.png` |
| 修复后：AI 开的预览窗右下角 341×602，无黑边、画面不变形 | 通过 | `docs/shots/78-after-ai-preview.png` |
| 修复后：贴边 96×96 @ [648,920] | 通过 | `docs/shots/78-after-snap.png` |
| 修复后：左侧贴边 / 点把手恢复，画面仍在 | 通过 | `docs/shots/78-after-snap-left.png`、`78-after-restore-left.png` |
| 修复后：点把手恢复 341×602，QQ 画面仍在 | 通过 | `docs/shots/78-after-restore.png` |
| 修复后：全屏 720×1570，点 QQ 群能打开会话（触摸转发正常） | 通过 | `docs/shots/78-after-fullscreen-touch.png` |
| 修复后：全屏→贴边→恢复→再全屏，画面一直活 | 通过 | `docs/shots/78-after-fullscreen-again.png` |
| 自动化栏：453×602（341 视频 + 56dp 面板），3/5→5/5、暂停/继续、退出 | 通过 | `docs/shots/78-after-automation.png` |
| 退出自动化：回 341×602，面板消失，画面仍在 | 通过 | `docs/shots/78-after-automation-exit.png` |

日志证据（修复后，贴边/恢复/全屏循环期间）：

```
VirtualScreenOverlay: apply animate=true full=false snap=true target=648,920 96x96 screen=720x1570
VirtualScreenOverlay: apply animate=true full=false snap=false target=363,920 341x602 screen=720x1570
```

全程没有新的 `ShowerSurfaceView: surfaceDestroyed / surfaceCreated`（logcat 里只有 00:48:01 初次
创建的那一条），说明贴边时渲染管线一直活着。

## 五、已知限制 / 未做

1. 小窗预览**不转发触摸**（只有全屏转发），与 Operit 一致；想手动操作虚拟屏要进全屏。
2. 预览窗位置不持久化：进程重启后回右下角默认位置（拖动只在本次进程内保留）。
3. 预览窗和悬浮窗仍可手动拖到重叠；默认位置已尽量错开（预览右下、浮窗默认在上半屏）。
4. `ShowerController` 的 SPS/PPS 补发是防御性修复：主路径已经不再触发 surface 重建，本轮没有单独
   构造「关掉预览再打开」的用例验证（AI 侧没有再调 `start_virtual_display` 显示预览的稳定入口）。
5. 自动化条按钮视觉 30dp（Material 最小命中 48dp），实测可点；要更稳妥可以放大按钮。

## 六、命令速查

```bash
# 编译 / 装机
cd <workspace>\native
cmd /c "set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot&& .tools\gradle-8.9\bin\gradle.bat :app:assembleDebug --console=plain > build-debug.log 2>&1"
C:\Android\platform-tools\adb.exe install --no-streaming -r -t app\build\outputs\apk\debug\app-debug.apk

# 悬浮窗 + 让 AI 开虚拟屏：在 WINDOW 模式输入框里发「open a virtual display with QQ」

# 不经过模型直接起虚拟屏 + 自动化栏（等 Shizuku binder，最多 6s）
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_virtual_automation true

# 看浮层状态 / 触摸前后 frame
C:\Android\platform-tools\adb.exe shell "dumpsys window windows | grep -A5 'Window #.*xin.dshaa.nativeapp}' | grep mAttrs"
C:\Android\platform-tools\adb.exe logcat -d | Select-String 'VirtualScreenOverlay|ShowerSurfaceView|setBinaryHandler'
```
