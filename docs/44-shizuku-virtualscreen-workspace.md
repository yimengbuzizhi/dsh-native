# 44 · Shizuku 真通道、虚拟屏修好、工作区可放任意目录（2026-11-07）

## 一、Shizuku：反射写的 `newProcess` 根本不存在

**症状**：权限页显示「Shizuku 已授权」，但虚拟屏一直黑/起不来，日志里：

```
W/DeviceShell: Shizuku exec failed, falling back to app shell
W/DeviceShell: java.lang.IllegalStateException: Shizuku.newProcess not found
E/ShowerServerManager: Failed to copy Shower jar to /data/local/tmp/shower-server.jar (cp: Permission denied)
```

**根因**：`DeviceShell` 之前用反射找 `rikka.shizuku.Shizuku.newProcess(String[], String[], String)`。
Shizuku 13 的 `api` artifact **没有这个方法**（`javap` 只有 `getBinder/checkSelfPermission/requestPermission/pingBinder/transactRemote/...`），
于是每条 shell 命令都静默退化成 App uid —— 复制 jar、`app_process`、建虚拟屏全部失败。

**修法**：

- 加依赖 `dev.rikka.shizuku:aidl:13.1.5`（`IShizukuService` / `IRemoteProcess`，Maven Central 有）；
- `runShizuku` 改成：
  ```kotlin
  val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(Shizuku.getBinder()))
  val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
  ```
- `IRemoteProcess` 不是 `java.lang.Process`：自己起两个线程读 `ParcelFileDescriptor`，`waitFor()` 拿退出码；
- `initShizuku` 从反射改成 `Shizuku.addBinderReceivedListenerSticky / addBinderDeadListener`；
- `hasShizuku()` 从「binder 收到」改成「binder 收到 + `checkSelfPermission() == GRANTED`」
  （否则「未授权」时也报可用，接着所有命令失败，界面还显示 Shizuku ✓）。

## 二、虚拟屏

修完上面的 shell 通道后，`ShowerServerManager` 能复制 jar、`app_process` 能起来，虚拟显示创建成功
（logcat：`DisplayDevice: id=virtual:com.android.shell … ShowerVirtualDisplay`、`displayId=5`），画面能渲染。

剩下的问题是「启动后黑屏」，两层原因：

1. **虚拟显示是空的**：新 display 上没有任何 Activity，当然全黑。旧代码试图用
   `resolveActivity(ACTION_HOME)` 拉桌面进来，但桌面在虚拟屏里不渲染（ColorOS 桌面要求主屏）。
   改成 **App 选择器**：启动后弹出可启动应用列表（图标 + 包名），点一个就 `controller.launchApp(pkg)`；
   下次启动自动拉起上次那个（prefs `vs_last_app`）。
2. **离开页面再回来变黑**：`ShowerController` 和「运行中」标志原来都是 `remember`，页面重建就丢；
   而且 Surface 换了之后编码器还往旧 sink 推帧。改成：
   - `device/shower/VirtualScreenSession`：进程级单例持有 controller / running / videoSize / appLabel；
   - 页面重新进入时 `LaunchedEffect` 再调一次 `ensureDisplay(context, 1080, 1920, 320)`
     （同尺寸时内部走 `setVideoSink(existingId, …)` 复用 display，把新 Surface 接上）。

验证：`运行中 · 手机管家`，画面实时；截图/触摸注入走同一条 binder。

**遗留**：标题栏的返回/Home/最近键调 `controller.key(...)`，实测 Home 会把**主屏**切到桌面
（虚拟屏没跟着变）。下轮要么在服务端按 displayId 注入，要么这把三个键换成 `am start --display` 方案。

## 三、工作区：任意目录 + 目录选择器

用户原话：「新建工作区太麻烦了，而且不能在应用目录外建工作区；选工作区也仿 Operit 做。」

- `WorkspaceStore.create(name, path)`：`path` 为空 → 仍建在 `files/workspaces/ws-*`；
  非空 → 直接用用户目录（不存在就 `mkdirs`，是文件/不可写/建不了都给中文错误）。
  已存在的目录直接复用（不搬、不删）。
- `AppViewModel.createWorkspace(name, path)` 接上，返回错误文案。
- 新 `ui/workspaces/WorkspacePicker.kt`：
  - `CreateWorkspaceDialog`：名字 + 两个位置（应用私有目录 / 手机存储·自定义）+「浏览」；
  - `DirectoryPickerDialog`：自己实现的目录浏览器（返回上级 / 子目录列表 / 新建文件夹 / 可写提示）。
    **不用 SAF**：`ACTION_OPEN_DOCUMENT_TREE` 给的是 `content://`，而工具链要真实路径；
    权限页已经有「所有文件访问」，直接 `File` 遍历最干净；没授权时直接跳授权页。
  - 选到 `/storage/emulated/0` 时默认名字是「手机存储」（不会变成 `0`）。
- 抽屉的工作区面板与设置页的工作区管理共用同一个对话框。

真机验证：抽屉 → 工作区 → 新建 → 「手机存储 / 自定义目录」→ 选 `/storage/emulated/0` → 创建，
立刻切过去（会话数 0），工具根/技能根/@file 索引跟着换。

## 四、下一轮

1. 虚拟屏：Home/返回键按 display 注入；启动尺寸可配（现在写死 1080×1920/320dpi）。
2. 工作区：管理页展示路径用 `shortWorkspacePath()`；「打开目录」；导入已有目录时扫会话。
3. 无障碍开启后的读屏/截图回归；设备工具 tap/swipe 走 Shizuku 还是无障碍，需要一次真机对拍。
4. 夹具：工具表 42 个，门 2 的 5 个夹具请求 sha 全变，重录。
