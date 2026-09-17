#!/usr/bin/env pwsh
# Windows / PowerShell port of ./install (see the bash `install` for macOS/Linux).
# Kept feature-compatible with the bash version so `./install x y z` behaves the
# same on every platform.

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'

# All adb invocations go through Invoke-Adb below, which scopes
# $ErrorActionPreference to Continue around the call: adb writes transfer
# progress to stderr even on success, and under the script-wide Stop that
# would terminate every push. Explicit exit-code checks govern instead.

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = $ScriptDir

if ($null -eq $Arguments) { $Arguments = @() }

function Print-Help {
    Write-Host @'
Usage:
  ./install [dev|release] <module> [module...]
  ./install <module> [module...]            # defaults to dev
  ./install [dev|release] all               # installs all app modules
  ./install all                             # same, defaults to dev

Description:
  Ergonomic wrapper around ./gradlew :module:assembleDev + adb session install.
  - Variant is swappable and optional (defaults to dev)
  - Supports both slash and colon notations: games/voxels == games:voxels
  - Supports games shorthand: ./install voxels -> games:voxels (auto-prefixed if games/<name> is a game)
  - Supports personal shorthand: ./install dooraccess -> personal:dooraccess (auto-prefixed if personal/<name> is a personal app)
  - Supports "all" keyword: ./install all -> installs every app module
  - Supports multiple modules in one call (single gradlew invocation)
  - Installs via adb package sessions with the .idsig sidecar staged, so
    updates to preinstalled MAOS system apps get fs-verity (plain
    installDev/installRelease and `adb install` never stage it and always fail
    those updates). Auto-passes -PversionCodeOverride when the on-device
    package is at the same versionCode as the build: +1 for system apps,
    exactly the on-device version for user apps.

Examples:
  ./install dev contacts                    -> :contacts:assembleDev + session install
  ./install release games:voxels            -> :games:voxels:assembleRelease + session install
  ./install dev games/voxels                -> :games:voxels:assembleDev (slash normalized)
  ./install dev :games:voxels:              -> :games:voxels:assembleDev (colon trimming)
  ./install voxels                          -> :games:voxels:assembleDev (shorthand auto games:)
  ./install chess                           -> :games:chess:assembleDev (shorthand auto games:)
  ./install dooraccess                      -> :personal:dooraccess:assembleDev (shorthand auto personal:)
  ./install all                             -> :contacts:assembleDev :games:chess:assembleDev ... (all modules)
  ./install dev all                         -> same as above with dev variant
  ./install contacts calendar               -> :contacts:assembleDev :calendar:assembleDev
  ./install release contacts                -> :contacts:assembleRelease + session install
  ./install contacts dev                    -> :contacts:assembleDev (variant anywhere)
  ./install --help
  ./install --dry-run dev contacts          # prints what would run without executing gradle
  ./install --dry-run all                   # preview all modules

Variant rules:
  - dev (default): assembleDev + session install unless explicitly asked for release
  - release: assembleRelease + session install when explicitly requested
  - debug: BLOCKED - never use assembleDebug

Notes:
  - NEVER uninstalls an app (no uninstall tasks)
  - Validates modules against app modules (those applying common-conventions-app)
  - Installs to the first connected device whose serial does not start with 'emulator'
  - Set INSTALL_DRY_RUN=1 or pass --dry-run for dry-run mode
'@
}

function Normalize-Module([string]$m) {
    $m = $m.Trim()
    $m = $m -replace '^[/:]+', '' -replace '[/:]+$', ''
    $m = $m -replace '/', ':'
    while ($m -match '::') { $m = $m -replace '::', ':' }
    return $m.ToLower()
}

