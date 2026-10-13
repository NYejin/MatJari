# 맞자리 — 로컬 실행 가이드

이 프로젝트는 두 가지 런타임(Java/Spring Boot, Node/React)이
섞여 있어서, **Docker Compose로 한 번에 격리된 환경에서 띄우는 방법(권장)**과
**각 서비스를 개별 가상환경(venv, node_modules, gradle)에서 직접 띄우는 방법**
두 가지를 모두 제공합니다.

---

## 방법 A. Docker Compose로 한 번에 실행 (권장)

전체 스택(mysql + backend + frontend)을 각각 격리된 컨테이너로
띄웁니다. 로컬에 Java/Node를 설치할 필요가 없습니다.

### 사전 준비
- Docker Desktop 설치 (https://www.docker.com/products/docker-desktop/)

### 실행

```bash
cp .env.example .env    # 필요 시 DB_PASSWORD, JWT_SECRET 값 수정
docker compose up --build
```

### 접속
| 서비스 | 주소 |
|---|---|
| 프론트엔드 | http://localhost:5173 |
| 백엔드 API | http://localhost:8080/api |
| MySQL | localhost:3306 (user: root) |

### 종료 / 초기화
```bash
docker compose down          # 컨테이너 종료
docker compose down -v       # 컨테이너 종료 + DB 데이터까지 삭제
```

### 코드 수정 반영
Dockerfile은 개발용으로 구성되어 있어 frontend는 코드 변경이
바로 반영됩니다(volume mount 추가 시). backend는 재빌드가 필요합니다:
```bash
docker compose up --build backend
```

---

## 방법 B. 서비스별 개별 가상환경에서 실행

Docker 없이 각자 언어별 가상환경에서 직접 띄우는 방법입니다. 2개 터미널이 필요합니다.

### 0. 공통 — MySQL 준비
로컬에 MySQL 8을 설치하고 DB를 생성합니다.
```sql
CREATE DATABASE matjari;
```

### 1. backend (Spring Boot)
Gradle Wrapper가 포함되어 있어 시스템에 Gradle을 따로 설치할 필요가 없습니다.
**반드시 `gradle`이 아니라 `./gradlew`(Windows는 `gradlew.bat`)를 사용하세요** — 버전 고정 및
VS Code 확장과의 충돌 방지를 위해 Wrapper로 통일했습니다.
```bash
cd backend
# 필요 시 환경변수로 DB 접속정보 오버라이드 (application.yml 참고)
export SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/matjari
export SPRING_DATASOURCE_PASSWORD=changeme

./gradlew bootRun        # macOS/Linux/Git Bash
gradlew.bat bootRun      # Windows cmd/PowerShell
```
기본 포트: `8080`

> VS Code에서 "Cannot mutate the dependency attributes..." 같은 Gradle 동기화 에러가 난다면,
> `./gradlew`가 생긴 뒤에도 VS Code를 완전히 종료하고 java/gradle 프로세스를 정리한 뒤
> 다시 열어야 합니다 (Wrapper가 있어도 기존에 꼬인 캐시/데몬은 저절로 안 풀립니다).

### 2. frontend (React + Vite)
```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```
기본 포트: `5173`

---

## 폴더 구조
```
MatJari/
├── docker-compose.yml   # 방법 A 진입점
├── .env.example
├── backend/             # Spring Boot (Dockerfile 포함)
└── frontend/             # React + Vite (Dockerfile 포함)
```

## 자주 겪는 문제
| 증상 | 원인/해결 |
|---|---|
| backend가 mysql 연결 실패 | Docker Compose는 healthcheck로 mysql 기동 후 backend가 뜨도록 되어 있음. 개별 실행 시 MySQL이 먼저 켜져 있는지 확인 |
| frontend에서 API 호출 실패(CORS 등) | SecurityConfig에 CORS 설정 적용됨 (localhost:5173 허용). 배포 시 실제 프론트 도메인으로 교체 필요 |
| MySQL Workbench에서 `matjari` 계정으로 접속 시 Access denied | MySQL은 `MYSQL_USER`/`MYSQL_PASSWORD` 환경변수를 **데이터 볼륨이 비어있는 최초 기동 시에만** 읽어서 계정을 만든다. 이미 떠 있던 환경을 업데이트했다면 `docker compose down -v`로 볼륨까지 지운 뒤 `docker compose up --build`로 다시 띄워야 `matjari` 계정이 생성됨. (`-v` 없이 내리면 기존 볼륨이 남아 계정이 안 만들어짐) |

## 운영 구성 (집 PC 온프레미스 등)

개발용 `docker-compose.yml`과 별개로 `docker-compose.prod.yml`을 쓴다. 밖에 열리는 포트는 nginx(`web`) 하나뿐이고, DB·백엔드는 내부 네트워크에서만 쓴다. 화면·`/api`·`/ws`가 같은 주소라 CORS 설정이 필요 없다.

```bash
cp .env.example .env        # DB_PASSWORD, JWT_SECRET, PUBLIC_URL 등을 채운다(아래)
docker compose -f docker-compose.prod.yml up -d --build
```

- `PUBLIC_URL`: 브라우저로 접속하는 주소. 이 PC에서만 `http://localhost`, 같은 공유기 안이면 `http://<이 PC의 사설 IP>`, 공개하면 `https://내도메인`.
- 접속 주소가 localhost가 아니면 메일 모드 `log`는 기동이 거부된다. `MAIL_MODE=smtp`와 `MAIL_*`를 채운다(Gmail은 앱 비밀번호).
- 공개 인터넷에 열 때: Cloudflare Zero Trust에서 터널을 만들고(서비스 `http://web:80`) `.env`에 `TUNNEL_TOKEN`, `SOCIAL_COOKIE_SECURE=true`를 넣은 뒤 `--profile tunnel`을 붙여 올린다. 공유기 포트포워딩은 필요 없다.
- 데이터는 Docker 볼륨(`mysql_data`, `upload_data`)에 있다. `down -v`는 DB까지 지운다. 백업: `docker compose -f docker-compose.prod.yml exec mysql mysqldump -u root -p matjari > backup.sql`.
- 한계: 백엔드가 프록시 뒤라 IP별 속도 제한(소셜·인증번호 발송)이 접속자 전체에 합쳐서 적용된다. 시연·소규모에서는 문제없지만 공개 운영 전에 신뢰 프록시 헤더 처리가 필요하다.

## 자동 배포 (CI/CD, 집 PC)

`master`에 병합하면 GitHub이 테스트를 돌리고, 통과하면 **도커 이미지(`backend`, `web`)를 ghcr.io에 올린다.** 집 PC는 몇 분마다 새 이미지가 있는지 확인해 가져와 교체한다. **PC가 GitHub의 코드를 실행하지 않는다**(self-hosted runner를 쓰지 않는 이유: public 저장소에서는 포크 PR로 내 PC에 코드가 실행될 위험이 있어서). 포트 개방이나 고정 IP도 필요 없다.

| 단계 | 어디서 | 무엇을 | 언제 |
|---|---|---|---|
| CI | GitHub 서버 (`.github/workflows/ci.yml`) | 백엔드 전체 테스트(임시 MySQL 통합 테스트 포함), 프런트 `tsc`·빌드 | 모든 PR, master 푸시 |
| 이미지 | GitHub 서버 (`ci.yml`의 `publish`) | `ghcr.io/nyejin/matjari-backend`·`matjari-web`을 `latest`와 `sha-xxxxxxx` 태그로 푸시 | **master 푸시에서 테스트가 모두 통과했을 때만** (PR·포크는 안 올림) |
| 갱신 | 내 PC (`MatJari/deploy/update.ps1`) | 이미지 pull → 바뀐 컨테이너만 교체 → 응답 확인 | 작업 스케줄러가 5분마다 |

갱신은 `backend`·`web`만 다룬다. DB(`mysql`)·터널(`matjari-tunnel`)·`.env`는 그대로라 데이터와 접속 주소는 유지된다. 이미지가 그대로면 아무것도 재시작하지 않는다. 교체할 때는 백엔드가 몇 초에서 수십 초 끊긴다(시연 중 병합 주의). 병합부터 서버 반영까지는 CI 15~20분 + 이미지 빌드 몇 분 + 확인 주기(5분)만큼 걸린다.

### 처음 한 번 설정
1. **Actions 변수**(개인정보처리방침 화면에 굳는 값): Settings → Secrets and variables → Actions → Variables에 `VITE_PRIVACY_OPERATOR`, `VITE_PRIVACY_CONTACT_EMAIL`, `VITE_PRIVACY_EFFECTIVE_DATE`. 값을 바꾸면 다음 master 푸시의 CI가 새 이미지를 만들 때 반영된다.
2. **보안 설정**: Settings → Actions → General → "Approval for running fork pull request workflows"를 **Require approval for all external contributors**로 둔다(비밀값 노출 방지). PR이 `.github/`를 건드렸으면 승인하지 않는다.
3. **첫 이미지 올리기**: 이 PR을 병합하면 CI가 끝난 뒤 저장소 오른쪽 **Packages**에 이미지 2개가 생긴다. 처음에는 비공개일 수 있다.
   - **공개로 전환(권장)**: 각 패키지 → Package settings → Change visibility → Public. 소스가 이미 public이고 비밀값은 `.env`에만 있어 이미지에 들어가지 않는다.
   - **비공개 유지**: `read:packages` 권한만 가진 토큰(PAT)을 만들어 PC에서 `docker login ghcr.io -u <깃허브 아이디>`를 한 번 한다.
4. **PC에 배포 전용 clone**을 만든다(개발 폴더에서 돌리면 브랜치가 master로 바뀐다). compose 파일을 최신으로 유지하는 용도다.
   ```
   git clone https://github.com/NYejin/MatJari.git C:\matjari-deploy
   copy <지금 운영 .env 경로> C:\matjari-deploy\MatJari\.env
   ```
   compose 프로젝트 이름이 `matjari-prod`로 고정이라 폴더가 달라도 같은 컨테이너·볼륨을 쓴다.
5. **한 번 직접 실행해서 확인**(PowerShell): `C:\matjari-deploy\MatJari\deploy\update.ps1` (`-DryRun`은 하는 일만 출력). 지금 돌고 있는 `backend`·`web`이 이미지 기반으로 교체되고 마지막에 "배포 완료: 정상 응답"이 나오면 된다.
6. **작업 스케줄러 등록**: `C:\matjari-deploy\MatJari\deploy\register-update-task.ps1`을 실행한다(5분마다 + 로그인 때. `-IntervalMinutes 10`, `-Unregister`, `-WhatIf` 지원). 내 Windows 계정으로 실행되고 Docker Desktop이 켜져 있어야 한다. Docker Desktop은 로그인 시 자동 시작으로 켜 둔다.

### 쓰는 법
- 평소: PR을 올리면 CI가 돈다. 병합하면 CI → 이미지 푸시 → (PC가) 확인 주기 안에 자동 교체.
- 지금 바로 반영하고 싶을 때: PC에서 `update.ps1`을 직접 실행하거나 `Start-ScheduledTask -TaskName matjari-update`.
- 로그: `C:\matjari-deploy\update.log`(마지막 2000줄만 유지). 교체가 실패하면 백엔드 로그 40줄이 같이 나온다.
- **되돌리기**: GitHub Packages에서 이전 커밋의 태그(`sha-xxxxxxx`)를 찾아 `.env`의 `IMAGE_TAG=sha-xxxxxxx`로 고치고 `update.ps1`을 실행한다. 이미지만 돌아가고 DB는 되돌아가지 않는다. 다시 최신으로 가려면 `IMAGE_TAG=latest`.
- **소스에서 직접 빌드**(이미지를 쓰지 않을 때): `docker compose -f docker-compose.prod.yml -f docker-compose.build.yml up -d --build`.

### 한계
- PC가 꺼져 있거나 Docker Desktop이 꺼져 있으면 갱신되지 않고, 켜진 뒤 다음 주기에 반영된다.
- 반영 지연(위 참고)과 교체 중 짧은 끊김이 있다. 자동 롤백·무중단 배포는 없다.
- DB 마이그레이션(Flyway)은 새 백엔드가 뜰 때 적용되므로 되돌릴 수 없는 마이그레이션은 병합 전에 주의한다.
- `.env` 값은 PC의 파일이라 병합으로 바뀌지 않는다. 새 설정값이 필요한 변경은 서버 PC의 `.env`를 직접 고쳐야 한다.
- CI 이미지 빌드는 GitHub 서버의 리눅스(amd64)다. Windows Docker Desktop(WSL 기반)에서 그대로 돈다.
