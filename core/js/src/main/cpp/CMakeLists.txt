cmake_minimum_required(VERSION 3.22.1)

project(dshquickjs C CXX)

# QuickJS 2026-06-04（MIT，源码 vendored 在 quickjs/，见 NOTICE.md）。
# 只编核心解释器 + 正则/Unicode/dtoa，不含 qjs/qjsc/libc（Android 上不需要 REPL 与 std）。
add_library(
    dshquickjs
    SHARED
    js_bridge.cpp
    quickjs/quickjs.c
    quickjs/cutils.c
    quickjs/dtoa.c
    quickjs/libregexp.c
    quickjs/libunicode.c
)

target_compile_definitions(
    dshquickjs
    PRIVATE
    _GNU_SOURCE
    CONFIG_VERSION="2026-06-04"
    NDEBUG
)

target_compile_options(
    dshquickjs
    PRIVATE
    # 引擎在解释器热路径上：debug app 也要 -O2；-fwrapv 是 quickjs Makefile 的要求。
    -O2
    -fwrapv
    -funsigned-char
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
)

set_target_properties(
    dshquickjs
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_link_libraries(dshquickjs log m)

# 16 KB page size（Android 15 起部分设备要求；与 Operit 的 quickjs 构建一致）
target_link_options(dshquickjs PRIVATE "-Wl,-z,max-page-size=16384")
