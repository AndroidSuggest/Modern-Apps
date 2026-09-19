<#
.SYNOPSIS
  Runs the Desktop Head Unit loopback against the Pixel 8: adb forward,
  fresh logcat capture, and the DHU in its own window.

.USAGE
  ./dhu            # full loop, log to analysis/dhu-<timestamp>.log
  ./dhu -NoLog     # skip the logcat capture (DHU only)
  ./dhu -DeviceId 44050DLJH001PC   # target a different device

.NOTES
  DHU is interactive: piping its stdout closes stdin and it exits, so it
  always launches in its own window via Start-Process. `adb logcat -d` has
  proven unreliable (stale buffer); this captures live to a file instead.
#>
param(
    [string]$DeviceId = "44050DLJH001PC",
    [switch]$NoLog
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$DhuExe = "C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"

if (-not (Test-Path $DhuExe)) {
    Write-Error "DHU not found at $DhuExe"
    exit 1
}

# The phone listens; the DHU reaches it through the forward.
adb -s $DeviceId forward tcp:5277 tcp:5277
Write-Output "forward tcp:5277 -> $DeviceId"

if (-not $NoLog) {
    adb -s $DeviceId logcat -c
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $logfile = Join-Path $RepoRoot "analysis\dhu-$stamp.log"
    Start-Process adb -ArgumentList "-s $DeviceId logcat -v time MaAuto.Service:V MaAuto.Server:V MaAuto.Video:V MaAuto.Sensors:V MaAuto.Input:V MaAuto.HostManager:V MaAuto.HostSession:V `"*:S`"" `
        -RedirectStandardOutput $logfile -WindowStyle Hidden
    Write-Output "logcat -> $logfile"
}

Start-Process $DhuExe
Write-Output "DHU launched (own window)"