function Get-AppModulesSlash {
    $settingsFile = Join-Path $Root 'settings.gradle.kts'
    if (-not (Test-Path $settingsFile)) { return @() }

    $found = @()
    foreach ($line in Get-Content -LiteralPath $settingsFile) {
        if ($line -match 'include\(":([^"]+)"\)') {
            $slash = $Matches[1] -replace ':', '/'
            if ([string]::IsNullOrEmpty($slash)) { continue }
            $buildFile = Join-Path $Root (Join-Path $slash 'build.gradle.kts')
            if ((Test-Path -LiteralPath $buildFile) -and
                (Select-String -LiteralPath $buildFile -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
                $found += $slash
            }
        }
    }
    return ($found | Sort-Object -Unique)
}

function Print-ValidModules {
    $slashModules = Get-AppModulesSlash
    if (-not $slashModules -or $slashModules.Count -eq 0) {
        Write-Host '  (could not discover app modules)'
        return
    }
    Write-Host ''
    Write-Host 'Valid app modules (slash notation for filesystem, colon for Gradle):'
    Write-Host ''
    Write-Host ('  {0,-30} {1}' -f 'Filesystem (slash)', 'Gradle (colon)')
    Write-Host ('  {0,-30} {1}' -f '--------------------', '-------------------')
    foreach ($sm in $slashModules) {
        if ([string]::IsNullOrEmpty($sm)) { continue }
        $colon = $sm -replace '/', ':'
        Write-Host ('  {0,-30} :{1}' -f $sm, $colon)
    }
    Write-Host ''
    Write-Host 'Tip: Use either notation: ./install dev games/voxels  OR  ./install dev games:voxels'
    Write-Host 'Shorthand: ./install dev voxels  (auto-expands to games:voxels if games/<name> exists)'
    Write-Host '           ./install dev dooraccess  (auto-expands to personal:dooraccess if personal/<name> exists)'
    Write-Host 'All: ./install all  (installs every app module)'
}

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $bases = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($base in $bases) {
        if ([string]::IsNullOrEmpty($base)) { continue }
        $candidate = Join-Path $base 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function Invoke-Adb([string]$adbBin, [string[]]$adbArgs) {
    # Runs adb, capturing combined stdout+stderr as text without letting stderr
    # kill the script. adb writes transfer progress to stderr even on success;
    # with the script-wide $ErrorActionPreference='Stop' that would terminate
    # every push. Scoped to Continue here (saved/restored) so explicit exit-code
    # checks govern instead. Works on Windows PowerShell 5.1 and 7+.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $adbBin @adbArgs 2>&1 | ForEach-Object { "$_" }
        return @{ Output = @($out); Exit = $LASTEXITCODE }
    }
    finally {
        $ErrorActionPreference = $prev
    }
}

function Select-TargetSerial([string]$adb) {
    $listed = Invoke-Adb $adb @('devices')
    $out = @($listed.Output)
    foreach ($line in $out) {
        $line = $line.Trim()
        if ([string]::IsNullOrEmpty($line)) { continue }
        if ($line -like 'List of devices*') { continue }
        $parts = $line -split '\s+'
        if ($parts.Count -lt 2) { continue }
        $serial = $parts[0]
        $state = $parts[1]
        if ($state -ne 'device') { continue }
        if ($serial -like 'emulator*') { continue }
        return $serial
    }
    return $null
}

# --- Handle zero args early ---
if ($Arguments.Count -eq 0) {
    Print-Help
    Write-Host ''
    Write-Host 'Error: No modules specified.'
    Print-ValidModules
    exit 1
}

# --- Detect --help/-h/help anywhere ---
foreach ($arg in $Arguments) {
    switch ($arg) {
        '--help' { Print-Help; exit 0 }
        '-h'     { Print-Help; exit 0 }
        'help'   { Print-Help; exit 0 }
    }
}

# --- Dry-run detection (env or flag) ---
$DryRun = $false
if ($env:INSTALL_DRY_RUN -eq '1') { $DryRun = $true }
foreach ($arg in $Arguments) {
    if ($arg -eq '--dry-run') { $DryRun = $true }
}

# --- Build filtered args (exclude --dry-run) ---
$filtered = @()
foreach ($arg in $Arguments) {
    if ($arg -eq '--dry-run') { continue }
    $filtered += $arg
}

if ($filtered.Count -eq 0) {
    Print-Help
    Write-Host ''
    Write-Host 'Error: No modules specified (dry-run mode).'
    Print-ValidModules
    exit 1
}

# --- Arg parsing: variant detection anywhere ---
$VariantLc = ''
$modulesRaw = @()

foreach ($arg in $filtered) {
    $lc = $arg.ToLower()
    if ($lc -eq 'dev' -or $lc -eq 'release' -or $lc -eq 'debug') {
        if ([string]::IsNullOrEmpty($VariantLc)) {
            $VariantLc = $lc
        }
        elseif ($VariantLc -ne $lc) {
            Write-Host "Error: Multiple distinct variants specified: '$VariantLc' and '$lc'."
            Write-Host 'Please specify only one variant: dev or release.'
            Write-Host ''
            Print-Help
            exit 1
        }
    }
    else {
        $modulesRaw += $arg
    }
}

if ([string]::IsNullOrEmpty($VariantLc)) { $VariantLc = 'dev' }

if ($VariantLc -eq 'debug') {
    Write-Host "Error: 'debug' variant is blocked."
    Write-Host ''
    Write-Host '  - Never assemble or install a debug variant'
    Write-Host '  - Always install the dev build, unless specifically asked for release'
    Write-Host ''
    Write-Host "Use 'dev' (default) or 'release' instead:"
    Write-Host '  ./install dev <module>'
    Write-Host '  ./install release <module>'
    exit 1
}

if ($modulesRaw.Count -eq 0) {
    Write-Host "Error: No modules specified (only variant '$VariantLc' found)."
    Write-Host ''
    Print-Help
    Print-ValidModules
    exit 1
}

# --- Normalization + Dedup + Shorthand + "all" + auto games:/personal: prefix ---
$normalizedModules = @()
$allExpanded = $false

foreach ($raw in $modulesRaw) {
    $norm = Normalize-Module $raw
    if ([string]::IsNullOrEmpty($norm)) {
        Write-Host "Warning: Skipping empty/invalid module token '$raw' after normalization."
        continue
    }

    # Handle "all" keyword - expand to every app module
    if ($norm -eq 'all') {
        if (-not $allExpanded) {
            Write-Host "Info: Expanding 'all' to all app modules..."
            $allSlash = Get-AppModulesSlash
            if (-not $allSlash -or $allSlash.Count -eq 0) {
                Write-Host "Error: Could not discover app modules for 'all' expansion."
                Print-ValidModules
                exit 1
            }
            foreach ($sm in $allSlash) {
                if ([string]::IsNullOrEmpty($sm)) { continue }
                $colon = $sm -replace '/', ':'
                if ($normalizedModules -notcontains $colon) {
                    $normalizedModules += $colon
                }
            }
            $allExpanded = $true
        }
        continue
    }

    # Auto games:/personal: prefix - if token has no colon and games/<name> or
    # personal/<name> is a valid app module, prefix it.
    if ($norm -notmatch ':') {
        foreach ($autoPrefix in @('games', 'personal')) {
            $prefPath = Join-Path $Root (Join-Path $autoPrefix $norm)
            $prefBuild = Join-Path $prefPath 'build.gradle.kts'
            if ((Test-Path -LiteralPath $prefBuild) -and
                (Select-String -LiteralPath $prefBuild -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
                $expanded = "${autoPrefix}:${norm}"
                Write-Host "Info: Expanding shorthand '$raw' ($norm) -> $expanded (found $autoPrefix/$norm)"
                $norm = $expanded
                break
            }
            elseif ((-not (Test-Path -LiteralPath (Join-Path $Root $norm))) -and
                    (Test-Path -LiteralPath $prefPath)) {
                # Fallback: root/<name> doesn't exist but <prefix>/<name> does (backward compat)
                $expanded = "${autoPrefix}:${norm}"
                Write-Host "Info: Expanding shorthand '$raw' ($norm) -> $expanded (found $autoPrefix/$norm)"
                $norm = $expanded
                break
            }
        }
    }

    if ($normalizedModules -notcontains $norm) {
        $normalizedModules += $norm
    }
}

if ($normalizedModules.Count -eq 0) {
    Write-Host 'Error: No valid modules after normalization.'
    Print-ValidModules
    exit 1
}

# --- Validation ---
$invalidModules = @()
foreach ($mod in $normalizedModules) {
    $fsPath = $mod -replace ':', '/'
    $buildFile = Join-Path $Root (Join-Path $fsPath 'build.gradle.kts')
    if (-not (Test-Path -LiteralPath $buildFile)) {
        $invalidModules += "$mod (not found: $fsPath/build.gradle.kts)"
    }
    elseif (-not (Select-String -LiteralPath $buildFile -Pattern 'id("common-conventions-app")' -SimpleMatch -Quiet)) {
        $invalidModules += "$mod (exists but is not an app module - missing common-conventions-app)"
    }
}

if ($invalidModules.Count -gt 0) {
    Write-Host 'Error: Some modules are invalid:'
    foreach ($im in $invalidModules) {
        Write-Host "  - $im"
    }
    Print-ValidModules
    exit 1
}

# --- Build Gradle tasks (assemble only; install happens below via adb sessions) ---
$VariantCap = $VariantLc.Substring(0, 1).ToUpper() + $VariantLc.Substring(1)
$tasks = @()
foreach ($mod in $normalizedModules) {
    $tasks += ":${mod}:assemble${VariantCap}"
}

$modulesStr = $normalizedModules -join ' '
$tasksStr = $tasks -join ' '

Write-Host "Variant: $VariantLc (assemble + adb session install)"
Write-Host "Modules ($($normalizedModules.Count)): $modulesStr"
Write-Host "Gradle tasks: $tasksStr"
Write-Host ''

# --- Device selection: first connected device whose serial doesn't start with 'emulator' ---
$adb = Find-Adb
if (-not $adb) {
    if ($DryRun) {
        Write-Host '[DRY-RUN] Warning: adb not found; a device could not be selected.'
    }
    else {
        Write-Host 'Error: adb not found; cannot select a target device.'
        Write-Host 'Install Android platform-tools and put adb on PATH (or set ANDROID_HOME).'
        exit 1
    }
}
else {
    $target = Select-TargetSerial $adb
    if ($target) {
        $env:ANDROID_SERIAL = $target
        Write-Host "Target device: $target (first connected device not starting with 'emulator')"
    }
    elseif ($DryRun) {
        Write-Host '[DRY-RUN] Warning: no non-emulator device connected; ANDROID_SERIAL not set.'
    }
    else {
        Write-Host 'Error: No non-emulator device connected.'
        Write-Host "Connect a physical device (check 'adb devices'); refusing to install to an emulator."
        exit 1
    }
}
Write-Host ''

function Get-RepoVersionCode {
    $versionFile = Join-Path $Root 'version.txt'
    $code = (Get-Content -LiteralPath $versionFile | Select-Object -First 1).Trim()
    return [int]$code
}

function Get-ModuleApplicationId([string]$mod) {
    $fsPath = $mod -replace ':', '/'
    $buildFile = Join-Path $Root (Join-Path $fsPath 'build.gradle.kts')
    $m = Select-String -LiteralPath $buildFile -Pattern 'applicationId\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($m) { return $m.Matches[0].Groups[1].Value }
    return $null
}

function Get-BuiltApk([string]$mod) {
    # output-metadata.json is authoritative for the APK file name (nested modules
    # like cast:tv build tv-dev.apk, not cast-tv-dev.apk).
    $fsPath = $mod -replace ':', '/'
    $outDir = Join-Path $Root (Join-Path $fsPath ("build/outputs/apk/$($VariantLc.ToLower())"))
    $metaFile = Join-Path $outDir 'output-metadata.json'
    $baseName = ($mod -split ':')[-1]
    $fileName = "$baseName-$($VariantLc.ToLower()).apk"
    $packageId = $null
    if (Test-Path -LiteralPath $metaFile) {
        $meta = Get-Content -LiteralPath $metaFile -Raw
        $pm = [regex]::Match($meta, '"applicationId"\s*:\s*"([^"]+)"')
        if ($pm.Success) { $packageId = $pm.Groups[1].Value }
        $fm = [regex]::Match($meta, '"outputFile"\s*:\s*"([^"]+)"')
        if ($fm.Success) { $fileName = $fm.Groups[1].Value }
    }
    if (-not $packageId) { $packageId = Get-ModuleApplicationId $mod }
    $apk = Join-Path $outDir $fileName
    if (-not (Test-Path -LiteralPath $apk)) { return $null }
    return @{ Apk = $apk; PackageId = $packageId }
}

function Get-DeviceVersions([string]$adbBin, [string]$serial, [string[]]$packageIds) {
    $versions = @{}
    if (-not $adbBin -or -not $serial) { return $versions }
    try {
        $listed = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'list', 'packages', '--show-versioncode')
        $out = @($listed.Output)
        foreach ($line in $out) {
            $m = [regex]::Match($line.Trim(), '^package:(\S+)\s+versionCode:(\d+)')
            if ($m.Success -and ($packageIds -contains $m.Groups[1].Value)) {
                $versions[$m.Groups[1].Value] = [int]$m.Groups[2].Value
            }
        }
    }
    catch {
        Write-Host 'Warning: could not query device package versions; proceeding without versionCodeOverride.'
    }
    return $versions
}

function Get-DeviceSystemApps([string]$adbBin, [string]$serial, [string[]]$packageIds) {
    $systemApps = @()
    if (-not $adbBin -or -not $serial) { return $systemApps }
    try {
        $listed = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'list', 'packages', '-s')
        $out = @($listed.Output)
        foreach ($line in $out) {
            $m = [regex]::Match($line.Trim(), '^package:(\S+)$')
            if ($m.Success -and ($packageIds -contains $m.Groups[1].Value)) {
                $systemApps += $m.Groups[1].Value
            }
        }
    }
    catch {
        Write-Host 'Warning: could not query device system packages; treating all as user apps.'
    }
    return $systemApps
}

