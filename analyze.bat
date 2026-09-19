<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# analyze.bat - Codebase metrics for Modern-Apps. (PowerShell twin of ./analyze.)
#
# Usage (cmd):
#   analyze.bat package [-m MODULE] [--nolib]    # .kt lines grouped by package (directory)
#   analyze.bat module [-m MODULE] [--nolib]     # .kt lines grouped by module
#   analyze.bat structure [-m MODULE] [--nolib]  # root packages x modules using them
#   analyze.bat ram                               # live device RAM via adb dumpsys meminfo
#
#   -m MODULE filters to one module directory name. --nolib skips top-level library/.
$ErrorActionPreference = 'Stop'

$AnalyzeRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }

function Show-AnalyzeHelp {
  @'
Usage:
  analyze.bat package [-m MODULE] [--nolib]    # .kt lines grouped by package (directory)
  analyze.bat module [-m MODULE] [--nolib]     # .kt lines grouped by module
  analyze.bat structure [-m MODULE] [--nolib]  # root packages x modules using them
  analyze.bat ram                               # live device RAM via adb dumpsys meminfo

  -m MODULE filters to one module directory name. --nolib skips top-level library/.
'@
}

function Get-AnalyzeAdb {
  $AdbCmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($AdbCmd) { return $AdbCmd.Source }
  $AnalyzeSdkBases = @()
  if ($env:ANDROID_HOME) { $AnalyzeSdkBases += $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT) { $AnalyzeSdkBases += $env:ANDROID_SDK_ROOT }
  $AnalyzeSdkBases += (Join-Path $HOME 'Android/Sdk')
  $AnalyzeSdkBases += (Join-Path $HOME 'Library/Android/sdk')
  if ($env:LOCALAPPDATA) { $AnalyzeSdkBases += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
  foreach ($AnalyzeBase in $AnalyzeSdkBases) {
    if (-not $AnalyzeBase) { continue }
    foreach ($AnalyzeName in @('platform-tools/adb', 'platform-tools/adb.exe')) {
      $AnalyzeCand = Join-Path $AnalyzeBase $AnalyzeName
      if (Test-Path -LiteralPath $AnalyzeCand -PathType Leaf) { return $AnalyzeCand }
    }
  }
  return ''
}

function Get-KtLineCount([string]$Path) {
  $AnalyzeCount = 0
  foreach ($AnalyzeLine in [System.IO.File]::ReadLines($Path)) { $AnalyzeCount++ }
  return $AnalyzeCount
}

function Format-AnalyzeBytes([long]$Bytes) {
  if ($Bytes -ge 1073741824) { return ('{0:F2} GB' -f ($Bytes / 1073741824)) }
  return ('{0:F2} MB' -f ($Bytes / 1048576))
}

$AnalyzeType = ''
$AnalyzeModule = ''
$AnalyzeNoLib = $false
$AnalyzeIdx = 0
while ($AnalyzeIdx -lt $args.Count) {
  $AnalyzeArg = [string]$args[$AnalyzeIdx]
  if ($AnalyzeArg -eq '-h' -or $AnalyzeArg -eq '--help' -or $AnalyzeArg -eq 'help') {
    Show-AnalyzeHelp
    exit 0
  }
  elseif ($AnalyzeArg -in @('package', 'module', 'ram', 'structure')) {
    $AnalyzeType = $AnalyzeArg
    $AnalyzeIdx++
  }
  elseif ($AnalyzeArg -eq '-m' -or $AnalyzeArg -eq '--module') {
    if ($AnalyzeIdx + 1 -ge $args.Count) {
      [Console]::Error.WriteLine('Error: --module needs a value.')
      exit 1
    }
    $AnalyzeModule = [string]$args[$AnalyzeIdx + 1]
    $AnalyzeIdx += 2
  }
  elseif ($AnalyzeArg -eq '--nolib' -or $AnalyzeArg -eq '-nolib') {
    $AnalyzeNoLib = $true
    $AnalyzeIdx++
  }
  else {
    [Console]::Error.WriteLine("Error: unknown arg '$AnalyzeArg'.")
    exit 1
  }
}
if (-not $AnalyzeType) {
  [Console]::Error.WriteLine('Error: give a type: package, module, ram, structure.')
  exit 1
}

if ($AnalyzeType -eq 'ram') {
  $AnalyzeAdb = Get-AnalyzeAdb
  if (-not $AnalyzeAdb) {
    [Console]::Error.WriteLine('Error: adb not found.')
    exit 1
  }
  $AnalyzeDevices = & $AnalyzeAdb devices 2>$null
  if (-not ($AnalyzeDevices | Where-Object { $_ -match "`tdevice$" })) {
    [Console]::Error.WriteLine('Error: no authorized device (adb devices).')
    exit 1
  }
  Write-Output 'Fetching RAM metrics via adb...'
  $AnalyzeMeminfo = & $AnalyzeAdb shell dumpsys meminfo 2>$null
  $AnalyzeMax = @{}
  $AnalyzeNames = @{}
  foreach ($AnalyzeRow in $AnalyzeMeminfo) {
    if ($AnalyzeRow -match '^\s*([\d,]+)\s+(?:K|kB|KB|K:):?\s*([A-Za-z0-9_.\-:+]+)\s+\(pid\s+(\d+)') {
      $AnalyzeBytes = [long]($Matches[1] -replace ',', '') * 1024
      $AnalyzePid = $Matches[3]
      if ((-not $AnalyzeMax.ContainsKey($AnalyzePid)) -or ($AnalyzeBytes -gt $AnalyzeMax[$AnalyzePid])) {
        $AnalyzeMax[$AnalyzePid] = $AnalyzeBytes
        $AnalyzeNames[$AnalyzePid] = $Matches[2]
      }
    }
  }
  $AnalyzeTotal = 0
  $AnalyzeRamRows = foreach ($AnalyzePid in $AnalyzeMax.Keys) {
    $AnalyzeTotal += $AnalyzeMax[$AnalyzePid]
    [pscustomobject]@{ Pid = $AnalyzePid; Name = $AnalyzeNames[$AnalyzePid]; Bytes = $AnalyzeMax[$AnalyzePid] }
  }
  $AnalyzeRamRows += [pscustomobject]@{ Pid = 'TOTAL'; Name = 'TOTAL RUNNING PROCESS RAM'; Bytes = $AnalyzeTotal }
  foreach ($AnalyzeRow in ($AnalyzeRamRows | Sort-Object -Property Bytes -Descending)) {
    '{0,-8} {1,-60} {2}' -f $AnalyzeRow.Pid, $AnalyzeRow.Name, (Format-AnalyzeBytes $AnalyzeRow.Bytes)
  }
  exit 0
}

# file list: all .kt under a src/ dir, optionally one module, optionally no library/
$AnalyzeFiles = Get-ChildItem -LiteralPath $AnalyzeRoot -Recurse -Filter '*.kt' -File -Force -ErrorAction SilentlyContinue |
  Where-Object {
    $AnalyzeFull = $_.FullName.Replace('\', '/')
    if ($AnalyzeFull -notlike '*/src/*') { return $false }
    if ($AnalyzeFull -like '*/build/*' -or $AnalyzeFull -like '*/target/*' -or $AnalyzeFull -like '*/.git/*') { return $false }
    if ($AnalyzeNoLib -and ($AnalyzeFull -like '*/library/*')) { return $false }
    if ($AnalyzeModule -and ($AnalyzeFull -notlike "*/$AnalyzeModule/src/*")) { return $false }
    return $true
  }
if (-not $AnalyzeFiles) {
  $AnalyzeSuffix = if ($AnalyzeModule) { " in module: $AnalyzeModule" } else { '' }
  [Console]::Error.WriteLine("No Kotlin files found$AnalyzeSuffix.")
  exit 1
}

switch ($AnalyzeType) {
  'package' {
    # group by containing directory name
    '{0,-50} {1}' -f 'PACKAGE(dir)', 'LINES'
    $AnalyzeByDir = @{}
    foreach ($AnalyzeFile in $AnalyzeFiles) {
      $AnalyzeDir = $AnalyzeFile.DirectoryName
      if (-not $AnalyzeByDir.ContainsKey($AnalyzeDir)) { $AnalyzeByDir[$AnalyzeDir] = 0 }
      $AnalyzeByDir[$AnalyzeDir] += Get-KtLineCount $AnalyzeFile.FullName
    }
    foreach ($AnalyzeEntry in ($AnalyzeByDir.GetEnumerator() | Sort-Object -Property Value -Descending)) {
      '{0,-50} {1}' -f (Split-Path -Leaf $AnalyzeEntry.Key), $AnalyzeEntry.Value
    }
  }
  'module' {
    # group by module = directory holding src/
    '{0,-30} {1}' -f 'MODULE', 'LINES'
    $AnalyzeMl = @{}
    $AnalyzeTotal = 0
    foreach ($AnalyzeFile in $AnalyzeFiles) {
      $AnalyzeRel = $AnalyzeFile.FullName.Replace('\', '/')
      $AnalyzeRootSlash = $AnalyzeRoot.Replace('\', '/').TrimEnd('/')
      if ($AnalyzeRel.StartsWith("$AnalyzeRootSlash/")) { $AnalyzeRel = $AnalyzeRel.Substring($AnalyzeRootSlash.Length + 1) }
      $AnalyzeSrcIdx = $AnalyzeRel.IndexOf('/src/')
      $AnalyzeMod = if ($AnalyzeSrcIdx -ge 0) { $AnalyzeRel.Substring(0, $AnalyzeSrcIdx) } else { $AnalyzeRel }
      $AnalyzeN = Get-KtLineCount $AnalyzeFile.FullName
      if (-not $AnalyzeMl.ContainsKey($AnalyzeMod)) { $AnalyzeMl[$AnalyzeMod] = 0 }
      $AnalyzeMl[$AnalyzeMod] += $AnalyzeN
    }
    foreach ($AnalyzeEntry in ($AnalyzeMl.GetEnumerator() | Sort-Object -Property Value -Descending)) {
      '{0,-30} {1}' -f $AnalyzeEntry.Key, $AnalyzeEntry.Value
      $AnalyzeTotal += $AnalyzeEntry.Value
    }
    Write-Output ''
    '{0,-30} {1}' -f 'TOTAL', $AnalyzeTotal
  }
  'structure' {
    # root package = path segment right after the module-name segment under src/
    '{0,-20} {1,-8} {2}' -f 'PACKAGE', 'MODULES', 'MODULENAMES'
    $AnalyzePkgMods = @{}
    foreach ($AnalyzeFile in $AnalyzeFiles) {
      $AnalyzeRel = $AnalyzeFile.FullName.Replace('\', '/')
      $AnalyzeRootSlash = $AnalyzeRoot.Replace('\', '/').TrimEnd('/')
      if ($AnalyzeRel.StartsWith("$AnalyzeRootSlash/")) { $AnalyzeRel = $AnalyzeRel.Substring($AnalyzeRootSlash.Length + 1) }
      $AnalyzeSrcIdx = $AnalyzeRel.IndexOf('/src/')
      if ($AnalyzeSrcIdx -lt 0) { continue }
      $AnalyzePre = $AnalyzeRel.Substring(0, $AnalyzeSrcIdx)
      $AnalyzeModName = Split-Path -Leaf $AnalyzePre
      $AnalyzePost = $AnalyzeRel.Substring($AnalyzeSrcIdx + 5)
      $AnalyzeSegs = $AnalyzePost.Split('/')
      $AnalyzeFound = -1
      for ($AnalyzeI = 0; $AnalyzeI -lt $AnalyzeSegs.Count; $AnalyzeI++) {
        if ($AnalyzeSegs[$AnalyzeI] -ceq $AnalyzeModName) { $AnalyzeFound = $AnalyzeI; break }
      }
      if (($AnalyzeFound -ge 0) -and (($AnalyzeFound + 1) -lt $AnalyzeSegs.Count)) {
        $AnalyzePkg = $AnalyzeSegs[$AnalyzeFound + 1]
      }
      else {
        $AnalyzePkg = Split-Path -Leaf $AnalyzeFile.FullName
      }
      if (-not $AnalyzePkgMods.ContainsKey($AnalyzePkg)) { $AnalyzePkgMods[$AnalyzePkg] = @() }
      if ($AnalyzePkgMods[$AnalyzePkg] -notcontains $AnalyzeModName) { $AnalyzePkgMods[$AnalyzePkg] += $AnalyzeModName }
    }
    foreach ($AnalyzeEntry in ($AnalyzePkgMods.GetEnumerator() | Sort-Object -Property @{ Expression = { $_.Value.Count }; Descending = $true }, @{ Expression = { $_.Key } })) {
      $AnalyzeMods = $AnalyzeEntry.Value
      '{0,-20} {1,-8} {2}' -f $AnalyzeEntry.Key, $AnalyzeMods.Count, ($AnalyzeMods -join ',')
    }
  }
}
exit 0
