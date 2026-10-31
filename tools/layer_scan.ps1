$ErrorActionPreference = 'Stop'
$repo = 'D:\IdeaProject\pw_automation_framework'
$srcRoot = "$repo\pw-codegen-v2\src"
$scanMain = "$srcRoot\main\java\com\hsbc\cmb\hk\dbb\automation\framework\web\page\scan"
$enc = New-Object System.Text.UTF8Encoding($false)
$prefix = 'com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.'

# 1) move map: Class -> subpackage (RolePickerConstants stays root)
$move = @{
  'RoleEntry'='model'
  'RolePickerCaptureDispatcher'='capture'
  'RolePickerCdpConfig'='capture'
  'RolePickerCdpNodeExtractor'='capture'
  'RolePickerCdpSession'='capture'
  'RolePickerFramePath'='capture'
  'RolePickerPageTracker'='capture'
  'RolePickerScriptInjector'='capture'
  'RolePickerScripts'='capture'
  'RolePickerContext'='state'
  'RolePickerNlsCache'='state'
  'RolePickerQuiet'='state'
  'RolePickerSessionState'='state'
  'RolePickerClassNameResolver'='naming'
  'RolePickerPickParser'='naming'
  'RolePickerSnapshotParser'='naming'
  'RoleElementAssertionGenerator'='generate'
  'RoleElementPageGenerator'='generate'
  'RoleElementStepGenerator'='generate'
  'RolePickerCodeAssembler'='generate'
  'RoleElementPicker'='record'
  'RolePickerCommandEngine'='record'
  'RolePickerPanelController'='record'
  'RolePickerPanelSync'='record'
  'RoleRecordSession'='record'
  'RoleElementPickerBridge'='spi'
  'RolePickerBridgeRegistry'='spi'
}

# 2) git mv production files
foreach ($cls in $move.Keys) {
  $sub = $move[$cls]
  $src = (Join-Path $scanMain "$cls.java").Replace('\','/')
  $destDir = (Join-Path $scanMain $sub).Replace('\','/')
  if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir | Out-Null }
  $dest = (Join-Path $destDir "$cls.java").Replace('\','/')
  if (Test-Path $src) { git -C $repo mv $src $dest | Out-Null }
}

# gather all java files AFTER moves
$allJava = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }

# 3a) update package declarations of moved files
foreach ($cls in $move.Keys) {
  $sub = $move[$cls]
  $f = Join-Path $scanMain "$sub\$cls.java"
  if (Test-Path $f) {
    $txt = [System.IO.File]::ReadAllText($f, $enc)
    $txt = $txt -replace '(package\s+com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.page\.)scan;', "`${1}scan.$sub;"
    [System.IO.File]::WriteAllText($f, $txt, $enc)
  }
}

# 3b) update import lines + FQN usages in ALL java files
# process RoleElementPickerBridge first so RoleElementPicker pattern cannot touch it
$ordered = @('RoleElementPickerBridge') + ($move.Keys | Where-Object { $_ -ne 'RoleElementPickerBridge' })
foreach ($f in $allJava) {
  $txt = [System.IO.File]::ReadAllText($f, $enc)
  $changed = $false
  foreach ($cls in $ordered) {
    $sub = $move[$cls]
    $pat1 = [regex]::Escape($prefix) + [regex]::Escape($cls) + ';'
    $rep1 = $prefix + $sub + '.' + $cls + ';'
    if ($txt -match $pat1) { $txt = $txt -replace $pat1, $rep1; $changed = $true }
    if ($cls -eq 'RoleElementPicker') {
      $pat2 = [regex]::Escape($prefix) + 'RoleElementPicker\.(?!Bridge)'
    } else {
      $pat2 = [regex]::Escape($prefix) + [regex]::Escape($cls) + '\.'
    }
    $rep2 = $prefix + $sub + '.' + $cls + '.'
    if ($txt -match $pat2) { $txt = $txt -replace $pat2, $rep2; $changed = $true }
  }
  if ($changed) { [System.IO.File]::WriteAllText($f, $txt, $enc) }
}

# 3c) add imports for same-package references (bare usage) in non-defining files
function FileSub($f) {
  $t = [System.IO.File]::ReadAllText($f, $enc)
  if ($t -match 'package\s+com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.page\.scan\.(\w+);') {
    return $Matches[1]
  }
  return $null
}
foreach ($f in $allJava) {
  $txt = [System.IO.File]::ReadAllText($f, $enc)
  $lines = $txt -split "`n"
  $fsub = FileSub $f
  $need = @()
  foreach ($cls in $move.Keys) {
    $sub = $move[$cls]
    $defFile = (Join-Path $scanMain "$sub\$cls.java")
    if ($f -eq $defFile) { continue }
    if ($fsub -eq $sub) { continue }  # same package -> no import needed
    $importLine = 'import ' + $prefix + $sub + '.' + $cls + ';'
    if ($txt -match ([regex]::Escape($importLine))) { continue }
    if ($txt -match ("\b" + [regex]::Escape($cls) + "\b")) { $need += $importLine }
  }
  if ($need.Count -gt 0) {
    $insertIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\s*import ') { $insertIdx = $i } }
    if ($insertIdx -eq -1) {
      for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\s*package ') { $insertIdx = $i; break } }
    }
    $toInsert = $need | Sort-Object -Unique
    if ($insertIdx -eq -1) {
      $lines = @($toInsert) + $lines
    } else {
      $newLines = @()
      $newLines += $lines[0..$insertIdx]
      $newLines += $toInsert
      $newLines += $lines[($insertIdx + 1)..($lines.Count - 1)]
      $lines = $newLines
    }
    [System.IO.File]::WriteAllText($f, ($lines -join "`n"), $enc)
  }
}

# 3d) update SPI services file implementation FQN
$svc = "$repo\pw-codegen-v2\src\main\resources\META-INF\services\com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridge"
if (Test-Path $svc) {
  $t = [System.IO.File]::ReadAllText($svc, $enc)
  $t = $t -replace 'web\.page\.scan\.RoleElementPickerBridge', 'web.page.scan.spi.RoleElementPickerBridge'
  [System.IO.File]::WriteAllText($svc, $t, $enc)
}

# 3e) cleanup: drop regular (non-static) imports of moved classes whose simple name is absent elsewhere
foreach ($f in $allJava) {
  $txt = [System.IO.File]::ReadAllText($f, $enc)
  $lines = $txt -split "`n"
  $newLines = @(); $modified = $false
  foreach ($line in $lines) {
    if ($line -match '^\s*import\s+com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.page\.scan\.(\w+)\.(\w+);\s*$') {
      $sub2 = $Matches[1]; $cls2 = $Matches[2]
      if ($move.ContainsKey($cls2)) {
        $rest = ($lines | Where-Object { $_ -ne $line }) -join "`n"
        if ($rest -match ("\b" + [regex]::Escape($cls2) + "\b")) { $newLines += $line; continue }
        $modified = $true; continue  # drop truly-unused regular import
      }
    }
    $newLines += $line
  }
  if ($modified) { [System.IO.File]::WriteAllText($f, ($newLines -join "`n"), $enc) }
}

Write-Host 'LAYER_SCAN_DONE'
