#requires -Version 5.1
<#
.SYNOPSIS
  门 2 对拍台（docs/02）。录制真实 SSE、回放给 App、两次 run 比对 0 diff。

.EXAMPLE
  # 1) 录一次真实会话（含工具调用），存成夹具 + runA
  .\tools\parity.ps1 record -Prompt "先用 bash 执行 ls -1 看看工作目录，再把文件名列出来。"

.EXAMPLE
  # 2) 一条命令验证：起回放器 → 同一夹具跑两次 → 比对
  .\tools\parity.ps1 verify -Captures parity\fixtures\device-smoke -Prompt "先用 bash 执行 ls -1 看看工作目录，再把文件名列出来。"

.EXAMPLE
  # 3) 单独起回放器（配合 adb reverse，供手动跑/抓包）
  .\tools\parity.ps1 serve -Captures parity\fixtures\device-smoke -Port 18080 -Loops 4
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('record', 'serve', 'run', 'compare', 'verify')]
    [string]$Command,

    [string]$Prompt = '先用 bash 执行 ls -1 看看工作目录，再把看到的文件名列出来。',
    [string]$Captures = 'parity\fixtures\device-smoke',
    [string]$Out = 'parity\runs',
    [int]$Port = 18080,
    [int]$Loops = 2,
    [switch]$RealTime,

    [string]$A,
    [string]$B,
    [string]$Report = 'parity\reports\verify.json',

    [string]$Device = '192.0.2.10:35757',
    [string]$Adb = 'C:\Android\platform-tools\adb.exe',
    [string]$Package = 'xin.dshaa.nativeapp',
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',

    # 每次 run 前要删掉的工作区文件（写/改类场景不幂等：write 的结果是 "Created file"，
    # 第二次跑就变 "Updated file"，夹具必然对不上）。相对路径按 files/workspace 解析。
    [string[]]$PreRunClean = @()
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$ParityBat = Join-Path $Root 'core\parity\build\install\parity\bin\parity.bat'
$env:JAVA_HOME = $JdkHome

function Resolve-UnderRoot([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return $Path }
    return [IO.Path]::GetFullPath((Join-Path $Root $Path))
}

function Invoke-Adb {
    & $Adb -s $Device @args
    if ($LASTEXITCODE -ne 0) { throw "adb 失败：$args" }
}

function Clear-WorkspacePaths {
    foreach ($p in $PreRunClean) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        $remote = if ($p.StartsWith('/')) { $p } else { "/data/user/0/$Package/files/workspace/$p" }
        & $Adb -s $Device shell run-as $Package rm -rf $remote 2>$null | Out-Null
        Write-Host "  clean: $remote"
    }
}

function Set-Reverse([int]$P) {
    Invoke-Adb reverse "tcp:$P" "tcp:$P" | Out-Null
    # 设备端注册 reverse 有延迟，立刻拉起 App 会偶发 "Failed to connect"
    Start-Sleep -Milliseconds 1500
    Write-Host "  reverse: $((& $Adb -s $Device reverse --list | Out-String).Trim())"
}

function Remove-Reverse([int]$P) {
    try { & $Adb -s $Device reverse --remove "tcp:$P" | Out-Null } catch { }
}

function Wait-TurnEnd([string]$RemoteRun, [int]$TimeoutSec = 120) {
    # PS 5.1 在 ErrorActionPreference=Stop 下会把 adb 的 stderr 当成终止错误；
    # 轮询时文件还不存在（grep 报 No such file），所以局部放宽并丢弃 stderr。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline) {
            $count = (& $Adb -s $Device shell run-as $Package grep -c turn.end "$RemoteRun/run.jsonl" 2>$null | Out-String).Trim()
            if ($count -eq '1') { return $true }
            Start-Sleep -Seconds 1
        }
        return $false
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Pull-Run([string]$RemoteRun, [string]$LocalDir) {
    New-Item -ItemType Directory -Force $LocalDir | Out-Null
    $local = Join-Path $LocalDir 'run.jsonl'
    cmd /c "`"$Adb`" -s $Device exec-out run-as $Package cat $RemoteRun/run.jsonl > `"$local`""
    if (-not (Test-Path $local) -or (Get-Item $local).Length -eq 0) { throw "拉取失败：$RemoteRun/run.jsonl" }
    Write-Host "  拉取 → $local ($((Get-Item $local).Length) bytes)"
}

