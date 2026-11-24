# 交接：把 Node 环境做成「完整的 DSH」

## ★ 2026-11-24 21:5x 最终裁定：**只换包**

用户确认：**不要进应用后再下载**；**接受 APK ≈ 490 MB、解压 ≈ 1.1 GB**；**只要包完整，不换内核**。
因此本文件 §三（四个决策）、§四 的 T2/T3/T6b、路线 A+/B 全部**作废**，只执行下面的「§四.0 换包六步」。
曾经卡住的 `node-addon-require-builtin` 只影响「跑真 dsh 内核」，与换包无关，**不要碰**。

### 四.0 换包（唯一要做的事）

1. 装完整生产树（注意：npm 11 加 `--os/--cpu` 会卡在解析，**不要加**）：
   ```powershell
   $d='D:\dsh\.tmp-full'
   New-Item -ItemType Directory -Force $d | Out-Null
   Set-Content "$d\package.json" '{"name":"dsh-full","private":true}'
   cd $d
   & "D:\New Folder\npm.cmd" install --omit=dev --ignore-scripts --no-audit --no-fund @deepseek-ai/dsh@0.2.2-rc.5
   ```
2. 把现有 `app/src/main/assets/node/runtime.zip` 解压到 staging：保留 `lib/`（10 个 arm64 .so）、`dsh/host.mjs`、`dsh/node-host-plugins.json`、`dsh/package.json`、`dsh/probe1.mjs`、`dsh/selftest.mjs`、`dsh/plugins/hello-plugin`；用完整 `node_modules` 替换 `dsh/node_modules`。
3. 打新 `runtime.zip`（Deflate Optimal 即可），记下**条目数**与**字节数**。
4. 替换 `assets/node/runtime.zip`；改 `NodeRuntimeInstaller.RUNTIME_VERSION = "node26.4.0-dsh0.2.2-rc.5-full1"`；`TOTAL_ENTRIES` 改成新 zip 的真实条目数（JVM 测试 `NodeRuntimeInstallerEntryCountTest` 会守住）。
5. `gradle :app:assembleDebug`（JAVA_HOME `jdk-21.0.11.10-hotspot`），报 APK 体积。
6. 模拟器验一次「解压 + App 启动」：x86_64 缺 `libnode.so`，Node 宿主 FAILED 属**预期**，不是换包失败；解压后的 `install.json` 条目/字节要对得上。

完成后把：新 zip 的字节/条目数、APK 字节、模拟器解压结果发用户。不升版本号、不打 tag、不动 `native-publish`。

---

> 写于 2026-11-24 晚，给下一个接手的 AI。**先读本文件，再读 `docs/192`（方案与全部实测数据）**。
> 目标（用户原话）：「node 环境弄成完整的 dsh 的 不要精简的」。
> 现状：Node 宿主 = 自写 `host.mjs` + 36 个 npm 包的剪枝树；要变成完整 DSH。
> 本文件只写「现在在哪、下一步做什么、不要做什么」；数字与论证在 `docs/192`。

---

## 一、已经做完的（不要重做）