function Install-ApkViaSession([string]$adbBin, [string]$serial, [string]$apk, [string]$packageId) {
    $apkSize = (Get-Item -LiteralPath $apk).Length
    $apkName = Split-Path -Leaf $apk
    $deviceApk = "/data/local/tmp/install_$packageId.apk"
    Write-Host "Installing $packageId ($apkName)..."
    $push = Invoke-Adb $adbBin @('-s', $serial, 'push', $apk, $deviceApk)
    if ($push.Exit -ne 0) { Write-Host "  Error: adb push failed for $apkName."; return $false }
    $created = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'install-create', '-r')
    $sessionOut = @($created.Output) -join "`n"
    $sm = [regex]::Match($sessionOut, 'created install session \[(\d+)\]')
    if (-not $sm.Success) {
        Write-Host "  Error: could not create install session: $sessionOut"
        Invoke-Adb $adbBin @('-s', $serial, 'shell', 'rm', '-f', $deviceApk) | Out-Null
        return $false
    }
    $session = $sm.Groups[1].Value
    # Stage the APK first, then its .idsig sidecar (V4 signature) under the
    # matching name. PackageManager enables fs-verity from a staged idsig, which
    # MAOS/GrapheneOS requires for updates to preinstalled system apps. Plain
    # `adb install` never stages the sidecar, so those updates always fail.
    $staged = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'install-write', '-S', "$apkSize", $session, 'base.apk', $deviceApk)
    if ($staged.Exit -ne 0) {
        Write-Host "  Error: failed to stage $apkName into session $session."
        Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'install-abandon', $session) | Out-Null
        Invoke-Adb $adbBin @('-s', $serial, 'shell', 'rm', '-f', $deviceApk) | Out-Null
        return $false
    }
    $idsig = "$apk.idsig"
    if (Test-Path -LiteralPath $idsig) {
        $idsigSize = (Get-Item -LiteralPath $idsig).Length
        $deviceIdsig = "$deviceApk.idsig"
        $ipush = Invoke-Adb $adbBin @('-s', $serial, 'push', $idsig, $deviceIdsig)
        if ($ipush.Exit -eq 0) {
            $istaged = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'install-write', '-S', "$idsigSize", $session, 'base.apk.idsig', $deviceIdsig)
            if ($istaged.Exit -ne 0) { Write-Host '  Warning: could not stage .idsig; system-app updates may fail fs-verity.' }
        }
        else { Write-Host '  Warning: could not push .idsig; system-app updates may fail fs-verity.' }
        Invoke-Adb $adbBin @('-s', $serial, 'shell', 'rm', '-f', $deviceIdsig) | Out-Null
    }
    else {
        Write-Host '  Warning: no .idsig sidecar next to the APK; system-app updates may fail fs-verity.'
    }
    $committed = Invoke-Adb $adbBin @('-s', $serial, 'shell', 'pm', 'install-commit', $session)
    $commitOut = @($committed.Output) -join "`n"
    Invoke-Adb $adbBin @('-s', $serial, 'shell', 'rm', '-f', $deviceApk) | Out-Null
    # `pm install-commit` prints Success either way; fail on absence of it.
    if ($committed.Exit -ne 0 -or $commitOut -notmatch '(?i)^Success') {
        Write-Host "  Error: install failed for $packageId : $commitOut"
        return $false
    }
    Write-Host "  Installed $packageId."
    return $true
}