function Start-ReplayServer([string]$CapturePath, [int]$P, [int]$L, [bool]$RealTimeMode) {
    $captureAbs = Resolve-UnderRoot $CapturePath
    $log = Join-Path $Root 'parity\replay.log'
    New-Item -ItemType Directory -Force (Split-Path -Parent $log) | Out-Null
    $argv = @('replay', '--capture', $captureAbs, '--port', "$P", '--loops', "$L")
    if ($RealTimeMode) { $argv += '--real-time' }
    $proc = Start-Process -FilePath $ParityBat -ArgumentList $argv -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        try {
            $stats = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$P/__parity/stats" -TimeoutSec 2).Content
            Write-Host "  回放器就绪 port=$P $stats"
            return $proc
        } catch {
            Start-Sleep -Milliseconds 400
        }
    }
    throw "回放器 40s 内没起来，看 $log"
}

function Stop-ReplayServer($Proc) {
    if ($null -eq $Proc) { return }
    try { & taskkill /PID $Proc.Id /T /F | Out-Null } catch { }
}

function Test-ConnectFailure([string]$LocalRunJsonl) {
    if (-not (Test-Path $LocalRunJsonl)) { return $false }
    $text = [IO.File]::ReadAllText($LocalRunJsonl, [Text.Encoding]::UTF8)
    return $text.Contains('Failed to connect to /127.0.0.1')
}

function Reset-Replay([int]$P) {
    try {
        Invoke-WebRequest -UseBasicParsing -Method Post "http://127.0.0.1:$P/__parity/reset" -TimeoutSec 5 | Out-Null
    } catch {
        throw "回放器 reset 失败：$($_.Exception.Message)"
    }
}

function Read-FixtureShas {
    $captureDir = Resolve-UnderRoot $Captures
    $files = Get-ChildItem (Join-Path $captureDir '*.jsonl') | Sort-Object Name
    $shas = @()
    foreach ($f in $files) {
        $header = [IO.File]::ReadAllLines($f.FullName, [Text.Encoding]::UTF8)[0] | ConvertFrom-Json
        $shas += [string]$header.requestSha256
    }
    return $shas
}

function Test-RunMatchesFixtures([string]$LocalRunJsonl, [string[]]$FixtureShas) {
    $lines = [IO.File]::ReadAllLines($LocalRunJsonl, [Text.Encoding]::UTF8)
    $shas = @()
    foreach ($line in $lines) {
        if ($line -like '*"t":"llm.request"*') {
            $shas += ([regex]::Match($line, '"sha256":"([a-f0-9]{64})"')).Groups[1].Value
        }
    }
    if ($shas.Count -ne $FixtureShas.Count) {
        return "请求数 $($shas.Count) ≠ 夹具数 $($FixtureShas.Count)"
    }
    for ($i = 0; $i -lt $shas.Count; $i++) {
        if ($shas[$i] -ne $FixtureShas[$i]) {
            return "第 $($i + 1) 个请求的 sha 与夹具不一致"
        }
    }
    return $null
}

function Invoke-DeviceRun([string]$Name, [int]$P) {
    $remoteRun = "/data/user/0/$Package/files/parity/$Name"
    $localDir = Join-Path (Resolve-UnderRoot $Out) $Name
    $fixtureShas = Read-FixtureShas
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Reset-Replay $P
        Invoke-Adb shell "am force-stop $Package" | Out-Null
        # 删掉上一次留下的 run.jsonl：否则 Wait-TurnEnd 会被旧 turn.end 骗过，拉到旧证据
        & $Adb -s $Device shell run-as $Package rm -rf $remoteRun 2>$null | Out-Null
        Clear-WorkspacePaths
        $am = "am start -n $Package/.MainActivity --ez dsh_auto_approve true " +
            "--es dsh_base_url http://127.0.0.1:$P --es dsh_parity_run $remoteRun --es dsh_send '$Prompt'"
        Invoke-Adb shell $am | Out-Null
        Write-Host "  run $Name 已启动（第 $attempt 次），等 turn.end…"
        if (-not (Wait-TurnEnd $remoteRun)) {
            Write-Host "  超时；dump 最近日志："
            & $Adb -s $Device shell logcat -d -t 80 | Select-String -Pattern 'AndroidRuntime|dsh|parity' | Select-Object -First 20
            throw "run $Name 没有产生 turn.end"
        }
        Pull-Run $remoteRun $localDir
        $localJsonl = Join-Path $localDir 'run.jsonl'
        $mismatch = Test-RunMatchesFixtures $localJsonl $fixtureShas
        if ($null -eq $mismatch -and -not (Test-ConnectFailure $localJsonl)) { return }
        Copy-Item $localJsonl (Join-Path $localDir "run.attempt$attempt.jsonl") -Force
        # adb reverse 首次连接偶发被拒 / 夹具游标错位：reset 后整轮重跑（重跑会截断 run.jsonl）
        Write-Host "  本次 run 不可用（$($mismatch)；连接失败=$(Test-ConnectFailure $localJsonl)），reset 后重试…"
        Start-Sleep -Seconds 2
    }
    throw "run $Name 连续三次没有对齐夹具"
}

