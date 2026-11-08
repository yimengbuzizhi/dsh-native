# upstream check: version source + package mapping completeness
# Usage: pwsh -File tools/upstream/check.ps1 [-KernelTree <path-to-@deepseek-ai-dir>]
param(
  [string]$KernelTree = 'D:\dsh\rebuild-v17\buildenv-pc\devhome\dshroot\lib\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fail = 0

function Info([string]$m) { Write-Host "[ok] $m" }
function Bad([string]$m) { Write-Host "[FAIL] $m"; $script:fail++ }

# 1) upstream.toml
$toml = Join-Path $root 'upstream.toml'
if (-not (Test-Path $toml)) { Bad "missing upstream.toml" } else {
  $txt = Get-Content $toml -Raw
  $ver = [regex]::Match($txt, '(?m)^\s*version\s*=\s*"([^"]+)"').Groups[1].Value
  if ($ver) { Info "upstream version = $ver" } else { Bad "no version in upstream.toml" }
}

# 2) mapping.tsv
$map = Join-Path $root 'docs/upstream/mapping.tsv'
if (-not (Test-Path $map)) { Bad "missing mapping.tsv" } else {
  $lines = Get-Content $map | Where-Object { $_.Trim() -ne '' }
  $header = $lines[0]
  if ($header -notmatch '^package\tgroup\tmodule\tstatus\tnote') { Bad "mapping header mismatch: $header" }
  $rows = $lines | Select-Object -Skip 1
  $names = @()
  $badCols = 0
  foreach ($r in $rows) {
    $c = $r -split "`t"
    if ($c.Count -lt 4) { $badCols++; continue }
    $names += $c[0]
  }
  if ($badCols -gt 0) { Bad "$badCols rows have <4 columns" } else { Info "mapping rows = $($rows.Count)" }
  $dup = $names | Group-Object | Where-Object Count -gt 1
  if ($dup) { Bad "duplicate packages: $($dup.Name -join ', ')" } else { Info "no duplicate package" }
  $st = $rows | ForEach-Object { ($_ -split "`t")[3] } | Group-Object | ForEach-Object { "$($_.Name)=$($_.Count)" }
  Info ("status: " + ($st -join '  '))
}

# 3) compare with kernel tree (optional)
if (Test-Path $KernelTree) {
  $actual = Get-ChildItem $KernelTree -Directory | Select-Object -ExpandProperty Name
  $mapped = @{}
  foreach ($r in $rows) { $mapped[($r -split "`t")[0]] = $true }
  $missing = $actual | Where-Object { -not $mapped.ContainsKey($_) }
  if ($missing) { Bad ("unmapped upstream packages (" + $missing.Count + "): " + (($missing | Select-Object -First 10) -join ', ')) }
  else { Info "all $($actual.Count) upstream packages are mapped" }
  $extra = $mapped.Keys | Where-Object { $_ -notin $actual }
  if ($extra) { Write-Host "[warn] mapping has $($extra.Count) packages not in this tree (ok if from other version)" }
} else {
  Write-Host "[warn] kernel tree not found, skip completeness check: $KernelTree"
}

if ($fail -gt 0) { Write-Host "RESULT: $fail check(s) failed"; exit 1 } else { Write-Host "RESULT: all checks passed"; exit 0 }
