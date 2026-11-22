# 운영 DB·업로드 파일 백업 스크립트 (로컬 PC). 작업 스케줄러가 하루 한 번 실행한다(register-backup-task.ps1).
#   .\MatJari\deploy\backup.ps1                         # 기본 폴더(%USERPROFILE%\MatJariBackups)에 백업, 14일 지난 백업 삭제
#   .\MatJari\deploy\backup.ps1 -BackupDir D:\backup -KeepDays 30
#   .\MatJari\deploy\backup.ps1 -DryRun                 # 하는 일만 출력한다
#
# 만드는 것(날짜별 폴더 하나):
#   matjari-YYYYMMDD-HHmmss\db.sql.gz       mysqldump --single-transaction (운영 중에도 일관된 스냅샷, 잠금 없음)
#   matjari-YYYYMMDD-HHmmss\uploads.tar.gz  업로드 볼륨(좌석 인증 사진·채팅 사진). 개인정보가 들어 있으니 백업 폴더 접근을 제한한다
# 운영 mysql 컨테이너가 꺼져 있으면 아무것도 하지 않고 끝낸다(오류 아님).
# 백업 폴더는 저장소 밖에 둔다(커밋되지 않게). 다른 디스크·외장 드라이브로 한 번 더 옮기면 PC 고장에도 남는다.
# 되살리기: gunzip -c db.sql.gz | docker compose -f docker-compose.prod.yml exec -T mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" matjari'
#           (되살리기 전에 지금 DB 를 한 번 더 백업한다. 업로드는 tar 를 볼륨에 다시 풀어 넣는다)
param(
    [string]$BackupDir = (Join-Path $env:USERPROFILE 'MatJariBackups'),
    [int]$KeepDays = 14,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$matjari = Resolve-Path (Join-Path $PSScriptRoot '..')
$compose = Join-Path $matjari 'docker-compose.prod.yml'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $BackupDir "matjari-$stamp"
$logFile = Join-Path $BackupDir 'backup.log'

function Log($text) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text
    Write-Host $line
    if (-not $DryRun) { Add-Content -Path $logFile -Value $line -Encoding UTF8 }
}

function Run($text, [scriptblock]$action) {
    Log "==> $text"
    if ($DryRun) { Write-Host "    (dry-run) $($action.ToString().Trim())"; return }
    & $action
    if ($LASTEXITCODE -ne 0) { throw "실패: $text (종료 코드 $LASTEXITCODE)" }
}

if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null }

try {
    $mysqlId = docker compose -f $compose ps -q --status running mysql 2>$null
    if (-not $mysqlId) { Log '운영 mysql 이 실행 중이 아니라 백업을 건너뜁니다.'; exit 0 }
    $backendId = docker compose -f $compose ps -q backend 2>$null

    if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $target | Out-Null }

    # 덤프는 컨테이너 안에서 파일로 만든 뒤 docker cp 로 꺼낸다(PowerShell 파이프는 바이너리를 망가뜨린다). 비밀번호는 컨테이너 환경변수로만 쓴다.
    Run 'DB 덤프 (컨테이너 안에서 mysqldump | gzip)' {
        docker exec $mysqlId sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --no-tablespaces matjari 2>/dev/null | gzip > /tmp/matjari-backup.sql.gz && test -s /tmp/matjari-backup.sql.gz'
    }
    Run 'DB 덤프 꺼내기' { docker cp "${mysqlId}:/tmp/matjari-backup.sql.gz" (Join-Path $target 'db.sql.gz') }
    Run '컨테이너 임시 파일 삭제' { docker exec $mysqlId rm -f /tmp/matjari-backup.sql.gz }

    if ($backendId) {
        Run '업로드 파일 묶기' { docker exec $backendId sh -c 'tar -czf /tmp/matjari-uploads.tar.gz -C /data seat-claims' }
        Run '업로드 묶음 꺼내기' { docker cp "${backendId}:/tmp/matjari-uploads.tar.gz" (Join-Path $target 'uploads.tar.gz') }
        Run '컨테이너 임시 파일 삭제' { docker exec $backendId rm -f /tmp/matjari-uploads.tar.gz }
    } else {
        Log 'backend 컨테이너가 없어 업로드 파일은 건너뜁니다.'
    }

    if (-not $DryRun) {
        $size = (Get-ChildItem $target -File | Measure-Object -Property Length -Sum).Sum
        Log ("백업 완료: {0} ({1:N0} 바이트)" -f $target, $size)
    }

    # 오래된 백업 정리: 이 스크립트가 만든 이름(matjari-yyyyMMdd-HHmmss)만 대상으로, 가장 최근 3개는 기간과 상관없이 남긴다
    $old = Get-ChildItem $BackupDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^matjari-\d{8}-\d{6}$' } |
        Sort-Object Name -Descending | Select-Object -Skip 3 |
        Where-Object { $_.CreationTime -lt (Get-Date).AddDays(-$KeepDays) }
    foreach ($d in $old) {
        Log "오래된 백업 삭제: $($d.Name)"
        if (-not $DryRun) { Remove-Item $d.FullName -Recurse -Force }
    }
}
catch {
    Log "오류: $($_.Exception.Message)"
    exit 1
}