$gradlew = Join-Path $ScriptDir 'gradlew.bat'

# --- Extra Gradle args: cap R8 workers for 'release all' (whole-repo minified build) ---
$gradleArgs = @('--continue')
$gradleArgsStr = ' --continue'
if ($VariantLc -eq 'release' -and $allExpanded) {
    $gradleArgs += @('-Pandroid.r8.maxWorkers=8')
    $gradleArgsStr += ' -Pandroid.r8.maxWorkers=8'
}

# --- Big-build heap: gradle.properties defaults to a small daemon heap sized for
# scoped work. A full-repo build ("all", ~100 modules, concurrent D8 dex merge)
# OOMs on it, so escalate here — and only here ($allExpanded). -D spawns a
# separate big daemon; the small one stays alive for normal builds.
if ($allExpanded) {
    $gradleArgs += @('-Dorg.gradle.jvmargs=-Xmx46144m -Xms4512m -XX:MaxMetaspaceSize=1024m -XX:+UseG1GC -Dfile.encoding=UTF-8')
    $gradleArgs += @('--max-workers=32')
    $gradleArgsStr += ' -Dorg.gradle.jvmargs=<46g-big-build-heap> --max-workers=32'
}

# --- versionCodeOverride: MAOS refuses to update a system package to the same
# versionCode, so an APK built from the same version.txt as the on-device OS image
# can never be installed over it. Query the device once; for system apps bump to
# one above the on-device version, for user apps just match it exactly
# (version.txt itself stays untouched).
$baseVersion = Get-RepoVersionCode
$repoVersion = $baseVersion
$packageIds = @()
$packageByModule = @{}
foreach ($mod in $normalizedModules) {
    $pkg = Get-ModuleApplicationId $mod
    if ($pkg) {
        $packageIds += $pkg
        $packageByModule[$mod] = $pkg
    }
}
$deviceVersions = Get-DeviceVersions $adb $target $packageIds
$systemApps = Get-DeviceSystemApps $adb $target $packageIds
$needBump = $false
foreach ($pkg in $packageIds) {
    if ($deviceVersions.ContainsKey($pkg) -and $deviceVersions[$pkg] -ge $repoVersion) {
        if ($systemApps -contains $pkg) {
            $targetVersion = $deviceVersions[$pkg] + 1
            $kind = 'system app'
        }
        else {
            $targetVersion = $deviceVersions[$pkg]
            $kind = 'user app'
        }
        if ($targetVersion -gt $repoVersion) {
            $repoVersion = $targetVersion
            $needBump = $true
            Write-Host "Info: $pkg is a $kind at versionCode $($deviceVersions[$pkg]) on-device (>= repo $baseVersion); will build with -PversionCodeOverride=$targetVersion."
        }
    }
}
if ($needBump) {
    $gradleArgs += @("-PversionCodeOverride=$repoVersion")
    $gradleArgsStr += " -PversionCodeOverride=$repoVersion"
}

