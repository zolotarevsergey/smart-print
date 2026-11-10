# Встановлення служби SportikPrint на Windows-ПК з принтером.
# Запуск: двічі клацнути install.cmd (сам попросить права адміністратора).
#   -AsUser  — замість служби Windows запускати при вході користувача (якщо зі служби не друкує).
#   -Uninstall — прибрати все.
param(
    [switch]$AsUser,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'

# без прав адміністратора — перезапускаємо себе з ними (окреме вікно)
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "`"$PSCommandPath`"")
    if ($AsUser) { $argList += '-AsUser' }
    if ($Uninstall) { $argList += '-Uninstall' }
    Start-Process powershell -Verb RunAs -ArgumentList $argList
    return
}
$Name = 'SportikPrint'
$Src = $PSScriptRoot
$Dst = Join-Path $env:ProgramFiles $Name

function Remove-Existing {
    $svc = Get-Service $Name -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.Status -ne 'Stopped') { Stop-Service $Name -Force }
        sc.exe delete $Name | Out-Null
        Start-Sleep 2
    }
    if (Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $Name -Confirm:$false
    }
    Get-Process $Name -ErrorAction SilentlyContinue | Stop-Process -Force
    Remove-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue
}

if ($Uninstall) {
    Remove-Existing
    Remove-Item $Dst -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "SportikPrint видалено." -ForegroundColor Green
    return
}

Remove-Existing

New-Item -ItemType Directory -Force $Dst | Out-Null
Copy-Item (Join-Path $Src 'SportikPrint.exe') $Dst -Force
# свій appsettings.json при оновленні не затираємо
if (-not (Test-Path (Join-Path $Dst 'appsettings.json'))) {
    Copy-Item (Join-Path $Src 'appsettings.json') $Dst
}

# порт з appsettings.json (у ньому є коментарі // — ConvertFrom-Json їх не любить)
$json = (Get-Content (Join-Path $Dst 'appsettings.json') -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*//' }) -join "`n"
$Port = (ConvertFrom-Json $json).Print.Port
if (-not $Port) { $Port = 8631 }

New-NetFirewallRule -DisplayName $Name -Direction Inbound -Protocol TCP -LocalPort $Port `
    -Action Allow -Profile Any -RemoteAddress LocalSubnet | Out-Null

$exe = Join-Path $Dst 'SportikPrint.exe'
if ($AsUser) {
    $action = New-ScheduledTaskAction -Execute $exe -WorkingDirectory $Dst
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 99 `
        -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Settings $settings `
        -Description 'Друк з Android на принтер цього ПК' | Out-Null
    Start-ScheduledTask -TaskName $Name
    Write-Host "Встановлено як завдання при вході користувача $env:USERNAME." -ForegroundColor Green
}
else {
    New-Service -Name $Name -BinaryPathName "`"$exe`"" -DisplayName 'SportikPrint (друк з Android)' `
        -StartupType Automatic -Description 'Приймає PDF/фото/текст з Android і друкує через драйвер принтера' | Out-Null
    sc.exe failure $Name reset= 86400 actions= restart/5000/restart/5000/restart/60000 | Out-Null
    Start-Service $Name
    Write-Host "Встановлено як службу Windows (стартує разом з ПК)." -ForegroundColor Green
}

Start-Sleep 3
try {
    $st = Invoke-RestMethod "http://localhost:$Port/api/status" -TimeoutSec 10
    Write-Host ""
    Write-Host "Принтер:  $($st.printer)"
    Write-Host "Стан:     $($st.state)"
    Write-Host "Усі принтери: $($st.printers -join ', ')"
}
catch {
    Write-Host "Служба не відповідає на порту ${Port}: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "У застосунку на телефоні вкажіть адресу цього ПК:" -ForegroundColor Cyan
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host "  $($_.IPAddress):$Port   ($($_.InterfaceAlias))" }
Write-Host ""
Write-Host "Журнал завдань: $env:ProgramData\SportikPrint\print.log"
