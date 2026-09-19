<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# search.bat - Scoped string search for the Modern-Apps monorepo.
# (PowerShell twin of ./search.) Uses rg when available, else Select-String.
#
# Usage (cmd):
#   search.bat PATTERN [-m MODULE ...] [-p PACKAGE ...] [-i] [--max N] [--files-only]
$ErrorActionPreference = 'Stop'

$SearchRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }
$SearchMaxCols = 220
$SearchDefaultMax = 200
$SearchKnownPackages = @('ui', 'data', 'domain', 'platform', 'network', 'intents', 'service', 'provider', 'widget', 'notifications', 'auth', 'sync', 'telephony')
$SearchExcludeDirs = @('target', 'build', '.gradle', '.kotlin', '.git', '.llms', 'analysis')

function Show-SearchHelp {
  @'
Usage:
  search.bat PATTERN [-m MODULE ...] [-p PACKAGE ...] [-i] [--max N] [--files-only]

  -m/--module   Repeatable, comma-separated. Slash == colon
                (games/voxels == games:voxels), shorthand (voxels -> games:voxels),
                or 'all' (every dir with build.gradle.kts).
  -p/--package  Repeatable, comma-separated root package (ui data domain platform
                network intents service provider widget notifications auth sync
                telephony). Searches only <module>/src/main/{java,kotlin}/**/<pkg>/.
                Needs -m; without it a warning is printed and it is ignored.
  -i            Case-insensitive. --max N caps output lines (default 200).
  --files-only  Print matching file paths, no contents.

Always excluded: target/ build/ .gradle/ .kotlin/ .git/ .llms/ analysis/
*.log *.onnx metadata_data/photos/. Exit code is 0 on success even with no
matches; 1 when every -m value is unknown; 2 on bad regex.
'@
}

function Normalize-SearchModule([string]$M) {
  $M = $M.Trim()
  $M = [regex]::Replace($M, '^[/:]+', '')
  $M = [regex]::Replace($M, '[/:]+$', '')
  $M = $M.Replace('/', ':')
  while ($M.Contains('::')) { $M = $M.Replace('::', ':') }
  return $M.ToLowerInvariant()
}

function Get-SearchAllModuleDirs {
  $SearchSettings = Join-Path $SearchRoot 'settings.gradle.kts'
  $SearchFound = @()
  if (-not (Test-Path -LiteralPath $SearchSettings)) { return $SearchFound }
  foreach ($SearchLine in [System.IO.File]::ReadLines($SearchSettings)) {
    if ($SearchLine -match 'include\(":([^"]+)"\)') {
      $SearchP = $Matches[1]
      $SearchP = [regex]::Replace($SearchP, '^:', '')
      $SearchP = $SearchP.Replace(':', '/')
      if ($SearchP -and (Test-Path -LiteralPath (Join-Path $SearchRoot "$SearchP/build.gradle.kts"))) {
        $SearchFound += $SearchP
      }
    }
  }
  return @($SearchFound | Sort-Object -Unique)
}