if ($DryRun) {
    Write-Host "[DRY-RUN] Would execute: $gradlew $tasksStr$gradleArgsStr"
    foreach ($mod in $normalizedModules) {
        $built = Get-BuiltApk $mod
        $pkg = $packageByModule[$mod]
        if ($built) { Write-Host "[DRY-RUN] Would session-install $($built.PackageId) from $($built.Apk)" }
        else { Write-Host "[DRY-RUN] Would session-install $pkg (APK expected after assemble)" }
    }
    exit 0
}

Write-Host "Running: $gradlew $tasksStr$gradleArgsStr"
Write-Host ''

& $gradlew @tasks @gradleArgs
$buildExit = $LASTEXITCODE
Write-Host ''

# Install whichever APKs were (re)built, even if sibling modules failed (--continue).
$failures = 0
if ($buildExit -ne 0) {
    Write-Host 'Warning: assemble reported failures; installing whatever APKs exist.'
}
foreach ($mod in $normalizedModules) {
    $built = Get-BuiltApk $mod
    if (-not $built) {
        Write-Host "Error: no APK found for :${mod} (assemble failed?)."
        $failures++
        continue
    }
    if (-not (Install-ApkViaSession $adb $target $built.Apk $built.PackageId)) { $failures++ }
}
if ($failures -gt 0) { exit 1 }
exit $buildExit