function Invoke-Serve {
    $captureAbs = Resolve-UnderRoot $Captures
    $argv = @('replay', '--capture', $captureAbs, '--port', "$Port", '--loops', "$Loops")
    if ($RealTime) { $argv += '--real-time' }
    & $ParityBat @argv
}

function Invoke-Record {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $remoteCap = "/data/user/0/$Package/files/parity/cap-$stamp"
    $remoteRun = "/data/user/0/$Package/files/parity/run-$stamp"
    Invoke-Adb shell "am force-stop $Package" | Out-Null
    Clear-WorkspacePaths
    # 录制必须打真实端点：上一次 verify 会把 base_url 留在 127.0.0.1:18080（设置持久化），
    # 不显式 default 就会录到「连接失败」的空夹具。
    $am = "am start -n $Package/.MainActivity --ez dsh_auto_approve true " +
        "--es dsh_base_url default --es dsh_dump_sse $remoteCap --es dsh_parity_run $remoteRun --es dsh_send '$Prompt'"
    Invoke-Adb shell $am | Out-Null
    Write-Host "录制中（$stamp），等 turn.end…"
    if (-not (Wait-TurnEnd $remoteRun)) { throw "录制超时" }

    $localCap = Resolve-UnderRoot $Captures
    New-Item -ItemType Directory -Force $localCap | Out-Null
    $files = (& $Adb -s $Device shell run-as $Package ls "$remoteCap" | Out-String) -split "`r?`n" | Where-Object { $_ -match '\.jsonl$' }
    foreach ($f in $files) {
        cmd /c "`"$Adb`" -s $Device exec-out run-as $Package cat $remoteCap/$f > `"$localCap\$f`""
        Write-Host "  $f"
    }
    Pull-Run $remoteRun (Join-Path (Resolve-UnderRoot $Out) 'run-real')
    Write-Host "录制完成：$($files.Count) 个请求 → $localCap"
}

function Invoke-Compare {
    param([string]$AInput, [string]$BInput)
    $aDir = Resolve-UnderRoot $AInput
    $bDir = Resolve-UnderRoot $BInput
    $reportPath = Resolve-UnderRoot $Report
    New-Item -ItemType Directory -Force (Split-Path -Parent $reportPath) | Out-Null
    & $ParityBat compare --a $aDir --b $bDir --workdir "/data/user/0/$Package/files/workspace" `
        --scenario (Split-Path -Leaf $aDir) --out $reportPath
    if ($LASTEXITCODE -ne 0) { throw "比对 FAIL（报告：$reportPath）" }
    Write-Host "比对 PASS → $reportPath"
}

function Restore-App-Environment {
    # verify/run 把 base_url 指到回放器、审批改成 auto，且都写进设置（会持久化）。
    # 跑完必须还原成官方端点 + 每次询问，否则用户点开 App 连的是 127.0.0.1。
    # `am start` 在 App 已在前台时会往 stderr 打 Warning；PS 5.1 会把它当终止错误，必须吞掉。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 必须 force-stop：App 已在前台时 am start 只投递 onNewIntent，MainActivity 不读 extras。
        & $Adb -s $Device shell "am force-stop $Package" 2>$null | Out-Null
        & $Adb -s $Device shell "am start -n $Package/.MainActivity --es dsh_base_url default --ez dsh_ask_approval true" 2>$null | Out-Null
        Start-Sleep -Milliseconds 800
        & $Adb -s $Device shell "am force-stop $Package" 2>$null | Out-Null
    } catch {
        Write-Host "  还原 App 设置失败（不影响对拍结果）：$($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Verify {
    $proc = $null
    try {
        $proc = Start-ReplayServer $Captures $Port $Loops $RealTime.IsPresent
        Set-Reverse $Port
        Invoke-DeviceRun 'run-1' $Port
        Invoke-DeviceRun 'run-2' $Port
        Invoke-Compare -AInput (Join-Path (Resolve-UnderRoot $Out) 'run-1') -BInput (Join-Path (Resolve-UnderRoot $Out) 'run-2')
    } finally {
        Stop-ReplayServer $proc
        Remove-Reverse $Port
        Restore-App-Environment
    }
}

switch ($Command) {
    'serve' { Invoke-Serve }
    'record' { Invoke-Record }
    'run' {
        Invoke-Adb shell "am force-stop $Package" | Out-Null
        try {
            Set-Reverse $Port
            Invoke-DeviceRun 'run-single' $Port
        } finally {
            Remove-Reverse $Port
            Restore-App-Environment
        }
    }
    'compare' { Invoke-Compare -AInput $A -BInput $B }
    'verify' { Invoke-Verify }
}