function ConvertTo-SearchRelative([string]$FullPath) {
  $SearchSlash = $FullPath.Replace('\', '/')
  $SearchRootSlash = $SearchRoot.Replace('\', '/').TrimEnd('/')
  if ($SearchSlash.StartsWith("$SearchRootSlash/")) {
    return $SearchSlash.Substring($SearchRootSlash.Length + 1)
  }
  return $SearchSlash
}

function Get-SearchPkgDirs([string]$Mod, [string[]]$Pkgs) {
  $SearchPkgFound = @()
  foreach ($SearchLang in @('java', 'kotlin')) {
    $SearchBase = Join-Path $SearchRoot "$Mod/src/main/$SearchLang"
    if (-not (Test-Path -LiteralPath $SearchBase)) { continue }
    foreach ($SearchPkg in $Pkgs) {
      # repo-relative paths (bash prints find-relatives too); absolute paths
      # would resurface as drive-colon lines that break path:line parsing.
      $SearchPkgFound += @(Get-ChildItem -LiteralPath $SearchBase -Recurse -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ceq $SearchPkg } |
        ForEach-Object { ConvertTo-SearchRelative $_.FullName })
    }
  }
  # drop dirs subsumed by another match (e.g. data/ inside data/)
  $SearchKept = @()
  foreach ($SearchA in $SearchPkgFound) {
    $SearchASlash = $SearchA.Replace('\', '/')
    $SearchSkip = $false
    foreach ($SearchB in $SearchPkgFound) {
      $SearchBSlash = $SearchB.Replace('\', '/')
      if (($SearchASlash -ne $SearchBSlash) -and $SearchASlash.StartsWith("$SearchBSlash/")) {
        $SearchSkip = $true
        break
      }
    }
    if (-not $SearchSkip) { $SearchKept += $SearchA }
  }
  return @($SearchKept | Sort-Object -Unique)
}

function Test-SearchExcluded([string]$FullPath) {
  $SearchSlash = $FullPath.Replace('\', '/')
  $SearchRootSlash = $SearchRoot.Replace('\', '/').TrimEnd('/')
  if ($SearchSlash.StartsWith("$SearchRootSlash/")) {
    $SearchSlash = $SearchSlash.Substring($SearchRootSlash.Length + 1)
  }
  $SearchSegs = $SearchSlash.Split('/')
  for ($SearchI = 0; $SearchI -lt $SearchSegs.Count - 1; $SearchI++) {
    if ($SearchExcludeDirs -contains $SearchSegs[$SearchI]) { return $true }
  }
  if ($SearchSlash -like 'metadata_data/photos/*') { return $true }
  $SearchLeaf = $SearchSegs[$SearchSegs.Count - 1]
  if ($SearchLeaf -like '*.log' -or $SearchLeaf -like '*.onnx') { return $true }
  return $false
}

$SearchHasRg = $null -ne (Get-Command rg -ErrorAction SilentlyContinue)

function Invoke-SearchRg([string]$Pattern, [string[]]$Dirs) {
  $SearchRgArgs = @('--no-heading', '--line-number', '--color', 'never', '--hidden', '--path-separator', '/',
    '-g', '!target/**', '-g', '!build/**', '-g', '!.gradle/**', '-g', '!.kotlin/**',
    '-g', '!.git/**', '-g', '!.llms/**', '-g', '!analysis/**',
    '-g', '!*.log', '-g', '!*.onnx', '-g', '!metadata_data/photos/**',
    '--max-columns', "$SearchMaxCols")
  if ($SearchIgnoreCase) { $SearchRgArgs += '-i' }
  $SearchRgArgs += @('-e', $Pattern, '--') + $Dirs
  $SearchPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $SearchOut = & rg @SearchRgArgs 2>$null
  $SearchRc = $LASTEXITCODE
  $ErrorActionPreference = $SearchPrevPref
  if ($SearchRc -eq 2) {
    [Console]::Error.WriteLine("error: bad regex: $Pattern")
    exit 2
  }
  $SearchLines = @()
  $SearchRootSlash = $SearchRoot.Replace('\', '/').TrimEnd('/')
  foreach ($SearchLine in @($SearchOut)) {
    $SearchNorm = ([string]$SearchLine).Replace('\', '/')
    $SearchNorm = [regex]::Replace($SearchNorm, '^\./', '')
    # defensive: if rg printed an absolute path (e.g. caller passed one),
    # relativize so path:line parsing and --files-only splits stay correct.
    if ($SearchNorm.StartsWith("$SearchRootSlash/")) {
      $SearchNorm = $SearchNorm.Substring($SearchRootSlash.Length + 1)
    }
    $SearchLines += $SearchNorm
  }
  return $SearchLines
}

function Invoke-SearchSelectString([string]$Pattern, [string[]]$Dirs) {
  try {
    $SearchOptions = [System.Text.RegularExpressions.RegexOptions]::None
    if ($SearchIgnoreCase) { $SearchOptions = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase }
    $null = New-Object System.Text.RegularExpressions.Regex($Pattern, $SearchOptions)
  }
  catch {
    [Console]::Error.WriteLine("error: bad regex: $Pattern")
    exit 2
  }
  $SearchFiles = @()
  foreach ($SearchDir in $Dirs) {
    $SearchAbs = if ([System.IO.Path]::IsPathRooted($SearchDir)) { $SearchDir } else { Join-Path $SearchRoot $SearchDir }
    if (-not (Test-Path -LiteralPath $SearchAbs)) { continue }
    $SearchItem = Get-Item -LiteralPath $SearchAbs -Force -ErrorAction SilentlyContinue
    if ($null -eq $SearchItem) { continue }
    if ($SearchItem -is [System.IO.DirectoryInfo]) {
      $SearchFiles += @(Get-ChildItem -LiteralPath $SearchAbs -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { -not (Test-SearchExcluded $_.FullName) })
    }
    elseif (-not (Test-SearchExcluded $SearchItem.FullName)) {
      $SearchFiles += $SearchItem
    }
  }
  $SearchLines = @()
  $SearchSelectOpts = @{}
  if ($SearchIgnoreCase) { $SearchSelectOpts['CaseSensitive'] = $false } else { $SearchSelectOpts['CaseSensitive'] = $true }
  foreach ($SearchFile in $SearchFiles) {
    $SearchMatches = Select-String -LiteralPath $SearchFile.FullName -Pattern $Pattern @SearchSelectOpts 2>$null
    foreach ($SearchM in @($SearchMatches)) {
      $SearchText = [string]$SearchM.Line
      if ($SearchText.Length -gt $SearchMaxCols) { $SearchText = $SearchText.Substring(0, $SearchMaxCols) }
      $SearchLines += "$(ConvertTo-SearchRelative $SearchFile.FullName):$($SearchM.LineNumber):$SearchText"
    }
  }
  return $SearchLines
}

function Invoke-SearchDirs([string]$Pattern, [string[]]$Dirs) {
  if ($SearchHasRg) { return Invoke-SearchRg $Pattern $Dirs }
  return Invoke-SearchSelectString $Pattern $Dirs
}

# --- arg parsing ---
$SearchPattern = ''
$SearchIgnoreCase = $false
$SearchMax = $SearchDefaultMax
$SearchFilesOnly = $false
$SearchModsRaw = @()
$SearchPkgsRaw = @()
$SearchIdx = 0
while ($SearchIdx -lt $args.Count) {
  $SearchArg = [string]$args[$SearchIdx]
  if ($SearchArg -eq '-h' -or $SearchArg -eq '--help' -or $SearchArg -eq 'help') {
    Show-SearchHelp
    exit 0
  }
  elseif ($SearchArg -eq '-m' -or $SearchArg -eq '--module') {
    $SearchIdx++
    while (($SearchIdx -lt $args.Count) -and (-not ([string]$args[$SearchIdx]).StartsWith('-'))) {
      $SearchModsRaw += [string]$args[$SearchIdx]
      $SearchIdx++
    }
  }
  elseif ($SearchArg -eq '-p' -or $SearchArg -eq '--package') {
    $SearchIdx++
    while (($SearchIdx -lt $args.Count) -and (-not ([string]$args[$SearchIdx]).StartsWith('-'))) {
      $SearchPkgsRaw += [string]$args[$SearchIdx]
      $SearchIdx++
    }
  }
  elseif ($SearchArg -eq '-i' -or $SearchArg -eq '--ignore-case') {
    $SearchIgnoreCase = $true
    $SearchIdx++
  }
  elseif ($SearchArg -eq '--max') {
    if ($SearchIdx + 1 -ge $args.Count) {
      [Console]::Error.WriteLine('Error: --max needs a value.')
      exit 1
    }
    $SearchMax = [int]$args[$SearchIdx + 1]
    $SearchIdx += 2
  }
  elseif ($SearchArg -like '--max=*') {
    $SearchMax = [int]$SearchArg.Substring('--max='.Length)
    $SearchIdx++
  }
  elseif ($SearchArg -eq '--files-only') {
    $SearchFilesOnly = $true
    $SearchIdx++
  }
  elseif ($SearchArg -eq '--') {
    $SearchIdx++
    if ((-not $SearchPattern) -and ($SearchIdx -lt $args.Count)) {
      $SearchPattern = [string]$args[$SearchIdx]
      $SearchIdx++
    }
  }
  elseif ($SearchArg.StartsWith('-')) {
    [Console]::Error.WriteLine("Error: unknown flag '$SearchArg'.")
    exit 1
  }
  else {
    if (-not $SearchPattern) {
      $SearchPattern = $SearchArg
    }
    else {
      [Console]::Error.WriteLine("Error: unexpected arg '$SearchArg' (pattern already set).")
      exit 1
    }
    $SearchIdx++
  }
}
if (-not $SearchPattern) {
  [Console]::Error.WriteLine('Error: no pattern. Usage: search.bat PATTERN [-m MODULE] [-p PACKAGE]')
  exit 1
}

# split comma-separated filter values
$SearchModsSplit = @()
foreach ($SearchM in $SearchModsRaw) {
  foreach ($SearchPart in $SearchM.Split(',')) {
    if ($SearchPart) { $SearchModsSplit += $SearchPart }
  }
}
$SearchPkgsSplit = @()
foreach ($SearchM in $SearchPkgsRaw) {
  foreach ($SearchPart in $SearchM.Split(',')) {
    if ($SearchPart) { $SearchPkgsSplit += $SearchPart.ToLowerInvariant() }
  }
}
foreach ($SearchP in $SearchPkgsSplit) {
  if ($SearchKnownPackages -notcontains $SearchP) {
    [Console]::Error.WriteLine("warning: unknown root package '$SearchP'.")
  }
}

# resolve modules
$SearchMods = @()
$SearchAllExp = $false
foreach ($SearchRaw in $SearchModsSplit) {
  $SearchNorm = Normalize-SearchModule $SearchRaw
  if ($SearchNorm -eq 'all') {
    if (-not $SearchAllExp) {
      foreach ($SearchSm in (Get-SearchAllModuleDirs)) {
        if ($SearchSm) { $SearchMods += $SearchSm }
      }
      $SearchAllExp = $true
    }
    continue
  }
  if (-not $SearchNorm.Contains(':')) {
    foreach ($SearchPfx in @('games', 'personal')) {
      if (Test-Path -LiteralPath (Join-Path $SearchRoot "$SearchPfx/$SearchNorm/build.gradle.kts")) {
        $SearchNorm = "$SearchPfx`:$SearchNorm"
        break
      }
    }
  }
  $SearchFs = $SearchNorm.Replace(':', '/')
  if (-not (Test-Path -LiteralPath (Join-Path $SearchRoot $SearchFs))) {
    [Console]::Error.WriteLine("warning: skipping unknown module '$SearchRaw' (-> $SearchFs/).")
    continue
  }
  if ($SearchMods -notcontains $SearchFs) { $SearchMods += $SearchFs }
}
if (($SearchModsRaw.Count -gt 0) -and ($SearchMods.Count -eq 0)) {
  [Console]::Error.WriteLine('error: no valid modules (all -m values unknown)')
  exit 1
}

Set-Location -LiteralPath $SearchRoot  # backends then print repo-relative paths
# NOTE: state lives in a hashtable (reference type) rather than plain vars:
# this file also runs via & ([scriptblock]::Create(...)), where $script:
# scope modifiers leak outward, so plain-variable writes from inside
# functions would miss the scriptblock-level variable.
$SearchState = @{ Total = 0; Max = $SearchMax; FilesOnly = $SearchFilesOnly; Seen = @{} }
function Emit-SearchLines($Result) {
  foreach ($SearchLine in @($Result)) {
    if ($null -eq $SearchLine) { continue }
    if ($SearchState.Total -ge $SearchState.Max) { break }
    if ($SearchState.FilesOnly) {
      $SearchF = ([string]$SearchLine -split ':', 2)[0]
      if ($SearchState.Seen.ContainsKey($SearchF)) { continue }
      $SearchState.Seen[$SearchF] = $true
      Write-Output $SearchF
    }
    else {
      Write-Output $SearchLine
    }
    $SearchState.Total++
  }
}

if ($SearchMods.Count -gt 0) {
  foreach ($SearchMod in $SearchMods) {
    $SearchDirsList = @()
    if ($SearchPkgsSplit.Count -gt 0) {
      $SearchDirsList = @(Get-SearchPkgDirs $SearchMod $SearchPkgsSplit)
      if ($SearchDirsList.Count -eq 0) {
        [Console]::Error.WriteLine("warning: $SearchMod`: no $($SearchPkgsSplit -join '/') package under src/main/{java,kotlin}, skipped")
        continue
      }
    }
    else {
      if (Test-Path -LiteralPath (Join-Path $SearchRoot "$SearchMod/src")) {
        $SearchDirsList = @("$SearchMod/src")
      }
      else {
        $SearchDirsList = @($SearchMod)
      }
    }
    Write-Output "=== $SearchMod ==="
    Emit-SearchLines (Invoke-SearchDirs $SearchPattern $SearchDirsList)
    if ($SearchState.Total -ge $SearchMax) { break }
  }
}
else {
  if ($SearchPkgsSplit.Count -gt 0) {
    [Console]::Error.WriteLine('warning: no -m given: -p needs a module scope, package filter ignored (repo-wide search)')
  }
  Emit-SearchLines (Invoke-SearchDirs $SearchPattern @('.'))
}
[Console]::Error.WriteLine("--- $($SearchState.Total) match(es) ---")
exit 0
