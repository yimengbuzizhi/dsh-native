# 把设备上的会话文件（多 zstd 帧）拉下来并解成可读 JSONL。
# 用法：
#   .\tools\session-dump.ps1 -Session /data/user/0/xin.dshaa.nativeapp/files/sessions/.../session.v4.jsonl.zstd -Output tmp-session.jsonl
#   .\tools\session-dump.ps1 -Latest -Output tmp-session.jsonl     # 自动取最新会话
param(
    [string]$Session,
    [switch]$Latest,
    [Parameter(Mandatory = $true)][string]$Output,
    [string]$Device = '192.0.2.10:35757',
    [string]$Adb = 'C:\Android\platform-tools\adb.exe',
    [string]$Package = 'xin.dshaa.nativeapp',
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

if ($Latest) {
    $root = "/data/user/0/$Package/files/sessions/--data-user-0-xin-dshaa-nativeapp-files-workspace--"
    $latest = (& $Adb -s $Device shell "run-as $Package sh -c 'ls -t $root | head -1'").Trim()
    if (-not $latest) { throw "设备上没有会话目录" }
    $Session = "$root/$latest/session.v4.jsonl.zstd"
}

$tmpZst = Join-Path $env:TEMP ("session-" + [guid]::NewGuid().ToString('N') + ".zst")
cmd /c "`"$Adb`" -s $Device exec-out run-as $Package cat $Session > `"$tmpZst`""
if (-not (Test-Path $tmpZst) -or (Get-Item $tmpZst).Length -eq 0) { throw "拉取失败：$Session" }

$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.github.luben\zstd-jni" -Recurse -Filter "zstd-jni-*.jar" |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $jar) { throw "找不到 zstd-jni jar（先跑一次 gradle 让依赖落盘）" }

$classes = Join-Path $env:TEMP "dsh-zstd-dump"
New-Item -ItemType Directory -Force $classes | Out-Null
& "$JdkHome\bin\javac.exe" -cp $jar -d $classes (Join-Path $PSScriptRoot "zstd\ZstdDump.java")
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }
& "$JdkHome\bin\java.exe" -cp "$jar;$classes" ZstdDump $tmpZst $Output
Remove-Item $tmpZst -Force
Write-Host "已解开 → $Output ($((Get-Item $Output).Length) bytes)"
