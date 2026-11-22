# backup.ps1 을 작업 스케줄러에 등록한다(매일 정해진 시각 + 놓쳤으면 PC 를 켠 뒤 바로). 내 Windows 계정으로 실행되며 Docker Desktop 이 켜져 있어야 한다.
# 관리자 권한은 필요 없다(일반 PowerShell 에서 실행). 배포 전용 clone 에서 실행한다(update.ps1 과 같은 곳).
#   .\register-backup-task.ps1                     # 매일 04:00
#   .\register-backup-task.ps1 -At 03:30 -BackupDir D:\MatJariBackups -KeepDays 30
#   .\register-backup-task.ps1 -Unregister         # 등록 해제
#   .\register-backup-task.ps1 -WhatIf             # 등록하지 않고 하려는 일만 출력
param(
    [string]$TaskName = 'matjari-backup',
    [string]$At = '04:00',
    [string]$BackupDir = (Join-Path $env:USERPROFILE 'MatJariBackups'),
    [int]$KeepDays = 14,
    [switch]$Unregister,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'backup.ps1'

if ($Unregister) {
    if ($WhatIf) { Write-Host "(what-if) 작업 '$TaskName' 해제"; exit 0 }
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "작업 '$TaskName' 을 해제했습니다."
    exit 0
}

if (-not (Test-Path $script)) { throw "backup.ps1 을 찾을 수 없습니다: $script" }
$arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`" -BackupDir `"$BackupDir`" -KeepDays $KeepDays"
Write-Host "실행: powershell.exe $arg"
Write-Host "주기: 매일 $At (놓친 실행은 PC 를 켠 뒤 바로)"
if ($WhatIf) { Write-Host '(what-if) 등록하지 않았습니다.'; exit 0 }

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg -WorkingDirectory (Split-Path $script)
$daily = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 60)
$me = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $daily -Settings $settings -Principal $principal `
    -Description '맞자리 운영 DB·업로드 파일을 매일 백업하고 오래된 백업을 지운다' -Force -ErrorAction Stop | Out-Null
Write-Host "작업 '$TaskName' 을 등록했습니다. 지금 한 번 실행해 보려면: Start-ScheduledTask -TaskName $TaskName"
