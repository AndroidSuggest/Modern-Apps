<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# dhu.bat - Desktop Head Unit loopback against the Pixel 8: adb forward,
# fresh logcat capture, and the DHU in its own window. (PowerShell twin of ./dhu.)
#
# Usage (cmd):
#   dhu.bat                                # full loop, log to analysis/dhu-<timestamp>.log
#   dhu.bat --no-log                       # skip the logcat capture (DHU only)
#   dhu.bat --device-id 44050DLJH001PC     # target a different device
#
# Notes:
#   DHU is interactive: piping its stdout closes stdin and it exits, so it
#   always launches detached. `adb logcat -d` has proven unreliable (stale
#   buffer); this captures live to a file instead.
$ErrorActionPreference = 'Stop'

$DhuScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }
$DhuDeviceId = '44050DLJH001PC'
$DhuNoLog = $false

function Show-DhuHelp {
  @'
Usage:
  dhu.bat                                # full loop, log to analysis/dhu-<timestamp>.log
  dhu.bat --no-log                       # skip the logcat capture (DHU only)
  dhu.bat --device-id 44050DLJH001PC     # target a different device

Notes:
  DHU is interactive: piping its stdout closes stdin and it exits, so it
  always launches detached. `adb logcat -d` has proven unreliable (stale
  buffer); this captures live to a file instead.
'@
}

$DhuIdx = 0
while ($DhuIdx -lt $args.Count) {
  $DhuArg = [string]$args[$DhuIdx]
  if ($DhuArg -eq '-h' -or $DhuArg -eq '--help' -or $DhuArg -eq 'help') {
    Show-DhuHelp
    exit 0
  }
  elseif ($DhuArg -eq '--no-log' -or $DhuArg -eq '--nolog' -or $DhuArg -eq '-NoLog') {
    $DhuNoLog = $true
    $DhuIdx++
  }
  elseif ($DhuArg -eq '--device-id' -or $DhuArg -eq '-DeviceId') {
    if ($DhuIdx + 1 -ge $args.Count) {
      [Console]::Error.WriteLine('Error: --device-id needs a value.')
      exit 1
    }
    $DhuDeviceId = [string]$args[$DhuIdx + 1]
    $DhuIdx += 2
  }
  elseif ($DhuArg -like '--device-id=*') {
    $DhuDeviceId = $DhuArg.Substring('--device-id='.Length)
    $DhuIdx++
  }
  else {
    [Console]::Error.WriteLine("Error: unknown arg '$DhuArg'. Usage: dhu.bat [--no-log] [--device-id SERIAL]")
    exit 1
  }
}

# SDK location: prefer env, then the usual per-OS defaults (plus Windows LOCALAPPDATA).
$DhuBases = @()
if ($env:ANDROID_HOME) { $DhuBases += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $DhuBases += $env:ANDROID_SDK_ROOT }
$DhuBases += (Join-Path $HOME 'Android/Sdk')
$DhuBases += (Join-Path $HOME 'Library/Android/sdk')
$DhuBases += '/c/Android/Sdk'
$DhuBases += 'C:/Android/Sdk'
if ($env:LOCALAPPDATA) { $DhuBases += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }

$DhuExe = ''
foreach ($DhuBase in $DhuBases) {
  if (-not $DhuBase) { continue }
  $DhuCand = Join-Path $DhuBase 'extras/google/auto/desktop-head-unit'
  if (Test-Path -LiteralPath $DhuCand -PathType Leaf) { $DhuExe = $DhuCand; break }
  $DhuCandExe = "$DhuCand.exe"
  if (Test-Path -LiteralPath $DhuCandExe -PathType Leaf) { $DhuExe = $DhuCandExe; break }
}
if (-not $DhuExe) {
  [Console]::Error.WriteLine('Error: DHU not found (looked under ANDROID_HOME, ~/Android/Sdk, ~/Library/Android/sdk, /c/Android/Sdk).')
  exit 1
}

# adb: prefer PATH, then platform-tools under the same SDK bases.
$DhuAdbCmd = Get-Command adb -ErrorAction SilentlyContinue
$DhuAdb = if ($DhuAdbCmd) { $DhuAdbCmd.Source } else { '' }
if (-not $DhuAdb) {
  foreach ($DhuBase in $DhuBases) {
    if (-not $DhuBase) { continue }
    foreach ($DhuName in @('platform-tools/adb', 'platform-tools/adb.exe')) {
      $DhuCand = Join-Path $DhuBase $DhuName
      if (Test-Path -LiteralPath $DhuCand -PathType Leaf) { $DhuAdb = $DhuCand; break }
    }
    if ($DhuAdb) { break }
  }
}
if (-not $DhuAdb) {
  [Console]::Error.WriteLine('Error: adb not found (install platform-tools or set ANDROID_HOME).')
  exit 1
}

# The phone listens; the DHU reaches it through the forward.
& $DhuAdb -s $DhuDeviceId forward tcp:5277 tcp:5277
if ($LASTEXITCODE -ne 0) {
  [Console]::Error.WriteLine('Error: adb forward failed.')
  exit 1
}
Write-Output "forward tcp:5277 -> $DhuDeviceId"

if (-not $DhuNoLog) {
  & $DhuAdb -s $DhuDeviceId logcat -c
  $DhuStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $DhuLogFile = Join-Path $DhuScriptDir "analysis/dhu-$DhuStamp.log"
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DhuLogFile) | Out-Null
  $DhuTags = @(
    '-s', $DhuDeviceId, 'logcat', '-v', 'time',
    'MaAuto.Service:V', 'MaAuto.Server:V', 'MaAuto.Video:V', 'MaAuto.Sensors:V',
    'MaAuto.Input:V', 'MaAuto.HostManager:V', 'MaAuto.HostSession:V', '*:S'
  )
  $DhuJob = Start-Job -Name "dhu-logcat-$DhuStamp" -ScriptBlock {
    param($AdbBin, $LogArgs, $LogPath)
    & $AdbBin @LogArgs *>> $LogPath
  } -ArgumentList $DhuAdb, $DhuTags, $DhuLogFile
  Write-Output "logcat -> $DhuLogFile (job $($DhuJob.Id))"
}

Start-Process -FilePath $DhuExe | Out-Null
Write-Output 'DHU launched (detached)'
exit 0
