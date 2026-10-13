# 운영 compose 배포 스크립트 (집 PC). self-hosted runner 가 부르거나 직접 실행한다.
#   .\MatJari\deploy\deploy.ps1            # master 를 pull 하고 backend·web 을 다시 빌드해 올린다
#   .\MatJari\deploy\deploy.ps1 -DryRun    # 하는 일만 출력한다(아무것도 바꾸지 않는다)
#
# 배포 전용 clone(예: C:\matjari-deploy)에서 쓴다. 개발 중인 작업 폴더에서 돌리면 브랜치를 master 로 바꿔 버린다.
# 하는 일: ① 추적 파일에 로컬 수정이 있으면 중단 ② master 로 fast-forward pull ③ backend·web 만 빌드해 재기동
# ④ 응답이 올 때까지 확인. DB(mysql)와 터널(matjari-tunnel)·.env 는 건드리지 않는다.
# 롤백은 자동으로 하지 않는다. 문제가 생기면 이전 커밋으로 git checkout 후 다시 실행한다.
param(
    [string]$Branch = 'master',
    [int]$HealthTimeoutSeconds = 180,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$matjari = Resolve-Path (Join-Path $PSScriptRoot '..')
$repo = Resolve-Path (Join-Path $matjari '..')
$compose = Join-Path $matjari 'docker-compose.prod.yml'

function Step($text, [scriptblock]$action) {
    Write-Host "==> $text"
    if ($DryRun) { Write-Host "    (dry-run) $($action.ToString().Trim())"; return }
    & $action
    if ($LASTEXITCODE -ne 0) { throw "실패: $text (종료 코드 $LASTEXITCODE)" }
}

Write-Host "저장소: $repo"
Write-Host "compose: $compose"

if (-not (Test-Path (Join-Path $matjari '.env'))) { throw "$matjari\.env 가 없습니다. .env.example 을 복사해 값을 채우세요." }

# 로컬에 고친 추적 파일이 있으면 pull 이 덮어쓸 수 있으니 멈춘다(.env 같은 추적 안 하는 파일은 상관없다)
$dirty = git -C $repo status --porcelain --untracked-files=no
if ($dirty) { throw "추적 파일에 로컬 수정이 있어 중단합니다:`n$dirty" }

Step "git pull ($Branch, fast-forward only)" {
    git -C $repo checkout $Branch
    if ($LASTEXITCODE -eq 0) { git -C $repo pull --ff-only }
}

Step "backend·web 빌드와 재기동 (mysql·터널은 그대로)" {
    docker compose -f $compose up -d --build backend web
}

Step "사용하지 않는 이미지 정리" {
    docker image prune -f
}

# 응답 확인: web(nginx)을 거쳐 백엔드까지 닿는지 본다. WEB_PORT 는 .env 에서 읽고 없으면 80
$port = 80
$match = Select-String -Path (Join-Path $matjari '.env') -Pattern '^\s*WEB_PORT\s*=\s*(\d+)' | Select-Object -First 1
if ($match) { $port = [int]$match.Matches[0].Groups[1].Value }
$url = "http://localhost:$port/api/auth/social/providers"
Write-Host "==> 응답 확인: $url (최대 $HealthTimeoutSeconds 초)"
if ($DryRun) { Write-Host '    (dry-run) 건너뜀'; exit 0 }

$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) { Write-Host '배포 완료: 정상 응답'; exit 0 }
    } catch { }
    Start-Sleep -Seconds 5
}
docker compose -f $compose logs --tail 40 backend
throw "배포 후 $HealthTimeoutSeconds 초 안에 정상 응답이 오지 않았습니다."