| 事项 | 位置 | 状态 |
|---|---|---|
| 完整 DSH 方案（三条路线 A/A+/B、体积实测、Android 阻塞、任务包 T0–T8） | `docs/192-完整DSH运行时-方案与交接.md` | 完成 |
| 0.3.7 预修：取消 8192 输出上限、缓存命中率照 `dsh-token-meter`、安全模式 v2（连续 2 次开机失败 + 手动入口） | `docs/193-0.3.7预修-输出上限与安全模式.md`、提交 `acb162f`、`01c2ea4` | 完成 |
| PC spike：`dsh --profile sdk` 在 Windows 跑通 `initialize → session/query → session.event → shutdown` | `D:\dsh\.tmp-probe2\`（`probe-sdk.mjs`、`probe-tools.mjs`、`dshhome\`） | 完成，可复用 |
| 关键阻塞查明：`node-addon-require-builtin` 没有 android 包且启动必经 → 真 dsh 在 bionic 上一启动就抛 | `docs/192` §2.5 | 事实 |

---

## 二、事实快照（2026-11-24 实测，别凭印象改）

- 现状运行时：`app/src/main/assets/node/runtime.zip` **25,080,557 B / 2526 条目 / 解压 65,790,493 B / 36 个 npm 包**，`RUNTIME_VERSION = node26.4.0-dsh0.2.2-rc.5-p3.1`；arm64 `libnode.so` 49,715,528 B（Node v26.4.0 bionic）。
- 全量生产树（Windows 口径，`@deepseek-ai/dsh@0.2.2-rc.5`）：**95,327 文件 / 1,133.9 MB 解压 / 336.1 MiB zip（Deflate Optimal）**；win32 专属 265.4 MB；office 两套 kit 335 MB；去掉 office 后 zip ≈ 233.6 MB。
- 模拟器 `FBD_API30` 是 x86_64，**没有 libnode.so**（Node 宿主必 FAILED）。Termux x86_64 `nodejs` 26.13.1 deb = 11,218,604 B（`bin/node` 50,715,096 B，依赖 libc++/openssl/c-ares/libicu/libsqlite/zlib/libffi）；注意 Termux 的 ICU 是 **79**，现 arm64 包是 **78**，不能混搭。
- 关键类：`app/.../node/NodeRuntimeInstaller.kt`（解压/stamp/软链）、`NodeHostProcess.kt`（spawn/进程树/`HOST_READY`）、`NodeBridgeCore.kt`（WS 帧）、`NodePluginHost.kt`（状态机）、`NodeHostTools.kt`（9 个 `nodehost_*`）。

---

## 三、先问用户拍板（不要替用户决定）

`docs/192` §10 的四个问题，用户还没回答：

1. 能否接受**首次联网下载约 200–340 MiB**？
2. 能否接受**约 1.5 GB 磁盘**（zip + 解压）？
3. **office 两套 kit（335 MB）要不要**？
4. 走 **A（完整依赖树，保留现有宿主/内核）** 还是 **B（真 DSH 内核，等于换 App 的回合/会话/审批，且要先自编 arm64 `node-addon-require-builtin`）**？

如果用户说「按方案推进」→ 默认走 **A**（先 T1 实测 Android 口径，再下载器）；B 只在 T6b 之后单独拍板。

---

## 四、下一步（按顺序做，每步留证据）

1. **T1 测量（半天）**：在 Linux/arm64（docker/WSL/arm64 CI）装全量树，测 Android 口径的文件数/解压字节/zip 字节/SHA256，写 `docs/192-evidence/full-tree.md`。
   ```bash
   mkdir -p /tmp/full && cd /tmp/full
   echo '{"name":"dsh-full-runtime","private":true}' > package.json
   npm install --omit=dev --ignore-scripts --no-audit --no-fund \
     --os=linux --cpu=arm64 @deepseek-ai/dsh@0.2.2-rc.5
   ```
   `--os/--cpu` 是否真的筛可选依赖要实测；不生效就用 arm64 容器。
2. **T2 下载器**：`NodeRuntimeInstaller` 扩展 `fullRuntimeUrl/Sha256/Bytes` + 断点续传（OkHttp Range）+ 空间预检 + 解压到 `runtime/<v>-full/` + 失败回滚；**不能套用插件的 64/256 MB 导入限额**。JVM 用例覆盖：校验失败、空间不足、续传、并发互斥。
3. **T3 UI**：插件页 Node 区加「下载完整运行时（约 X MB）」确认、下载进度（复用 `NodeBootProgressUi` 五态，新增 Downloading）、取消、失败横幅 +「用精简版继续」。
4. **T4 模拟器**：Termux x86_64 deb → `jniLibs/x86_64/libnode.so` + 依赖 so；`runtime.zip` 的 `lib/` 按 ABI 分目录；`gradle :app:assembleDebug -PabiSplits`，在 `FBD_API30` 上验 `HOST_READY`。
5. **T6b 评估**：读 `node-addon-require-builtin` 源码与构建脚本，估「用 NDK 给 android arm64 编一个」的工作量（这是 B 路线的真正门槛）。
6. **T7 决策门**：把 T1/T4/T6b 的数字给用户，再进实现。

---

## 五、验收口径

- **A**：完整树能装（下载→校验→解压）、Node 宿主能起、一个 import 了任意 `@deepseek-ai/*` 的测试插件能加载；精简版可一键回滚；App 启动不受影响。
- **B**：PC 上 `initialize/session/query/审批 RPC` 已通（已完成）+ Android 上 `node-addon-require-builtin` 能加载；否则不算。
- 没有实测数字不写「通过」；模拟器 ≠ 真机，真机能力（bash/PTY/rg）要在 arm64 真机上另验。

---

## 六、红线（和 `docs/185`/`docs/189` 一致）

- 不把 336 MB 全量树打进 APK；**不静默联网下载**（必须确认 + 显示体积）。
- 不给上游 dsh 打补丁；不改 `host.mjs` 以外的 dsh 源码。
- 不升版、不打 tag、不动 `D:\dsh\native-publish`、不建 Release（除非用户另行要求）。
- 失败不能让 App 起不来：与安全模式（`docs/193` §5）联动，Node 运行时下载/解压失败只能回退精简版。
- 中文字符串只用 read/edit/write 改（PowerShell 5.1 会写坏）；设备 prefs 不读不输出 API key；`logcat` 只 `-d`，截图用 `cmd /c "adb exec-out screencap -p > x.png"`；测试禁 sleep 轮询。

---

## 七、环境

- 开发树 `D:\dsh\native`，HEAD **`acb162f`**，分支 `feat/integration-187`；构建：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'` + `.\.tools\gradle-8.9\bin\gradle.bat`。
- 无真机 adb；模拟器 `FBD_API30`（x86_64，`C:\Android\emulator\emulator.exe -avd FBD_API30`）；PC node v24.18.0，npm 用 `"D:\New Folder\npm.cmd"`（execution policy 拦 `npm.ps1`）。
- 上游参考树：`.upstream/oracle`（pnpm 全量 0.2.2-rc.1，329 个 `@deepseek-ai` 包）。

---

## 八、执行结果（2026-11-24 22:0x，§四.0 六步已完成）

- **完整生产树**：`npm install --omit=dev --ignore-scripts --no-audit --no-fund @deepseek-ai/dsh@0.2.2-rc.5`
  → 670 包 / **95,327 文件 / 1,189,028,936 B**（= 1,133.9 MiB），4 分钟。
- **staging**：旧 `runtime.zip` 解压（2,526 条 / 65,790,493 B）后**只换 `dsh/node_modules`**
  （2,170 文件 → 95,327 文件）；`lib/`、`host.mjs`、`node-host-plugins.json`、`package.json`、
  `probe1.mjs`、`selftest.mjs`、`plugins/hello-plugin` 全部原字节保留 → 95,343 文件 / 1,236,649,983 B。
- **新 `assets/node/runtime.zip`**：**375,820,757 B / 101,965 条**（6,622 目录 + 95,343 文件），
  Deflate level 9，SHA256 `BD884E69AF0443857EA771FAA912A8752AE21E2AD21C1B2A63FC6373B18092E2`；
  解压 1,236,649,983 B。条目数 > 65535，用 Zip64 EOCD（Android `ZipInputStream` 解压实测 OK）。
- **打包方式**：`D:\dsh\.tmp-runtime-new\ZipTool.java`（JDK 21 `java.util.zip.ZipOutputStream`，
  level 9、条目名一律 `/`、UTF-8、按相对路径排序，`host.mjs`/`lib/` 原字节 `CopyTo`；
  打完后 `ZipTool verify` 逐条 CRC 全过）。改 zip 必须同步 `TOTAL_ENTRIES`，`NodeRuntimeInstallerEntryCountTest` 守住。
- **Kotlin**：`RUNTIME_VERSION = "node26.4.0-dsh0.2.2-rc.5-full1"`、`TOTAL_ENTRIES = 101965`；其余代码未动。
- **JVM**：`:app:testDebugUnitTest --tests "xin.dshaa.nativeapp.node.*"` → 62 tests / 0 failures。
- **APK**：`app/build/outputs/apk/debug/app-debug.apk` **516,470,312 B**
  （APK 里 `assets/node/runtime.zip` 压缩为 349,144,710 B，解出与源 zip SHA256 一致）。
- **模拟器 FBD_API30（x86_64）**：装 0.3.6(9) → 启动自动解压
  `entries=101,965 bytes=1,236,649,983 in 59,231ms`；`install.json` 与常量一致，旧 p3.1 运行时被 prune，
  `data/node_modules` 软链建立。随后 `spawn failed: 缺少 …/lib/x86_64/libnode.so` 属**预期**；
  App 正常进「新会话」，横幅给出该原因（`docs/node-fulltree-emulator.png`）。
  验前模拟器上 `node_plugin_host_enabled` 是关的，已置 true（否则不会自动启动）。
- **真机（`adb connect 192.0.2.10:36091`，OnePlus PEMM00 / arm64-v8a / Android 13）**：
  同签名 `-r` 覆盖安装（516 MB 推装 93 s，数据保留）→ 启动自动解压
  `entries=101,965 bytes=1,236,649,983 in 74,319 ms`，旧 p3.1 prune，`install.json` 一致；
  随后 `node host spawned pid=11966` → **`node host ready version=v26.4.0 bootMs=1699 tools=4`**，
  `hello-plugin` 的 prompt/llm/commands 全部注册（2 provider、2 command、1 prompt section），
  Node 进程以 `libnode.so` 常驻，无 FATAL；界面无失败横幅（`docs/node-fulltree-realdevice.png`）。
- **文案/技能同步（22:30）**：解压弹窗（`NodeBootOverlay`）里「首次解压约 25MB」改成「约 1.2GB（约 1 分钟）」；
  Node 宿主 OFF / EXTRACTING 两处提示同步；`dsh-node-plugin-authoring`、`dsh-plugin-adapter` 的依赖说明
  改成「运行时 = 完整 dsh 生产树（`@deepseek-ai/…` 及传递依赖）」，`nodehost_install` 工具描述同步。
  真机装新包、删运行时强制重解压验证：弹窗显示 1.2GB，60 s 解完 → 宿主 READY（`docs/node-fulltree-extract-overlay.png`）。
- **工作区**：只改 2 个文件（`app/src/main/assets/node/runtime.zip`、`NodeRuntimeInstaller.kt`）；
  未升版本号、未打 tag、未碰 `native-publish`；`host.mjs` 未改。staging 保留在 `D:\dsh\.tmp-runtime-stage`（可复用于重打）。
