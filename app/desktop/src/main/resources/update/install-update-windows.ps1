# Installs a Ketch update on Windows. Waits up to a minute for the app to quit, runs the Windows
# Installer package with a progress bar, which upgrades the installed app once the user allows
# it, and opens the app, updated or not.
param([int]$ProcessId, [string]$Installer, [string]$App)
"$(Get-Date): installing $Installer"
Wait-Process -Id $ProcessId -Timeout 60 -ErrorAction SilentlyContinue
$arguments = @('/i', "`"$Installer`"", '/passive', '/norestart')
$msiexec = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru
"msiexec exited with $($msiexec.ExitCode)"
Remove-Item -LiteralPath $Installer -ErrorAction SilentlyContinue
Start-Process -FilePath $App
