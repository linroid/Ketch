# Updates a portable copy of Ketch on Windows. Waits up to a minute for the app to quit, then up
# to another for every other program run from the app's folder to end, such as the browser
# extension's host, which keeps the app's files open. Then renames each item of the new version
# into the app's folder, and the old item it replaces into the backup folder, all on one volume;
# the data folder and items the new version lacks stay. If a rename fails, the old items go back.
# Old items that cannot go back are kept in data\update-backup-<time>. Opens the app, updated
# or not.
# Paths go to .NET rather than to cmdlets, which read [ and ] in them as wildcards.
param([int]$ProcessId, [string]$Source, [string]$Target, [string]$Backup, [string]$App)
"$(Get-Date): updating $Target from $Source"
Wait-Process -Id $ProcessId -Timeout 60 -ErrorAction SilentlyContinue

$folder = [System.IO.Path]::GetFullPath($Target).TrimEnd('\') + '\'
# Processes whose program lies in the app's folder; null paths belong to other users.
function Get-AppProcesses {
  Get-CimInstance -ClassName Win32_Process | Where-Object {
    $_.ExecutablePath -and
      $_.ExecutablePath.StartsWith($folder, [System.StringComparison]::OrdinalIgnoreCase)
  }
}
$deadline = (Get-Date).AddSeconds(60)
$running = @(Get-AppProcesses)
while ($running.Count -gt 0 -and (Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 500
  $running = @(Get-AppProcesses)
}

# Renames the file or folder $From to $To, which must not exist; fails rather than copy to
# another volume. Tries a few times, as a virus scanner may hold a file for a moment.
function Move-Exactly([string]$From, [string]$To) {
  for ($attempt = 1; ; $attempt++) {
    try {
      [System.IO.Directory]::Move($From, $To)
      return
    } catch {
      if ($attempt -ge 5) { throw }
      Start-Sleep -Seconds 1
    }
  }
}

if ($running.Count -gt 0) {
  'Programs still run from the app folder, so the old version stays:'
  $running | ForEach-Object { "  $($_.ProcessId) $($_.ExecutablePath)" }
} else {
  $saved = New-Object System.Collections.ArrayList
  $placed = New-Object System.Collections.ArrayList
  try {
    if ([System.IO.Directory]::Exists($Backup)) { [System.IO.Directory]::Delete($Backup, $true) }
    [void][System.IO.Directory]::CreateDirectory($Backup)
    $names = [System.IO.Directory]::GetFileSystemEntries($Source) |
      ForEach-Object { [System.IO.Path]::GetFileName($_) } |
      Where-Object { $_ -ne 'data' } | Sort-Object
    foreach ($name in $names) {
      $old = [System.IO.Path]::Combine($Target, $name)
      if ([System.IO.File]::Exists($old) -or [System.IO.Directory]::Exists($old)) {
        Move-Exactly $old ([System.IO.Path]::Combine($Backup, $name))
        [void]$saved.Add($name)
      }
      Move-Exactly ([System.IO.Path]::Combine($Source, $name)) $old
      [void]$placed.Add($name)
    }
    'updated'
  } catch {
    "Couldn't update: $($_.Exception.Message)"
    $restored = $true
    for ($i = $placed.Count - 1; $i -ge 0; $i--) {
      $name = $placed[$i]
      $new = [System.IO.Path]::Combine($Target, $name)
      try {
        Move-Exactly $new ([System.IO.Path]::Combine($Source, $name))
      } catch {
        "Couldn't move the new $name back: $($_.Exception.Message)"
        $restored = $false
      }
    }
    for ($i = $saved.Count - 1; $i -ge 0; $i--) {
      $name = $saved[$i]
      $old = [System.IO.Path]::Combine($Backup, $name)
      try {
        Move-Exactly $old ([System.IO.Path]::Combine($Target, $name))
      } catch {
        "Couldn't restore $name from the backup: $($_.Exception.Message)"
        $restored = $false
      }
    }
    if ($restored) {
      'the old version stays'
    } else {
      # The next launch deletes the updates folder, so keep what could not go back beside it.
      $kept = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetDirectoryName($Backup))
      $kept = [System.IO.Path]::Combine($kept, 'update-backup-' + (Get-Date -Format yyyyMMddHHmmss))
      try {
        Move-Exactly $Backup $kept
        "The old files that could not go back are in $kept"
      } catch {
        "The old files that could not go back are in $Backup, which the next launch deletes"
      }
    }
  }
}
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $App
$start.WorkingDirectory = $Target
$start.UseShellExecute = $true
[void][System.Diagnostics.Process]::Start($start)
