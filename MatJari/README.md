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
