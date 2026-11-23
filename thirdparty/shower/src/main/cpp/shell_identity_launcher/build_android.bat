@echo off
setlocal enabledelayedexpansion

rem Build the shell-identity launcher (aarch64 executable) with the Android NDK and
rem place it into thirdparty/shower/src/main/jniLibs/arm64-v8a/libshower_shell_exec.so.
rem
rem The file is named .so only so AGP packages it into the arm64-v8a ABI directory;
rem its ELF type stays e_type=ET_DYN (PIE executable), which is what DeviceShell execs
rem after copying it out of nativeLibraryDir to filesDir/bin.
rem
rem Usage (from anywhere):
rem   thirdparty\shower\src\main\cpp\shell_identity_launcher\build_android.bat
rem Optional: set ANDROID_NDK_HOME / ANDROID_SDK_ROOT first.

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=C:\Android"
if not defined ANDROID_NDK_HOME (
    for /d %%D in ("%ANDROID_SDK_ROOT%\ndk\*") do set "ANDROID_NDK_HOME=%%D"
)
if not defined ANDROID_NDK_HOME (
    echo [ERROR] ANDROID_NDK_HOME is not set and no NDK was found under %ANDROID_SDK_ROOT%\ndk
    exit /b 1
)
echo Using ANDROID_NDK_HOME=%ANDROID_NDK_HOME%

rem Locate CMake/Ninja shipped with the SDK (do not touch global PATH).
set "NINJA_EXE="
for /d %%C in ("%ANDROID_SDK_ROOT%\cmake\*") do (
    if exist "%%C\bin\ninja.exe" (
        set "NINJA_EXE=%%C\bin\ninja.exe"
        set "CMAKE_EXE=%%C\bin\cmake.exe"
    )
)
if not defined NINJA_EXE (
    echo [ERROR] Could not find ninja.exe under %ANDROID_SDK_ROOT%\cmake\*\bin
    exit /b 1
)

if exist "%SCRIPT_DIR%\build" rmdir /S /Q "%SCRIPT_DIR%\build"
mkdir "%SCRIPT_DIR%\build"

"%CMAKE_EXE%" -G Ninja -S "%SCRIPT_DIR%" -B "%SCRIPT_DIR%\build" ^
  -DCMAKE_TOOLCHAIN_FILE="%ANDROID_NDK_HOME%\build\cmake\android.toolchain.cmake" ^
  -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
  -DANDROID_ABI=arm64-v8a ^
  -DANDROID_PLATFORM=android-26 ^
  -DCMAKE_BUILD_TYPE=Release
if errorlevel 1 goto :error

"%CMAKE_EXE%" --build "%SCRIPT_DIR%\build" --config Release
if errorlevel 1 goto :error

set "BINARY=%SCRIPT_DIR%\build\shower_shell_exec"
if not exist "%BINARY%" (
    echo [ERROR] Build output not found: %BINARY%
    goto :error
)

set "TARGET_DIR=%SCRIPT_DIR%\..\..\..\..\src\main\jniLibs\arm64-v8a"
if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"
copy /Y "%BINARY%" "%TARGET_DIR%\libshower_shell_exec.so" >nul
if errorlevel 1 goto :error

echo [OK] Wrote %TARGET_DIR%\libshower_shell_exec.so
exit /b 0

:error
echo [ERROR] Build failed.
exit /b 1
