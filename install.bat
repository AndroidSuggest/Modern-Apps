<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# install.bat - Ergonomic wrapper around gradlew assemble + adb session install.
# (PowerShell twin of ./install.)
#
# Usage (cmd):
#   install.bat [dev|release] <module> [module...]
#   install.bat <module> [module...]            # defaults to dev
#   install.bat [dev|release] all               # installs all app modules
#   install.bat all                             # same, defaults to dev
#   install.bat [dev|release] [8|9] <module>    # Pixel 8 or Pixel 9 (by model)
$ErrorActionPreference = 'Stop'

$InstallRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }

function Show-InstallHelp {
  @'
Usage:
  install.bat [dev|release] <module> [module...]
  install.bat <module> [module...]            # defaults to dev
  install.bat [dev|release] all               # installs all app modules
  install.bat all                             # same, defaults to dev
  install.bat [dev|release] [8|9] <module>    # Pixel 8 or Pixel 9 (by model)

Description:
  Ergonomic wrapper around gradlew :module:assembleDev + adb session install.
  - Variant is swappable and optional (defaults to dev)
  - Supports both slash and colon notations: games/voxels == games:voxels
  - Supports games shorthand: install.bat voxels -> games:voxels (auto-prefixed if games/<name> is a game)
  - Supports personal shorthand: install.bat dooraccess -> personal:dooraccess (auto-prefixed if personal/<name> is a personal app)
  - Supports "all" keyword: install.bat all -> installs every app module
  - Supports multiple modules in one call (single gradlew invocation)
  - 8|9 selects the Pixel 8 or Pixel 9 by model (optional; defaults to the
    first non-emulator device). Also accepts --device 8|9 / --device=8|9.
  - Installs via adb package sessions with the .idsig sidecar staged, so
    updates to preinstalled MAOS system apps get fs-verity (plain
    installDev/installRelease and `adb install` never stage it and always fail
    those updates). Auto-passes -PversionCodeOverride when the on-device
    package is at the same versionCode as the build: +1 for system apps,
    exactly the on-device version for user apps.

Examples:
  install.bat dev contacts                    -> :contacts:assembleDev + session install
  install.bat release games:voxels            -> :games:voxels:assembleRelease + session install
  install.bat dev games/voxels                -> :games:voxels:assembleDev (slash normalized)
  install.bat dev :games:voxels:              -> :games:voxels:assembleDev (colon trimming)
  install.bat voxels                          -> :games:voxels:assembleDev (shorthand auto games:)
  install.bat chess                           -> :games:chess:assembleDev (shorthand auto games:)
  install.bat dooraccess                      -> :personal:dooraccess:assembleDev (shorthand auto personal:)
  install.bat all                             -> :contacts:assembleDev :games:chess:assembleDev ... (all modules)
  install.bat dev all                         -> same as above with dev variant
  install.bat contacts calendar               -> :contacts:assembleDev :calendar:assembleDev
  install.bat release contacts                -> :contacts:assembleRelease + session install
  install.bat contacts dev                    -> :contacts:assembleDev (variant anywhere)
  install.bat 9 maps                        -> :maps:assembleDev + session install to the Pixel 9
  install.bat dev 8 maps                    -> :maps:assembleDev + session install to the Pixel 8
  install.bat --help
  install.bat --dry-run dev contacts          # prints what would run without executing gradle
  install.bat --dry-run all                   # preview all modules

Variant rules:
  - dev (default): assembleDev + session install unless explicitly asked for release
  - release: assembleRelease + session install when explicitly requested
  - debug: BLOCKED - never use assembleDebug

Notes:
  - NEVER uninstalls an app (no uninstall tasks)
  - Validates modules against app modules (those applying common-conventions-app)
  - Installs to the first connected device whose serial does not start with 'emulator'
    (unless 8|9 selects the Pixel 8 or Pixel 9)
  - Set INSTALL_DRY_RUN=1 or pass --dry-run for dry-run mode
'@
}

function Normalize-InstallModule([string]$M) {
  $M = $M.Trim()
  $M = [regex]::Replace($M, '^[/:]+', '')
  $M = [regex]::Replace($M, '[/:]+$', '')
  $M = $M.Replace('/', ':')
  while ($M.Contains('::')) { $M = $M.Replace('::', ':') }
  return $M.ToLowerInvariant()
}

