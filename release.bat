<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# release.bat - Local script to prepare release, build, tag, and push (headless mode).
# (PowerShell twin of ./release.)
$ErrorActionPreference = 'Stop'

$ReleaseRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }
Set-Location -LiteralPath $ReleaseRoot

function Invoke-ReleaseGit([string[]]$GitArgs) {
  $ReleasePrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $ReleaseOut = & git @GitArgs 2>&1
  $ReleaseRc = $LASTEXITCODE
  $ErrorActionPreference = $ReleasePrevPref
  return @{ Out = @($ReleaseOut); Rc = $ReleaseRc }
}

function ConvertTo-ReleaseRelative([string]$FullPath) {
  $ReleaseSlash = $FullPath.Replace('\', '/')
  $ReleaseRootSlash = $ReleaseRoot.Replace('\', '/').TrimEnd('/')
  if ($ReleaseSlash.StartsWith("$ReleaseRootSlash/")) {
    return $ReleaseSlash.Substring($ReleaseRootSlash.Length + 1)
  }
  return $ReleaseSlash
}

function Get-ReleaseJsonString([string]$S) {
  return ($S -replace '\\', '\\' -replace '"', '\"')
}

# 0. Environment check: Ensure working directory is clean
$ReleaseStatus = Invoke-ReleaseGit @('status', '--porcelain')
if (($ReleaseStatus.Out | Where-Object { [string]$_ -ne '' }).Count -gt 0) {
  Write-Output 'Error: Your working directory is not clean.'
  Write-Output 'Please commit or stash your changes before running the release script.'
  exit 1
}

# Check for GitHub CLI (gh)
$ReleaseGhCmd = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ReleaseGhCmd) {
  Write-Output "Warning: 'gh' (GitHub CLI) is not installed. Release creation will be skipped."
  Write-Output '   Install it from https://cli.github.com/ (winget install GitHub.cli)'
  $ReleaseHasGh = $false
}
else {
  $ReleaseHasGh = $true
}

$ReleaseHead = Invoke-ReleaseGit @('rev-parse', 'HEAD')
$ReleaseOriginalCommit = ([string]$ReleaseHead.Out[0]).Trim()
$ReleaseVersionFile = Join-Path $ReleaseRoot 'version.txt'

if (-not (Test-Path -LiteralPath $ReleaseVersionFile -PathType Leaf)) {
  Write-Output 'Error: version.txt not found!'
  exit 1
}

$ReleaseVersionLines = [System.IO.File]::ReadAllLines($ReleaseVersionFile)
$ReleaseVersionCode = ([string]$ReleaseVersionLines[0]).Trim()
$ReleaseVersionName = ([string]$ReleaseVersionLines[1]).Trim()

$ReleaseEpoch = Invoke-ReleaseGit @('log', '-1', '--pretty=%ct')
$env:SOURCE_DATE_EPOCH = ([string]$ReleaseEpoch.Out[0]).Trim()
Write-Output "SOURCE_DATE_EPOCH=$($env:SOURCE_DATE_EPOCH) for reproducible builds"

Write-Output "Preparing headless release $ReleaseVersionName ($ReleaseVersionCode)..."

# 1. Inject version into build.gradle.kts files
Write-Output 'Injecting versions into sub-module build.gradle.kts files...'
$ReleaseBuildFiles = Get-ChildItem -LiteralPath $ReleaseRoot -Recurse -Filter 'build.gradle.kts' -File -Force -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName.Replace('\', '/') -notlike '*/build-logic/*' }
foreach ($ReleaseBf in $ReleaseBuildFiles) {
  $ReleaseLines = [System.IO.File]::ReadAllLines($ReleaseBf.FullName)
  $ReleaseKept = @()
  foreach ($ReleaseLine in $ReleaseLines) {
    if ($ReleaseLine -match 'versionCode\s*=') { continue }
    if ($ReleaseLine -match 'versionName\s*=') { continue }
    $ReleaseKept += $ReleaseLine
  }
  $ReleaseInjected = @()
  foreach ($ReleaseLine in $ReleaseKept) {
    $ReleaseInjected += $ReleaseLine
    if ($ReleaseLine -match 'defaultConfig\s*\{') {
      $ReleaseInjected += "        versionCode = $ReleaseVersionCode"
      $ReleaseInjected += "        versionName = `"$ReleaseVersionName`""
    }
  }
  [System.IO.File]::WriteAllLines($ReleaseBf.FullName, $ReleaseInjected)
}

# 2. Prepare F-Droid Metadata
Write-Output 'Preparing F-Droid metadata...'
$ReleaseModuleDirs = @($ReleaseBuildFiles |
  ForEach-Object { ConvertTo-ReleaseRelative $_.FullName } |
  ForEach-Object { [regex]::Replace($_, '/build\.gradle\.kts$', '') } |
  Sort-Object -Unique)
foreach ($ReleaseDir in $ReleaseModuleDirs) {
  $ReleaseModuleKey = $ReleaseDir.Replace('/', '-')
  $ReleaseTargetPath = Join-Path $ReleaseRoot "$ReleaseDir/src/main/play/listings/en-US"
  $ReleaseMd = Join-Path $ReleaseRoot "metadata_data/$ReleaseModuleKey.md"
  $ReleaseIcon = Join-Path $ReleaseRoot "$ReleaseDir/src/main/ic_launcher-playstore.png"
  if ((Test-Path -LiteralPath $ReleaseMd) -or (Test-Path -LiteralPath $ReleaseIcon)) {
    New-Item -ItemType Directory -Force -Path "$ReleaseTargetPath/graphics/icon" | Out-Null
    New-Item -ItemType Directory -Force -Path "$ReleaseTargetPath/graphics/phone-screenshots" | Out-Null
    if (Test-Path -LiteralPath $ReleaseIcon) {
      Copy-Item -LiteralPath $ReleaseIcon -Destination "$ReleaseTargetPath/graphics/icon/icon.png" -Force
    }
    $ReleasePhotosDir = Join-Path $ReleaseRoot "metadata_data/photos/$ReleaseModuleKey"
    $ReleasePhotoFile = Join-Path $ReleaseRoot "metadata_data/photos/$ReleaseModuleKey.png"
    if (Test-Path -LiteralPath $ReleasePhotosDir -PathType Container) {
      $ReleaseI = 1
      # natural (version) sort like `sort -V`: 2.png before 10.png.
      $ReleaseShots = Get-ChildItem -LiteralPath $ReleasePhotosDir -Filter '*.png' -File |
        Sort-Object { [regex]::Replace($_.Name, '\d+', { param($M) $M.Value.PadLeft(10, '0') }) }
      foreach ($ReleaseShot in $ReleaseShots) {
        Copy-Item -LiteralPath $ReleaseShot.FullName -Destination "$ReleaseTargetPath/graphics/phone-screenshots/$ReleaseI.png" -Force
        $ReleaseI++
      }
    }
    elseif (Test-Path -LiteralPath $ReleasePhotoFile) {
      Copy-Item -LiteralPath $ReleasePhotoFile -Destination "$ReleaseTargetPath/graphics/phone-screenshots/1.png" -Force
    }
    if (Test-Path -LiteralPath $ReleaseMd) {
      $ReleaseMdLines = [System.IO.File]::ReadAllLines($ReleaseMd)
      [System.IO.File]::WriteAllText("$ReleaseTargetPath/short-description.txt", $ReleaseMdLines[0])
      Copy-Item -LiteralPath $ReleaseMd -Destination "$ReleaseTargetPath/full-description.txt" -Force
    }
  }
}

# 3. Git Commit and Tag (Done BEFORE build so build sees committed state)
Write-Output 'Creating temporary commit and tag...'
# Reproducible builds: make commit/tag timestamps deterministic using SOURCE_DATE_EPOCH
# so the tag object doesn't embed wall-clock time and can be verified.
$env:GIT_AUTHOR_DATE = "@$($env:SOURCE_DATE_EPOCH)"
$env:GIT_COMMITTER_DATE = "@$($env:SOURCE_DATE_EPOCH)"
$ReleaseAdd = Invoke-ReleaseGit @('add', '.')
$ReleaseCommit = Invoke-ReleaseGit @('commit', '-m', "chore: prepare release $ReleaseVersionName")
if ($ReleaseCommit.Rc -ne 0) {
  Write-Output 'No changes to commit'
  exit 1
}
$ReleaseTag = Invoke-ReleaseGit @('tag', '-a', $ReleaseVersionName, '-m', "Release $ReleaseVersionName")
if ($ReleaseTag.Rc -ne 0) {
  Write-Output "Error: git tag failed: $($ReleaseTag.Out -join ' ')"
  exit 1
}

# 4. Build Apps (Parallel with Worker Limits)
Write-Output 'Building all app modules in parallel (constrained)...'
# Detect modules that apply the app conventions
$ReleaseAppModules = @()
foreach ($ReleaseBf in $ReleaseBuildFiles) {
  if (Select-String -LiteralPath $ReleaseBf.FullName -Pattern 'common-conventions-app' -Quiet) {
    $ReleaseAppModules += [regex]::Replace((ConvertTo-ReleaseRelative $ReleaseBf.FullName), '/build\.gradle\.kts$', '')
  }
}
$ReleaseAppModules = @($ReleaseAppModules | Sort-Object -Unique)
$ReleaseTasks = @()
foreach ($ReleaseMod in $ReleaseAppModules) {
  $ReleaseTasks += ":$($ReleaseMod.Replace('/', ':')):assembleRelease"
}

$ReleaseDistDir = Join-Path $ReleaseRoot 'distribution_apks'
if (Test-Path -LiteralPath $ReleaseDistDir) { Remove-Item -LiteralPath $ReleaseDistDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ReleaseDistDir | Out-Null

# - --parallel: Build modules in parallel
# - --max-workers=2: Strictly limit concurrent tasks to prevent memory spikes
# - --no-daemon: Use a fresh process to ensure memory is released after build
$ReleaseGradlewBat = Join-Path $ReleaseRoot 'gradlew.bat'
$ReleaseGradlewSh = Join-Path $ReleaseRoot 'gradlew'
$ReleaseBashCmd = Get-Command bash -ErrorAction SilentlyContinue
$ReleasePrevPref = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$ReleaseGradleArgs = @($ReleaseTasks) + @(
  '--parallel', '--max-workers=2', '--no-daemon',
  '-x', 'lint', '-x', 'test',
  '-Pandroid.enableResourceOptimizations=true',
  '-Pandroid.enableR8.fullMode=true',
  '-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g'
)
if ((Test-Path -LiteralPath $ReleaseGradlewBat) -and ($env:OS -eq 'Windows_NT')) {
  & $ReleaseGradlewBat @ReleaseGradleArgs
  $ReleaseBuildRc = $LASTEXITCODE
}
elseif ($ReleaseBashCmd -and (Test-Path -LiteralPath $ReleaseGradlewSh)) {
  & $ReleaseBashCmd.Source $ReleaseGradlewSh @ReleaseGradleArgs
  $ReleaseBuildRc = $LASTEXITCODE
}
else {
  $ErrorActionPreference = $ReleasePrevPref
  Write-Output 'Error: no Gradle wrapper found (gradlew.bat / gradlew).'
  exit 1
}
$ErrorActionPreference = $ReleasePrevPref
if ($ReleaseBuildRc -ne 0) {
  Write-Output "Error: Gradle build failed (exit $ReleaseBuildRc)."
  exit 1
}

# 5. Consolidate APKs
Write-Output 'Collecting APKs into distribution_apks/...'
Get-ChildItem -LiteralPath $ReleaseRoot -Recurse -Filter '*.apk' -File -Force -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName.Replace('\', '/') -like '*/build/outputs/apk/release/*.apk' } |
  ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $ReleaseDistDir -Force }

# 5b. Build index.json for the :appstore "Modern Apps" source.
# The store cannot map an asset filename back to a package name on its own
# (:games:chess builds chess-release.apk but installs as com.vayunmathur.games.chess),
# so the release has to state it. This index is NOT signed and does not need to be:
# :appstore verifies every APK from this source against its own signing certificate,
# so a rewritten index can change which bytes are offered but not which bytes install.
Write-Output 'Generating distribution_apks/index.json...'
$ReleaseSdkBase = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $HOME 'Library/Android/sdk' }
if ($env:LOCALAPPDATA -and (-not (Test-Path -LiteralPath (Join-Path $ReleaseSdkBase 'build-tools')))) {
  $ReleaseSdkBase = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
}
$ReleaseAapt2 = Get-ChildItem -LiteralPath (Join-Path $ReleaseSdkBase 'build-tools') -Filter 'aapt2*' -File -Recurse -ErrorAction SilentlyContinue |
  Sort-Object FullName | Select-Object -Last 1
if (-not $ReleaseAapt2) {
  Write-Output 'Error: aapt2 not found under $ANDROID_HOME/build-tools; cannot generate index.json'
  exit 1
}

# 5b index helpers (ConvertTo-ReleaseRelative, Get-ReleaseJsonString) are defined
# at the top next to Invoke-ReleaseGit: PowerShell executes straight-line code
# in order, so helpers must be defined before step 2 calls them.
$ReleaseIndexPath = Join-Path $ReleaseDistDir 'index.json'
$ReleaseIndexLines = @(
  '{',
  "  `"versionCode`": $ReleaseVersionCode,",
  "  `"versionName`": `"$ReleaseVersionName`",",
  '  "apps": ['
)
$ReleaseFirst = $true
$ReleaseApks = @(Get-ChildItem -LiteralPath $ReleaseDistDir -Filter '*.apk' -File | Sort-Object Name)
foreach ($ReleaseApk in $ReleaseApks) {
  $ReleaseBadging = & $ReleaseAapt2.FullName dump badging $ReleaseApk.FullName 2>$null
  $ReleaseBadgingText = ($ReleaseBadging -join "`n")
  $ReleasePkg = ''
  $ReleaseVcode = ''
  $ReleaseVname = ''
  $ReleaseTsdk = ''
  $ReleaseLabel = ''
  $ReleaseM = [regex]::Match($ReleaseBadgingText, "^package: name='([^']*)'", 'Multiline')
  if ($ReleaseM.Success) { $ReleasePkg = $ReleaseM.Groups[1].Value }
  $ReleaseM = [regex]::Match($ReleaseBadgingText, "versionCode='([^']*)'")
  if ($ReleaseM.Success) { $ReleaseVcode = $ReleaseM.Groups[1].Value }
  $ReleaseM = [regex]::Match($ReleaseBadgingText, "versionName='([^']*)'")
  if ($ReleaseM.Success) { $ReleaseVname = $ReleaseM.Groups[1].Value }
  $ReleaseM = [regex]::Match($ReleaseBadgingText, "^targetSdkVersion:'([^']*)'", 'Multiline')
  if ($ReleaseM.Success) { $ReleaseTsdk = $ReleaseM.Groups[1].Value }
  $ReleaseM = [regex]::Match($ReleaseBadgingText, "^application-label:'([^']*)'", 'Multiline')
  if ($ReleaseM.Success) { $ReleaseLabel = $ReleaseM.Groups[1].Value }
  if (-not $ReleasePkg) {
    Write-Output "Warning: Skipping $($ReleaseApk.Name) (aapt2 could not read a package name)"
    continue
  }
  if (-not $ReleaseLabel) { $ReleaseLabel = $ReleasePkg }
  $ReleaseSha = (Get-FileHash -LiteralPath $ReleaseApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  $ReleaseSize = (Get-Item -LiteralPath $ReleaseApk.FullName).Length
  # Short description = first line of the F-Droid metadata, when there is one.
  $ReleaseFoundBuild = ''
  foreach ($ReleaseBf in $ReleaseBuildFiles) {
    if (Select-String -LiteralPath $ReleaseBf.FullName -Pattern "applicationId = `"$ReleasePkg`"" -SimpleMatch -Quiet) {
      $ReleaseFoundBuild = $ReleaseBf.FullName
      break
    }
  }
  $ReleaseSummary = ''
  if ($ReleaseFoundBuild) {
    $ReleaseModKey = [regex]::Replace((ConvertTo-ReleaseRelative $ReleaseFoundBuild), '/build\.gradle\.kts$', '').Replace('/', '-')
    $ReleaseMdPath = Join-Path $ReleaseRoot "metadata_data/$ReleaseModKey.md"
    if (Test-Path -LiteralPath $ReleaseMdPath) {
      $ReleaseSummary = Get-ReleaseJsonString ([System.IO.File]::ReadAllLines($ReleaseMdPath)[0])
    }
  }
  if (-not $ReleaseFirst) { $ReleaseIndexLines += ',' }
  $ReleaseFirst = $false
  if (-not $ReleaseVcode) { $ReleaseVcode = '0' }
  if (-not $ReleaseVname) { $ReleaseVname = $ReleaseVersionName }
  if (-not $ReleaseTsdk) { $ReleaseTsdk = '0' }
  $ReleaseIndexLines[-1] += "    {`"packageName`": `"$ReleasePkg`", `"label`": `"$(Get-ReleaseJsonString $ReleaseLabel)`", `"apk`": `"$($ReleaseApk.Name)`", `"sha256`": `"$ReleaseSha`", `"size`": $ReleaseSize, `"versionCode`": $ReleaseVcode, `"versionName`": `"$(Get-ReleaseJsonString $ReleaseVname)`", `"targetSdk`": $ReleaseTsdk, `"summary`": `"$ReleaseSummary`"}"
}
$ReleaseIndexLines += '  ]'
$ReleaseIndexLines += '}'
[System.IO.File]::WriteAllLines($ReleaseIndexPath, $ReleaseIndexLines)

$ReleaseIndexed = ([regex]::Matches([System.IO.File]::ReadAllText($ReleaseIndexPath), '"packageName"')).Count
Write-Output "   $ReleaseIndexed apps indexed"

# 6. Push Tag and Create GitHub Release
Write-Output "Pushing tag $ReleaseVersionName to origin..."
$ReleasePush = Invoke-ReleaseGit @('push', 'origin', $ReleaseVersionName)
if ($ReleasePush.Rc -ne 0) {
  Write-Output "Error: git push failed: $($ReleasePush.Out -join ' ')"
  exit 1
}

if ($ReleaseHasGh) {
  Write-Output 'Creating GitHub Release...'
  $ReleaseAssets = @((Get-ChildItem -LiteralPath $ReleaseDistDir -Filter '*.apk' -File | ForEach-Object { $_.FullName })) + @($ReleaseIndexPath)
  $ReleasePrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $ReleaseGhCmd.Source release create $ReleaseVersionName @ReleaseAssets `
    --title "Release $ReleaseVersionName" `
    --notes "Automated multi-module release. Version Code: $ReleaseVersionCode" `
    --draft
  $ReleaseGhRc = $LASTEXITCODE
  $ErrorActionPreference = $ReleasePrevPref
  if ($ReleaseGhRc -ne 0) {
    Write-Output "Error: gh release create failed (exit $ReleaseGhRc)."
    exit 1
  }
  Write-Output 'Release created as a draft.'
}
else {
  Write-Output 'Skipping GitHub Release creation (gh CLI not found).'
}

# 7. Restore local branch state (Headless effect)
Write-Output "Restoring local branch to original state ($ReleaseOriginalCommit)..."
$ReleaseReset = Invoke-ReleaseGit @('reset', '--hard', $ReleaseOriginalCommit)
if ($ReleaseReset.Rc -ne 0) {
  Write-Output "Error: git reset failed: $($ReleaseReset.Out -join ' ')"
  exit 1
}

Write-Output 'Done! All apps built and tag pushed.'
exit 0
