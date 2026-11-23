# Shell 身份 launcher（从 Operit tools/shell_identity_launcher 复制，LGPL-3.0）。
#
# 产物是 **可执行文件**（不是 .so）：以 root 启动后 setgroups/setgid/setuid 2000、
# setcon("u:r:shell:s0")，再 execvp 目标命令。AGP 的 externalNativeBuild 不会把
# add_executable 的产物打进 APK，所以本项目按 Operit 的方式离线构建，把 ELF 复制到
# src/main/jniLibs/arm64-v8a/libshower_shell_exec.so（按 ABI 目录随包，x86_64 包不带）。
# 详见同目录 README.md 与 build_android.bat。
cmake_minimum_required(VERSION 3.22.1)

project("shower_shell_identity_launcher")

add_executable(shower_shell_exec native-lib.cpp)

target_link_libraries(shower_shell_exec android log)

# 16 KB page size（Android 15+ 部分设备）：与核心 quickjs/terminal 的构建保持一致。
target_link_options(shower_shell_exec PRIVATE "-Wl,-z,max-page-size=16384")
