# 운영 compose 자동 갱신 스크립트 (로컬 PC). 작업 스케줄러가 몇 분마다 실행한다(register-update-task.ps1).
#   .\MatJari\deploy\update.ps1            # 새 이미지가 있으면 가져와 backend·web 을 교체한다
#   .\MatJari\deploy\update.ps1 -DryRun    # 하는 일만 출력한다(아무것도 바꾸지 않는다)
#
# 방식: GitHub CI 가 master 병합 때 도커 이미지(backend·web)를 ghcr.io 에 올린다. 이 PC 는 GitHub 의 코드를 실행하지 않고,
# 이미지를 가져와(pull) 바뀐 컨테이너만 교체한다. 이미지가 그대로면 아무것도 재시작하지 않는다(몇 분마다 돌려도 안전하다).
#
# 배포 전용 clone(예: C:\matjari-deploy)에서 쓴다. 개발 중인 작업 폴더에서 돌리면 브랜치를 master 로 바꿔 버린다.
# 하는 일: ① 추적 파일에 로컬 수정이 있으면 중단 ② compose 파일을 최신으로(git pull --ff-only) ③ 이미지 pull
# ④ backend·web 교체(바뀐 것만) ⑤ 교체했으면 응답 확인. DB(mysql)·터널·.env 는 건드리지 않는다.
# 롤백: .env 의 IMAGE_TAG 를 이전 커밋 태그(sha-xxxxxxx)로 고치면 다음 실행 때 그 이미지로 돌아간다(DB 는 되돌아가지 않는다).
param(
    [string]$Branch = 'master',
    [int]$HealthTimeoutSeconds = 180,
    [string]$LogFile,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$matjari = Resolve-Path (Join-Path $PSScriptRoot '..')
$repo = Resolve-Path (Join-Path $matjari '..')
$compose = Join-Path $matjari 'docker-compose.prod.yml'
if (-not $LogFile) { $LogFile = Join-Path $repo 'update.log' }
$lock = Join-Path $repo '.update.lock'

function Log($text) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text
    Write-Host $line
    if (-not $DryRun) {
        Add-Content -Path $LogFile -Value $line -Encoding UTF8
        # 로그가 무한히 커지지 않게 2000줄만 남긴다
        $all = Get-Content -Path $LogFile -Encoding UTF8
        if ($all.Count -gt 2500) { $all | Select-Object -Last 2000 | Set-Content -Path $LogFile -Encoding UTF8 }
    }
}

function Step($text, [scriptblock]$action) {
    Log "==> $text"
    if ($DryRun) { Write-Host "    (dry-run) $($action.ToString().Trim())"; return }
    & $action
    if ($LASTEXITCODE -ne 0) { throw "실패: $text (종료 코드 $LASTEXITCODE)" }
}

function RunningImages {
    $ids = docker compose -f $compose ps -q backend web renderer
    if (-not $ids) { return '' }
    (@($ids) | ForEach-Object { docker inspect --format '{{.Image}}' $_ }) -join ','
}

# 동시 실행 방지(이전 실행이 아직 도는 중이면 건너뛴다. 30분 넘은 잠금은 죽은 것으로 보고 무시)
if (Test-Path $lock) {
    if (((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes -lt 30) { Write-Host '이전 실행이 진행 중이라 건너뜁니다.'; exit 0 }
}
if (-not $DryRun) { Set-Content -Path $lock -Value (Get-Date -Format o) }

try {
    if (-not (Test-Path (Join-Path $matjari '.env'))) { throw "$matjari\.env 가 없습니다. .env.example 을 복사해 값을 채우세요." }

    # 로컬에 고친 추적 파일이 있으면 pull 이 덮어쓸 수 있으니 멈춘다(.env·update.log 같은 추적 안 하는 파일은 상관없다)
    $dirty = git -C $repo status --porcelain --untracked-files=no
    if ($dirty) { throw "추적 파일에 로컬 수정이 있어 중단합니다:`n$dirty" }

    Step "compose 파일 갱신 (git pull $Branch, fast-forward only)" {
        git -C $repo checkout $Branch
        if ($LASTEXITCODE -eq 0) { git -C $repo pull --ff-only }
    }

    $before = if ($DryRun) { '' } else { RunningImages }

    Step "새 이미지 확인 (backend·web pull)" {
        docker compose -f $compose pull backend web
    }

    Step "backend·web 교체 (바뀐 것만 재시작, mysql·터널은 그대로)" {
        docker compose -f $compose up -d backend web renderer
    }

    if ($DryRun) { Write-Host '    (dry-run) 교체 여부 확인·응답 확인은 건너뜀'; exit 0 }

    $after = RunningImages
    if ($before -eq $after) { Log '이미지 변경 없음'; exit 0 }
    Log "새 이미지로 교체됨: $after"

    Step "사용하지 않는 이미지 정리" {
        docker image prune -f
    }

    # 응답 확인: web(nginx)을 거쳐 백엔드까지 닿는지 본다. WEB_PORT 는 .env 에서 읽고 없으면 80
    $port = 80
    $match = Select-String -Path (Join-Path $matjari '.env') -Pattern '^\s*WEB_PORT\s*=\s*(\d+)' | Select-Object -First 1
    if ($match) { $port = [int]$match.Matches[0].Groups[1].Value }
    $url = "http://localhost:$port/api/auth/social/providers"
    Log "응답 확인: $url (최대 $HealthTimeoutSeconds 초)"
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { Log '배포 완료: 정상 응답'; exit 0 }
        } catch { }
        Start-Sleep -Seconds 5
    }
    docker compose -f $compose logs --tail 40 backend
    throw "교체 후 $HealthTimeoutSeconds 초 안에 정상 응답이 오지 않았습니다. .env 의 IMAGE_TAG 를 이전 태그로 고쳐 되돌릴 수 있습니다."
}
catch {
    Log "오류: $($_.Exception.Message)"
    exit 1
}
finally {
    if (-not $DryRun -and (Test-Path $lock)) { Remove-Item $lock -Force -ErrorAction SilentlyContinue }
}
