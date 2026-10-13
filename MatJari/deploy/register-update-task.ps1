# update.ps1 을 작업 스케줄러에 등록한다(로그인 때와 N분마다 실행). 내 Windows 계정으로 실행되며 Docker Desktop 이 켜져 있어야 한다.
#   .\register-update-task.ps1                    # 5분마다
#   .\register-update-task.ps1 -IntervalMinutes 10
#   .\register-update-task.ps1 -Unregister        # 등록 해제
#   .\register-update-task.ps1 -WhatIf            # 등록하지 않고 하려는 일만 출력
param(
    [string]$TaskName = 'matjari-update',
    [int]$IntervalMinutes = 5,
    [switch]$Unregister,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'update.ps1'

if ($Unregister) {
    if ($WhatIf) { Write-Host "(what-if) 작업 '$TaskName' 해제"; exit 0 }
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "작업 '$TaskName' 을 해제했습니다."
    exit 0
}

if (-not (Test-Path $script)) { throw "update.ps1 을 찾을 수 없습니다: $script" }
$arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
Write-Host "실행: powershell.exe $arg"
Write-Host "주기: 로그인 때 + $IntervalMinutes 분마다"
if ($WhatIf) { Write-Host '(what-if) 등록하지 않았습니다.'; exit 0 }

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg -WorkingDirectory (Split-Path $script)
$repeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes) -RepetitionDuration (New-TimeSpan -Days 3650)
$logon = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($repeat, $logon) -Settings $settings `
    -Description '맞자리 운영 이미지를 확인해 새 버전이 있으면 backend·web 을 교체한다' -Force | Out-Null
Write-Host "작업 '$TaskName' 을 등록했습니다. 지금 한 번 실행해 보려면: Start-ScheduledTask -TaskName $TaskName"