function Get-InstallAppModulesSlash {
  $InstallSettings = Join-Path $InstallRoot 'settings.gradle.kts'
  $InstallFound = @()
  if (-not (Test-Path -LiteralPath $InstallSettings)) { return $InstallFound }
  foreach ($InstallLine in [System.IO.File]::ReadLines($InstallSettings)) {
    if ($InstallLine -match 'include\(":([^"]+)"\)') {
      $InstallP = $Matches[1].Replace(':', '/')
      if (-not $InstallP) { continue }
      $InstallBuild = Join-Path $InstallRoot "$InstallP/build.gradle.kts"
      if ((Test-Path -LiteralPath $InstallBuild) -and
          (Select-String -LiteralPath $InstallBuild -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
        $InstallFound += $InstallP
      }
    }
  }
  return @($InstallFound | Sort-Object -Unique)
}

function Show-InstallValidModules {
  $InstallSlash = Get-InstallAppModulesSlash
  if ($InstallSlash.Count -eq 0) {
    Write-Output '  (could not discover app modules)'
    return
  }
  Write-Output ''
  Write-Output 'Valid app modules (slash notation for filesystem, colon for Gradle):'
  Write-Output ''
  Write-Output ('  {0,-30} {1}' -f 'Filesystem (slash)', 'Gradle (colon)')
  Write-Output ('  {0,-30} {1}' -f '--------------------', '-------------------')
  foreach ($InstallSm in $InstallSlash) {
    if (-not $InstallSm) { continue }
    Write-Output ('  {0,-30} :{1}' -f $InstallSm, $InstallSm.Replace('/', ':'))
  }
  Write-Output ''
  Write-Output 'Tip: Use either notation: install.bat dev games/voxels  OR  install.bat dev games:voxels'
  Write-Output 'Shorthand: install.bat dev voxels  (auto-expands to games:voxels if games/<name> exists)'
  Write-Output '           install.bat dev dooraccess  (auto-expands to personal:dooraccess if personal/<name> exists)'
  Write-Output 'All: install.bat all  (installs every app module)'
}

function Get-InstallAdb {
  $InstallAdbCmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($InstallAdbCmd) { return $InstallAdbCmd.Source }
  $InstallSdkBases = @()
  if ($env:ANDROID_HOME) { $InstallSdkBases += $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT) { $InstallSdkBases += $env:ANDROID_SDK_ROOT }
  $InstallSdkBases += (Join-Path $HOME 'Android/Sdk')
  $InstallSdkBases += (Join-Path $HOME 'Library/Android/sdk')
  if ($env:LOCALAPPDATA) { $InstallSdkBases += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
  foreach ($InstallBase in $InstallSdkBases) {
    if (-not $InstallBase) { continue }
    foreach ($InstallName in @('platform-tools/adb', 'platform-tools/adb.exe')) {
      $InstallCand = Join-Path $InstallBase $InstallName
      if (Test-Path -LiteralPath $InstallCand -PathType Leaf) { return $InstallCand }
    }
  }
  return ''
}

# applicationId for a module (all are string literals in build.gradle.kts).
function Get-InstallApplicationId([string]$Mod) {
  $InstallBuild = Join-Path $InstallRoot "$($Mod.Replace(':', '/'))/build.gradle.kts"
  if (-not (Test-Path -LiteralPath $InstallBuild)) { return '' }
  foreach ($InstallLine in [System.IO.File]::ReadLines($InstallBuild)) {
    if ($InstallLine -match '^\s*applicationId\s*=\s*"([^"]+)"') { return $Matches[1] }
  }
  return ''
}

# APK built for a module; output-metadata.json names the file authoritatively
# (nested modules like cast:tv build tv-dev.apk, not cast-tv-dev.apk).
function Get-InstallBuiltApk([string]$Mod, [string]$VariantLc) {
  $InstallFs = $Mod.Replace(':', '/')
  $InstallOutDir = Join-Path $InstallRoot "$InstallFs/build/outputs/apk/$VariantLc"
  $InstallMeta = Join-Path $InstallOutDir 'output-metadata.json'
  $InstallBaseName = ($Mod -split ':')[-1]
  $InstallFileName = "$InstallBaseName-$VariantLc.apk"
  if (Test-Path -LiteralPath $InstallMeta -PathType Leaf) {
    $InstallMetaText = [System.IO.File]::ReadAllText($InstallMeta)
    $InstallM = [regex]::Match($InstallMetaText, '"outputFile"\s*:\s*"([^"]+)"')
    if ($InstallM.Success -and $InstallM.Groups[1].Value) { $InstallFileName = $InstallM.Groups[1].Value }
  }
  $InstallApk = Join-Path $InstallOutDir $InstallFileName
  if (Test-Path -LiteralPath $InstallApk -PathType Leaf) { return $InstallApk }
  return ''
}

# --- Handle zero args early ---
if ($args.Count -eq 0) {
  Show-InstallHelp
  Write-Output ''
  Write-Output 'Error: No modules specified.'
  Show-InstallValidModules
  exit 1
}

# --- Detect --help/-h/help anywhere ---
foreach ($InstallA in $args) {
  if ($InstallA -eq '--help' -or $InstallA -eq '-h' -or $InstallA -eq 'help') {
    Show-InstallHelp
    exit 0
  }
}

# --- Early DRY_RUN detection ---
$InstallDryRun = ($env:INSTALL_DRY_RUN -eq '1')
foreach ($InstallA in $args) {
  if ([string]$InstallA -eq '--dry-run') { $InstallDryRun = $true }
}

# --- Build FILTERED_ARGS (exclude --dry-run) ---
$InstallFiltered = @()
foreach ($InstallA in $args) {
  if ([string]$InstallA -eq '--dry-run') { continue }
  $InstallFiltered += [string]$InstallA
}
if ($InstallFiltered.Count -eq 0) {
  Show-InstallHelp
  Write-Output ''
  Write-Output 'Error: No modules specified (dry-run mode).'
  Show-InstallValidModules
  exit 1
}

# --- Device hint: bare 8|9 anywhere, or --device 8|9 / --device=8|9 (optional) ---
$InstallDeviceHint = ''
$InstallPrescan = @()
$InstallIdx = 0
while ($InstallIdx -lt $InstallFiltered.Count) {
  $InstallArg = $InstallFiltered[$InstallIdx]
  $InstallLc = $InstallArg.ToLowerInvariant()
  if ($InstallLc -eq '--device') {
    if ($InstallIdx + 1 -ge $InstallFiltered.Count) {
      Write-Output 'Error: --device requires a value: 8 (Pixel 8) or 9 (Pixel 9).'
      exit 1
    }
    $InstallDeviceHint = $InstallFiltered[$InstallIdx + 1].ToLowerInvariant()
    $InstallIdx += 2
    continue
  }
  elseif ($InstallLc -like '--device=*') {
    $InstallDeviceHint = $InstallLc.Substring('--device='.Length)
    $InstallIdx++
    continue
  }
  elseif ($InstallLc -eq '8' -or $InstallLc -eq '9') {
    $InstallDeviceHint = $InstallLc
    $InstallIdx++
    continue
  }
  elseif ($InstallLc -match '^[0-9]+$') {
    Write-Output "Error: Invalid device '$InstallArg'. Use 8 (Pixel 8) or 9 (Pixel 9)."
    exit 1
  }
  $InstallPrescan += $InstallArg
  $InstallIdx++
}
$InstallFiltered = $InstallPrescan
if ($InstallDeviceHint -and ($InstallDeviceHint -ne '8') -and ($InstallDeviceHint -ne '9')) {
  Write-Output "Error: Invalid device '$InstallDeviceHint'. Use 8 (Pixel 8) or 9 (Pixel 9)."
  exit 1
}

# --- Arg parsing: variant detection anywhere ---
$InstallVariantLc = ''
$InstallModulesRaw = @()
foreach ($InstallArg in $InstallFiltered) {
  $InstallLc = $InstallArg.ToLowerInvariant()
  if (($InstallLc -eq 'dev') -or ($InstallLc -eq 'release') -or ($InstallLc -eq 'debug')) {
    if (-not $InstallVariantLc) {
      $InstallVariantLc = $InstallLc
    }
    elseif ($InstallVariantLc -ne $InstallLc) {
      Write-Output "Error: Multiple distinct variants specified: '$InstallVariantLc' and '$InstallLc'."
      Write-Output 'Please specify only one variant: dev or release.'
      Write-Output ''
      Show-InstallHelp
      exit 1
    }
  }
  else {
    $InstallModulesRaw += $InstallArg
  }
}
if (-not $InstallVariantLc) { $InstallVariantLc = 'dev' }

if ($InstallVariantLc -eq 'debug') {
  Write-Output "Error: 'debug' variant is blocked."
  Write-Output ''
  Write-Output '  - Never assemble or install a debug variant'
  Write-Output '  - Always install the dev build, unless specifically asked for release'
  Write-Output ''
  Write-Output 'Use ''dev'' (default) or ''release'' instead:'
  Write-Output '  install.bat dev <module>'
  Write-Output '  install.bat release <module>'
  exit 1
}

if ($InstallModulesRaw.Count -eq 0) {
  Write-Output "Error: No modules specified (only variant '$InstallVariantLc' found)."
  Write-Output ''
  Show-InstallHelp
  Show-InstallValidModules
  exit 1
}

# --- Normalization + Dedup + Shorthand + "all" + auto games: prefix ---
# (dedup via hashtable: insertion-ordered enumeration is NOT relied on; order
# follows first-seen via a companion list.)
$InstallNormalized = @()
$InstallSeenNorm = @{}
$InstallAllExpanded = $false
foreach ($InstallRaw in $InstallModulesRaw) {
  $InstallNorm = Normalize-InstallModule $InstallRaw
  if (-not $InstallNorm) {
    Write-Output "Warning: Skipping empty/invalid module token '$InstallRaw' after normalization."
    continue
  }

  if ($InstallNorm -eq 'all') {
    if (-not $InstallAllExpanded) {
      Write-Output "Info: Expanding 'all' to all app modules..."
      $InstallAllSlash = Get-InstallAppModulesSlash
      if ($InstallAllSlash.Count -eq 0) {
        Write-Output "Error: Could not discover app modules for 'all' expansion."
        Show-InstallValidModules
        exit 1
      }
      foreach ($InstallSm in $InstallAllSlash) {
        if (-not $InstallSm) { continue }
        $InstallColon = $InstallSm.Replace('/', ':')
        if (-not $InstallSeenNorm.ContainsKey($InstallColon)) {
          $InstallSeenNorm[$InstallColon] = $true
          $InstallNormalized += $InstallColon
        }
      }
      $InstallAllExpanded = $true
    }
    continue
  }

  # Auto games:/personal: prefix
  if (-not $InstallNorm.Contains(':')) {
    foreach ($InstallAutoPrefix in @('games', 'personal')) {
      $InstallPrefPath = Join-Path $InstallRoot "$InstallAutoPrefix/$InstallNorm"
      $InstallPrefBuild = Join-Path $InstallPrefPath 'build.gradle.kts'
      $InstallRootAsDir = Join-Path $InstallRoot $InstallNorm
      if ((Test-Path -LiteralPath $InstallPrefBuild) -and
          (Select-String -LiteralPath $InstallPrefBuild -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
        $InstallExpanded = "$InstallAutoPrefix`:$InstallNorm"
        Write-Output "Info: Expanding shorthand '$InstallRaw' ($InstallNorm) -> $InstallExpanded (found $InstallAutoPrefix/$InstallNorm)"
        $InstallNorm = $InstallExpanded
        break
      }
      elseif ((-not (Test-Path -LiteralPath $InstallRootAsDir)) -and (Test-Path -LiteralPath $InstallPrefPath)) {
        $InstallExpanded = "$InstallAutoPrefix`:$InstallNorm"
        Write-Output "Info: Expanding shorthand '$InstallRaw' ($InstallNorm) -> $InstallExpanded (found $InstallAutoPrefix/$InstallNorm)"
        $InstallNorm = $InstallExpanded
        break
      }
    }
  }

  if (-not $InstallSeenNorm.ContainsKey($InstallNorm)) {
    $InstallSeenNorm[$InstallNorm] = $true
    $InstallNormalized += $InstallNorm
  }
}

if ($InstallNormalized.Count -eq 0) {
  Write-Output 'Error: No valid modules after normalization.'
  Show-InstallValidModules
  exit 1
}

# --- Validation ---
$InstallInvalid = @()
foreach ($InstallMod in $InstallNormalized) {
  $InstallFsPath = $InstallMod.Replace(':', '/')
  $InstallFullPath = Join-Path $InstallRoot $InstallFsPath
  $InstallBuildFile = Join-Path $InstallFullPath 'build.gradle.kts'
  if (-not (Test-Path -LiteralPath $InstallBuildFile)) {
    $InstallInvalid += "$InstallMod (not found: $InstallFsPath/build.gradle.kts)"
  }
  elseif (-not (Select-String -LiteralPath $InstallBuildFile -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
    $InstallInvalid += "$InstallMod (exists but is not an app module - missing common-conventions-app)"
  }
}
if ($InstallInvalid.Count -gt 0) {
  Write-Output 'Error: Some modules are invalid:'
  foreach ($InstallBad in $InstallInvalid) { Write-Output "  - $InstallBad" }
  Show-InstallValidModules
  exit 1
}

# --- Build Gradle tasks (assemble only; install happens below via adb sessions) ---
$InstallVariantCap = $InstallVariantLc.Substring(0, 1).ToUpperInvariant() + $InstallVariantLc.Substring(1)
$InstallTasks = @()
foreach ($InstallMod in $InstallNormalized) {
  $InstallTasks += ":${InstallMod}:assemble${InstallVariantCap}"
}

Write-Output "Variant: $InstallVariantLc (assemble + adb session install)"
Write-Output "Modules ($($InstallNormalized.Count)): $($InstallNormalized -join ' ')"
Write-Output "Gradle tasks: $($InstallTasks -join ' ')"
Write-Output ''

# --- Device selection: first connected device whose serial doesn't start with 'emulator' ---
$InstallAdbBin = Get-InstallAdb
$InstallTargetSerial = ''
if ($InstallAdbBin) {
  # hint '' = first non-emulator device; '8' = model Pixel_8;
  # '9' = model Pixel_9* (covers Pixel_9 and Pixel_9_Pro_XL).
  $InstallPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $InstallDevLines = & $InstallAdbBin devices -l 2>$null
  $ErrorActionPreference = $InstallPrevPref
  foreach ($InstallDevLine in @($InstallDevLines)) {
    $InstallParts = ([string]$InstallDevLine -split '\s+')
    if ($InstallParts.Count -lt 2) { continue }
    $InstallDevSerial = $InstallParts[0]
    $InstallDevState = $InstallParts[1]
    if ($InstallDevState -ne 'device') { continue }
    if ($InstallDevSerial -like 'emulator*') { continue }
    if ($InstallDevSerial -eq 'List') { continue }
    if (-not $InstallDeviceHint) {
      $InstallTargetSerial = $InstallDevSerial
      break
    }
    $InstallDevModel = ''
    $InstallModelM = [regex]::Match([string]$InstallDevLine, 'model:([^\s]+)')
    if ($InstallModelM.Success) { $InstallDevModel = $InstallModelM.Groups[1].Value }
    if (($InstallDeviceHint -eq '8') -and ($InstallDevModel -eq 'Pixel_8')) {
      $InstallTargetSerial = $InstallDevSerial
      break
    }
    if (($InstallDeviceHint -eq '9') -and ($InstallDevModel -like 'Pixel_9*')) {
      $InstallTargetSerial = $InstallDevSerial
      break
    }
  }
}

if (-not $InstallAdbBin) {
  if ($InstallDryRun) {
    Write-Output '[DRY-RUN] Warning: adb not found; a device could not be selected.'
  }
  else {
    Write-Output 'Error: adb not found; cannot select a target device.'
    Write-Output 'Install Android platform-tools and put adb on PATH (or set ANDROID_HOME).'
    exit 1
  }
}
elseif ($InstallTargetSerial) {
  $env:ANDROID_SERIAL = $InstallTargetSerial
  if ($InstallDeviceHint) {
    Write-Output "Target device: $($env:ANDROID_SERIAL) (Pixel $InstallDeviceHint)"
  }
  else {
    Write-Output "Target device: $($env:ANDROID_SERIAL) (first connected device not starting with 'emulator')"
  }
}
elseif ($InstallDeviceHint) {
  Write-Output "Error: No connected Pixel $InstallDeviceHint found (check 'adb devices')."
  exit 1
}
elseif ($InstallDryRun) {
  Write-Output '[DRY-RUN] Warning: no non-emulator device connected; ANDROID_SERIAL not set.'
}
else {
  Write-Output 'Error: No non-emulator device connected.'
  Write-Output "Connect a physical device (check 'adb devices'); refusing to install to an emulator."
  exit 1
}

# --- Extra Gradle args: --continue so one broken module doesn't block the rest ---
$InstallGradleArgs = @('--continue')
$InstallGradleArgsStr = ' --continue'
if (($InstallVariantLc -eq 'release') -and $InstallAllExpanded) {
  $InstallGradleArgs += '-Pandroid.r8.maxWorkers=8'
  $InstallGradleArgsStr += ' -Pandroid.r8.maxWorkers=8'
}

# --- Big-build heap (see ./install comments): escalate only for ALL_EXPANDED ---
if ($InstallAllExpanded) {
  $InstallGradleArgs += '-Dorg.gradle.jvmargs=-Xmx46144m -Xms4512m -XX:MaxMetaspaceSize=1024m -XX:+UseG1GC -Dfile.encoding=UTF-8'
  $InstallGradleArgs += '--max-workers=32'
  $InstallGradleArgsStr += ' -Dorg.gradle.jvmargs=<46g-big-build-heap> --max-workers=32'
}

# --- versionCodeOverride (see ./install comments) ---
$InstallRepoVersion = [int]([System.IO.File]::ReadLines((Join-Path $InstallRoot 'version.txt')) | Select-Object -First 1).Trim()
$InstallNeedBump = $InstallRepoVersion
if ($InstallTargetSerial) {
  $InstallPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  foreach ($InstallMod in $InstallNormalized) {
    $InstallPkg = Get-InstallApplicationId $InstallMod
    if (-not $InstallPkg) { continue }
    $InstallListOut = & $InstallAdbBin -s $InstallTargetSerial shell pm list packages --show-versioncode $InstallPkg 2>$null
    $InstallDevV = 0
    foreach ($InstallListLine in @($InstallListOut)) {
      $InstallVm = [regex]::Match([string]$InstallListLine, 'versionCode:([0-9]+)')
      if ($InstallVm.Success) { $InstallDevV = [int]$InstallVm.Groups[1].Value; break }
    }
    if (($InstallDevV -gt 0) -and ($InstallDevV -ge $InstallNeedBump)) {
      $InstallSysOut = & $InstallAdbBin -s $InstallTargetSerial shell pm list packages -s $InstallPkg 2>$null
      $InstallIsSystem = $false
      foreach ($InstallSysLine in @($InstallSysOut)) {
        if ([string]$InstallSysLine.Trim() -eq "package:$InstallPkg") { $InstallIsSystem = $true; break }
      }
      if ($InstallIsSystem) { $InstallTarget = $InstallDevV + 1; $InstallKind = 'system app' }
      else { $InstallTarget = $InstallDevV; $InstallKind = 'user app' }
      if ($InstallTarget -gt $InstallNeedBump) {
        $InstallNeedBump = $InstallTarget
        Write-Output "Info: $InstallPkg is a $InstallKind at versionCode $InstallDevV on-device (>= repo $InstallRepoVersion); will build with -PversionCodeOverride=$InstallTarget."
      }
    }
  }
  $ErrorActionPreference = $InstallPrevPref
}
if ($InstallNeedBump -gt $InstallRepoVersion) {
  $InstallGradleArgs += "-PversionCodeOverride=$InstallNeedBump"
  $InstallGradleArgsStr += " -PversionCodeOverride=$InstallNeedBump"
}

function Install-ApkViaSession([string]$Apk, [string]$PackageId, [string]$AdbBin, [string]$Serial, [string]$Cur = '', [string]$Total = '') {
  $InstallApkName = Split-Path -Leaf $Apk
  $InstallApkSize = (Get-Item -LiteralPath $Apk).Length
  $InstallApkMb = '{0:N1}' -f ($InstallApkSize / 1MB)
  $InstallDeviceApk = "/data/local/tmp/install_${PackageId}.apk"
  $InstallPrefix = ''
  if ($Cur -and $Total) { $InstallPrefix = "[$Cur/$Total] " }
  Write-Output "$InstallPrefix Installing $PackageId ($InstallApkName, ${InstallApkMb} MB)..."
  Write-Output "  $InstallPrefix[1/4] Pushing APK to device..."
  $InstallPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $AdbBin -s $Serial push $Apk $InstallDeviceApk >$null 2>&1
  if ($LASTEXITCODE -ne 0) {
    $ErrorActionPreference = $InstallPrevPref
    Write-Output "  $InstallPrefix Error: adb push failed for $InstallApkName."
    return $false
  }
  Write-Output "  $InstallPrefix[2/4] Creating install session..."
  $InstallCreateOut = & $AdbBin -s $Serial shell pm install-create -r 2>&1
  $InstallSession = ''
  $InstallSm = [regex]::Match(($InstallCreateOut -join "`n"), 'created install session \[([0-9]+)\]')
  if ($InstallSm.Success) { $InstallSession = $InstallSm.Groups[1].Value }
  if (-not $InstallSession) {
    Write-Output "  $InstallPrefix Error: could not create install session."
    & $AdbBin -s $Serial shell rm -f $InstallDeviceApk >$null 2>&1
    $ErrorActionPreference = $InstallPrevPref
    return $false
  }
  # Stage the APK first, then its .idsig sidecar (V4 signature) under the
  # matching name. PackageManager enables fs-verity from a staged idsig, which
  # MAOS/GrapheneOS requires for updates to preinstalled system apps. Plain
  # `adb install` never stages the sidecar, so those updates always fail.
  Write-Output "  $InstallPrefix[3/4] Staging APK into session $InstallSession..."
  & $AdbBin -s $Serial shell pm install-write -S $InstallApkSize $InstallSession base.apk $InstallDeviceApk >$null 2>&1
  if ($LASTEXITCODE -ne 0) {
    Write-Output "  $InstallPrefix Error: failed to stage $InstallApkName into session $InstallSession."
    & $AdbBin -s $Serial shell pm install-abandon $InstallSession >$null 2>&1
    & $AdbBin -s $Serial shell rm -f $InstallDeviceApk >$null 2>&1
    $ErrorActionPreference = $InstallPrevPref
    return $false
  }
  $InstallIdsig = "$Apk.idsig"
  if (Test-Path -LiteralPath $InstallIdsig -PathType Leaf) {
    $InstallDeviceIdsig = "$InstallDeviceApk.idsig"
    Write-Output "  $InstallPrefix Staging .idsig sidecar..."
    & $AdbBin -s $Serial push $InstallIdsig $InstallDeviceIdsig >$null 2>&1
    if ($LASTEXITCODE -eq 0) {
      $InstallIdsigSize = (Get-Item -LiteralPath $InstallIdsig).Length
      & $AdbBin -s $Serial shell pm install-write -S $InstallIdsigSize $InstallSession base.apk.idsig $InstallDeviceIdsig >$null 2>&1
      if ($LASTEXITCODE -ne 0) {
        Write-Output "  $InstallPrefix Warning: could not stage .idsig; system-app updates may fail fs-verity."
      }
    }
    else {
      Write-Output "  $InstallPrefix Warning: could not push .idsig; system-app updates may fail fs-verity."
    }
    & $AdbBin -s $Serial shell rm -f $InstallDeviceIdsig >$null 2>&1
  }
  else {
    Write-Output "  $InstallPrefix Warning: no .idsig sidecar next to the APK; system-app updates may fail fs-verity."
  }
  Write-Output "  $InstallPrefix[4/4] Committing install session $InstallSession..."
  $InstallCommitOut = & $AdbBin -s $Serial shell pm install-commit $InstallSession 2>&1
  & $AdbBin -s $Serial shell rm -f $InstallDeviceApk >$null 2>&1
  # `pm install-commit` prints "Success" but exits 0 either way; fail on absence.
  if (-not (($InstallCommitOut -join "`n") -match '(?im)^Success')) {
    Write-Output "  $InstallPrefix Error: install failed for ${PackageId}: $($InstallCommitOut -join ' ')"
    $ErrorActionPreference = $InstallPrevPref
    return $false
  }
  if ($Cur -and $Total) {
    Write-Output "  Installed $PackageId. [$Cur/$Total done]"
  }
  else {
    Write-Output "  Installed $PackageId."
  }
  $ErrorActionPreference = $InstallPrevPref
  return $true
}

if ($InstallDryRun) {
  $InstallGradlewDisp = if (Test-Path -LiteralPath (Join-Path $InstallRoot 'gradlew.bat')) { Join-Path $InstallRoot 'gradlew.bat' } else { Join-Path $InstallRoot 'gradlew' }
  Write-Output "[DRY-RUN] Would execute: $InstallGradlewDisp $($InstallTasks -join ' ')$InstallGradleArgsStr"
  foreach ($InstallMod in $InstallNormalized) {
    $InstallApk = Get-InstallBuiltApk $InstallMod $InstallVariantLc
    if ($InstallApk) {
      Write-Output "[DRY-RUN] Would session-install $(Get-InstallApplicationId $InstallMod) from $InstallApk"
    }
    else {
      Write-Output "[DRY-RUN] Would session-install $(Get-InstallApplicationId $InstallMod) (APK expected after assemble)"
    }
  }
  exit 0
}

# gradlew: gradlew.bat on Windows when present, else extensionless via bash.
$InstallGradlewBat = Join-Path $InstallRoot 'gradlew.bat'
$InstallGradlewSh = Join-Path $InstallRoot 'gradlew'
$InstallBashCmd = Get-Command bash -ErrorAction SilentlyContinue
$InstallPrevPref = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if (Test-Path -LiteralPath $InstallGradlewBat) {
  Write-Output "Running: $InstallGradlewBat $($InstallTasks -join ' ')$InstallGradleArgsStr"
  Write-Output ''
  & $InstallGradlewBat @InstallTasks @InstallGradleArgs
  $InstallBuildExit = $LASTEXITCODE
}
elseif ($InstallBashCmd -and (Test-Path -LiteralPath $InstallGradlewSh)) {
  Write-Output "Running: $InstallGradlewSh $($InstallTasks -join ' ')$InstallGradleArgsStr"
  Write-Output ''
  & $InstallBashCmd.Source $InstallGradlewSh @InstallTasks @InstallGradleArgs
  $InstallBuildExit = $LASTEXITCODE
}
else {
  Write-Output 'Error: no Gradle wrapper found (gradlew.bat / gradlew).'
  exit 1
}
$ErrorActionPreference = $InstallPrevPref
Write-Output ''

# Install whichever APKs were (re)built, even if sibling modules failed (--continue).
$InstallFailures = 0
if ($InstallBuildExit -ne 0) {
  Write-Output 'Warning: assemble reported failures; installing whatever APKs exist.'
}
$InstallTotal = $InstallNormalized.Count
Write-Output ''
Write-Output "Installing $InstallTotal app(s) to $InstallTargetSerial..."
$InstallCur = 0
foreach ($InstallMod in $InstallNormalized) {
  $InstallCur++
  $InstallApk = Get-InstallBuiltApk $InstallMod $InstallVariantLc
  if ($InstallApk) {
    if (-not (Install-ApkViaSession $InstallApk (Get-InstallApplicationId $InstallMod) $InstallAdbBin $InstallTargetSerial "$InstallCur" "$InstallTotal")) {
      $InstallFailures++
    }
  }
  else {
    Write-Output "[$InstallCur/$InstallTotal] Error: no APK found for :${InstallMod} (assemble failed?)."
    $InstallFailures++
  }
}
Write-Output ''
if ($InstallFailures -gt 0) {
  Write-Output "Install complete: $($InstallTotal - $InstallFailures)/$InstallTotal succeeded, $InstallFailures failed."
  exit 1
}
Write-Output "Install complete: $InstallTotal/$InstallTotal installed successfully."
exit $InstallBuildExit
