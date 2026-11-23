# shell identity launcher（root 下的 uid 2000 launcher）

这个目录是 Operit 的 `tools/shell_identity_launcher` 源码副本（LGPL-3.0），用于修
「只有 root、没有 Shizuku 时虚拟屏失败」的根因：

- `su -c <cmd>` 得到的是 **uid 0 / magisk 域**；
- `shower-server.jar` 里的 `FakeContext` 自称 `com.android.shell`（uid 2000），
  `DisplayManagerService` 会校验 calling uid 与包名，于是建虚拟屏/`launchApp` 被拒；
- 本 launcher 以 root 启动，`setgroups` → `setgid/setuid 2000` → `setcon("u:r:shell:s0")`
  → `execvp` 目标命令，使 `app_process` 真正以 shell 身份运行。

## 产物与打包

- 预编译 ELF：`thirdparty/shower/src/main/jniLibs/arm64-v8a/libshower_shell_exec.so`
  - 来自 Operit `app/src/main/assets/operit_shell_exec`（21496 B，
    sha256 `40befdfb8e98a050330a1d91b2d17792539618784bb5c11d942bb2acabf221e9`）；
  - ELF64 / AArch64 / `e_type=ET_DYN`（PIE 可执行文件），**只放 arm64-v8a** 目录。
- 为什么叫 `.so`：AGP 只把 `jniLibs/<abi>/*.so` 按 ABI 打进 APK（`useLegacyPackaging=true`
  时会被解压到 `nativeLibraryDir`）。运行期由 `DeviceShell` 复制到 `filesDir/bin/operit_shell_exec`
  并 `chmod 700`，再以 `su -c "<launcher> <命令>"` 执行。x86_64 包没有这个文件，
  `DeviceShell` 会明确记录「launcher 不可用」并退回旧的 uid 0 路径（不会静默）。
- `add_executable` 的产物不会被 AGP 的 `externalNativeBuild` 打包，所以这里沿用 Operit
  的离线构建方式，而不是在 Gradle 里挂 CMake。

## 重新构建

```bat
thirdparty\shower\src\main\cpp\shell_identity_launcher\build_android.bat
```

脚本用 SDK 里的 CMake/Ninja + NDK 26.1.10909125 生成 aarch64 ELF，并覆盖
`jniLibs/arm64-v8a/libshower_shell_exec.so`。因为工具链版本与 Operit 的产物可能不同，
重新构建后的 sha256 不保证逐字节相同，但 ELF 架构与入口行为一致；改完请在真机 root 上
用 `su -c "<launcher> id"` 验证输出是 `uid=2000(shell)`（本仓库当前无 root 设备，未实测）。
