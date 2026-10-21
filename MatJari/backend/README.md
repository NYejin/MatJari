# 맞자리 Backend (Spring Boot)

## 패키지 구조
- domain       — JPA 엔티티 (User·Performance·PerformanceSession·Ticket, 교환 희망 쪽 ExchangeRequest·ExchangeWantRange·ExchangeWantSession, 매칭 ExchangeMatch, 교환 이력 ExchangeHistory(V9), 알림 Notification(V10), 채팅 메시지 ChatMessage(V11), 차단 UserBlock(V13), 신고 AbuseReport(V13), 이용 정지 UserSanction(V13), 소셜 계정 SocialAccount(V14))와 enum·값 객체 (UserRole·TicketStatus·ExtraType·ExchangeRequestStatus·ExchangeMatchStatus·ExchangeMatchAction·SeatKey). 예약 잠금(exchange_ticket_lock)은 엔티티 없이 `ExchangeTicketLockRepository`(JdbcTemplate)가 다룬다. 공연장은 Performance.venueName 텍스트(V2에서 venue 테이블 삭제). 펼친 희망 좌석(exchange_want_seat)은 엔티티 없이 `ExchangeWantSeatRepository`(JdbcTemplate)가 다룬다. 채팅 읽음 포인터(chat_read_state)는 `ChatReadStateRepository`(JdbcTemplate)가 다룬다
- repository   — JpaRepository
- service      — 비즈니스 로직
- controller   — REST API + WebSocket(STOMP)
- dto          — request/response
- config       — Security, WebSocket 설정
- security     — JWT 발급/검증
- exception    — 커스텀 예외 + 전역 핸들러

## 현재 상태
- 2026-10-07 방향 전환으로 좌석표 트랙 코드와 교환·채팅·후기 등 빈 스켈레톤(컨트롤러·서비스·저장소)을 삭제했다. 좌석표 코드는 git 태그 `archive/seatmap-track-20261007`에 보관되어 있다.
- 엔티티는 User·Performance·PerformanceSession·Ticket과 교환 도메인 ExchangeRequest·ExchangeWantRange·ExchangeWantSession·ExchangeMatch·ExchangeHistory(V9)·Notification(V10)·ChatMessage(V11)·PerformanceEditProposal(V12)·SeatClaim(V12)·UserBlock(V13)·AbuseReport(V13)·UserSanction(V13)·SocialAccount(V14) 17종이다(예약 잠금·펼친 희망 좌석·채팅 읽음 포인터는 JdbcTemplate 저장소). Ticket은 구역·열·번(표시용 label + 정규화 key)·상태(ACTIVE/INACTIVE/EXCHANGED; `EXCHANGED`는 교환 완료 때 기존 티켓에 붙는다, V9)를 가지며 티켓 등록·조회·내리기 API가 있다(FR-03).
- **회원가입/로그인/JWT 인증(FR-01)은 구현 완료**:
  - `security/JwtTokenProvider` — access/refresh 토큰 발급·검증 (jjwt 0.12.5)
  - `security/JwtAuthenticationFilter` — Authorization 헤더 검증 후 SecurityContext 설정
  - `security/CustomUserDetailsService` — 이메일 기준 사용자 조회. 요청마다 호출되며 `AccountSuspensionChecker`로 활성 제재를 확인해 정지 계정이면 403 `ACCOUNT_SUSPENDED`(V13, 아래 '차단·신고·이용 정지' 절)
  - `config/SecurityConfig` — JWT 필터 등록, CORS(개발용 localhost:5173 허용), `/api/auth/**` permitAll, `/api/admin/**`는 ADMIN 권한 (관리자 컨트롤러 2종은 2026-10-12 구현, 아래 '관리자 API' 절)
  - `service/AuthService`, `controller/AuthController` — POST /api/auth/signup, /login, /refresh
  - 요청 DTO는 `jakarta.validation`으로 기본 검증(이메일 형식, 비밀번호 8자 이상) 적용
- **공연·회차 등록/조회(FR-02)는 구현 완료** (`PerformanceController`, `PerformanceService`). 공연은 회차와 함께 등록하며, 등록 후에는 아무도 제목·공연장·회차를 수정하거나 공연·회차를 삭제할 수 없다(수정은 관리자 수정 제안 승인으로만 바뀌며 2026-10-12 구현, 아래 '관리자 API' 절). 공연장은 공연의 텍스트 속성 `venueName`(필수, 1~100자)이다.
- **소셜 로그인(2026-10-12, `feature/social-login`, V14)은 구현 완료**: 카카오·구글·네이버, `/api/auth/social/**`(`SocialAuthController`, `service/social/`). 자세한 규칙은 아래 '소셜 로그인 (V14)' 절.
- **마이페이지 닉네임 수정·소셜 계정 연결·해제(2026-10-13, `feature/profile-edit`, V16)는 구현 완료**: 자세한 규칙은 아래 '프로필 수정·소셜 계정 연결 (V16)' 절.
- **회원탈퇴·개인정보처리방침(2026-10-13, `feature/withdraw`, V17)은 구현 완료**: `DELETE /api/users/me`, 익명화와 진행 중 교환 자동 정리. 자세한 규칙은 아래 '회원탈퇴·개인정보처리방침 (V17)' 절.
- **링크 기반 공연 정보 미리보기(2026-10-12, `feature/link-autofill`)는 구현 완료**(`GET /api/performances/preview`, DB 변경 없음). 자세한 규칙은 아래 '링크 공연정보 미리보기' 절.
- **공연 포스터 표시(2026-10-13, `feature/poster`, DB 변경 없음)는 구현 완료**(`GET /api/performances/{id}/poster`, 미리보기 응답의 `posterUrl`). 자세한 규칙은 아래 '공연 포스터 표시' 절.
- **회차 날짜 미리 채우기(2026-10-13, `feature/session-prefill`, DB 변경 없음)는 구현 완료**(미리보기 응답의 `sessionCandidates`, 공연 기간 100일 이하의 모든 날짜 — 2026-10-14에 2일 이하에서 확대). 자세한 규칙은 아래 '회차 날짜 후보' 절.
- STOMP 스켈레톤(`config/WebSocketConfig`의 TODO)은 채팅 구현(V11, 2026-10-11)으로 대체됐다. 아래 '채팅 (V11)' 절 참고.
- **티켓 등록(FR-03)은 구현 완료** (`TicketController`, `TicketService`, `SeatKeyNormalizer`). 좌석 1개를 텍스트(구역 필수, 열·번은 숫자 또는 문자)로 등록한다. 같은 회차·구역·열·번의 활성 티켓은 1개(DB `uk_ticket_active_seat`), 사용자당 활성 티켓 20개 상한(`users` 행 FOR UPDATE로 직렬화), 회차 당일 끝(다음날 0시 KST)까지만 등록할 수 있다. 교환 희망 범위·매칭·예약은 아래 교환 항목 참고. 내릴 때 예약 잠금이면 `TicketService.ensureCanDeactivate`가 409 `TICKET_RESERVED`로 막는다.
- **교환 희망 조건 등록(FR-04 교환 요청)은 구현 완료** (`ExchangeRequestController`, `ExchangeRequestService`, `WantSeatExpander`, Flyway V4). 티켓 하나에 미삭제 요청 1개(범위별 추가금 유형·희망 회차 우선순위·희망 좌석 범위)를 등록·조회·수정·소프트 삭제한다. 범위는 (구역, 열 from~to, 번 from~to)로 입력하면 개별 좌석으로 펼쳐 `exchange_want_seat`에 저장한다. **매칭 후보 조회**(`GET /api/exchange/requests/{id}/candidates`, 읽기 전용)는 구현됐다. 티켓을 내리면(`DELETE /api/tickets/{id}`) 그 티켓의 교환 요청은 CLOSED로 바뀐다.
- **매칭 생성·예약(FR-04 교환 흐름 일부)은 구현 완료** (`ExchangeMatchController`, `ExchangeMatchService`, Flyway V5). 후보를 골라 매칭(채팅 단계, CHATTING)을 만들고, 한쪽이 예약을 제안하고 상대가 수락하면 RESERVED가 되어 두 티켓이 잠기고, 누구든 예약을 취소해 CHATTING으로 되돌릴 수 있다(`reserve`·`reserve/accept`·`unreserve`, Flyway V8·V15. 2026-10-13 이전의 '한 명이 예약하면 즉시 RESERVED' 방식은 폐기). 채팅 종료(취소)·거절은 CHATTING에서만 가능하다. **교환 완료는 구현 완료(2026-10-11, V9)**: 양쪽 '교환 수락'(`complete`)으로 COMPLETED, 기존 티켓 EXCHANGED + 새 티켓, `exchange_history` 기록(아래 '교환 완료' 절). 교환 이력 조회 API·마이페이지 화면은 2026-10-12 구현 완료다. 후보 '예약 중' 뱃지는 폐기(후보 목록은 예약 잠긴 티켓을 계속 제외). (내 매칭 조회 `GET /api/exchange/matches/me`·`/{id}`는 구현됨) 아래 '매칭 API' 참고.
- **알림 6종 구현 완료(V10 2026-10-11 3종, V15 2026-10-13 6종, `feature/reserve-propose`)**: 새 제안·예약 제안·예약 제안 거절·상대 예약 수락·상대 예약 취소·새 채팅 메시지를 `notification`에 쌓고, 조회·안 읽은 개수·읽음·모두 읽음·읽은 알림 삭제 API 5개를 제공한다. 자세한 규칙은 아래 '알림 (V10)' 절. 실시간 푸시(STOMP 신호)는 2026-10-11 `feature/notifications-realtime`에서 구현됐다(아래 '알림 실시간 푸시' 절). 토스트는 만들지 않았다.
- **관리자 페이지 (V12, 2026-10-12, `feature/admin`)는 구현 완료**: 공연 수정 제안(제목·공연장)과 좌석 소유 인증 요청(내 티켓 인증, 사진 업로드) 두 가지를 관리자가 처리한다. 자세한 규칙은 아래 '관리자 API' 절.
- **사용자 차단·신고, 관리자 신고 처리·이용 정지(V13, 2026-10-12, `feature/block-report`)는 구현 완료**: 차단은 채팅 화면에서 시작하고 양쪽 후보에서 빠지며 열린 매칭은 시스템 취소된다. 신고는 채팅 화면에서 하고 관리자가 채팅 근거를 열람한다. 정지 계정은 모든 인증 API·로그인·재발급·STOMP CONNECT가 403이다. 자세한 규칙은 아래 '차단·신고·이용 정지 (V13)' 절.
- 테스트 수치(2026-10-12, `feature/block-report`): 백엔드 전체(임시 MySQL IT 포함) 781건 통과·실패 0·skip 0(V13 스키마 7, 차단 17, 신고·제재 약 45 포함). 프론트 `tsc`·빌드 통과, 브라우저 점검 미실시.
- 테스트 수치(2026-10-12, `feature/admin`): 백엔드 전체(임시 MySQL IT 포함) 708건 통과·실패 0·skip 0(V12 스키마 `AdminSchemaMysqlTest` 6건, 수정 제안 24건, 좌석 인증 31건 포함; 기본 모드와 IT 모드를 나눈 수치는 기록하지 않았다). 직전 기준(2026-10-12 기준, `feature/link-autofill`): 기본 `./gradlew test`는 632건 통과·0 실패, MySQL IT 141건은 건너뛴다(링크 자동 입력 신규 32건 포함: 파서 10, PageFetcher 9, 서비스 8, 컨트롤러 슬라이스 5). 직전 기준(2026-10-11, `feature/chat`)은 기본 595건 통과, IT 포함 612건 통과였다. 실제 MySQL이 필요한 `ExchangeCandidateQueryTest`·`ExchangeMatchMysqlTest`·`ExchangeMatchQueryMysqlTest`·`ExchangeCompleteMysqlTest`·`NotificationApiMysqlTest`·`ChatServiceMysqlTest`·`ChatSchemaMysqlTest`·`StompChatMysqlIT`·마이그레이션 테스트(V9 `MigrationV9MysqlTest` 포함)는 `MATJARI_IT_JDBC_URL`이 없으면 통째로 건너뛰며 리포트에 `[SKIPPED ...]` 메시지가 남는다. `MATJARI_IT_REQUIRED=true`와 `MATJARI_IT_*`를 주면 IT 포함 전체 실행이 된다(2026-10-12 IT 포함 실행은 재확인하지 않았다). `MATJARI_IT_REQUIRED=true`(또는 `CI` 환경변수가 있으면)는 건너뛰지 않고 실패한다. 주의: Gradle은 환경변수를 입력으로 보지 않아 이전 결과를 재사용하므로 환경을 바꿔 다시 돌릴 때는 `./gradlew cleanTest test`를 쓴다. 아래 '매칭 후보 조회 검증' 참고.

## 인증 API

| Method | Path | 설명 |
|---|---|---|
| POST | /api/auth/signup/email-code | 가입 전 이메일 인증번호 발송 (email) → {expiresInSeconds, resendAfterSeconds}. 가입된 이메일 400, 너무 잦으면 429 |
| POST | /api/auth/signup | 회원가입 (email, password, nickname, emailCode) → 생성된 사용자 정보 반환. emailCode는 인증이 켜져 있으면 필수 |
| POST | /api/auth/login | 로그인 (email, password) → accessToken, refreshToken 반환 |
| POST | /api/auth/refresh | refreshToken으로 accessToken 재발급 |
| GET | /api/auth/social/providers | 활성 소셜 제공자 목록 `{providers:[...]}`(client-id가 설정된 제공자만, 인증 불필요) |
| GET | /api/auth/social/{provider}/authorize | 제공자 인증 화면으로 302(provider = google·kakao·naver). state 쿠키 `ss_oauth` 발급. 비활성이면 404 `SOCIAL_PROVIDER_DISABLED`. `?link={ticket}`가 있으면 연결 모드('프로필 수정·소셜 계정 연결 (V16)' 절) |
| GET | /api/auth/social/{provider}/callback | 제공자 콜백. 성공·실패 모두 302(성공 `{frontend}/auth/callback#code=`, 실패 `{frontend}/login?socialError=`. 연결 모드는 성공 `{frontend}/me#linkCode=`, 실패 `{frontend}/me?socialLinkError=`) |
| POST | /api/auth/social/exchange | `{code}` → TokenResponse. 일회용 코드 60초, 틀리거나 만료되면 400 `INVALID_LOGIN_CODE`, 정지면 403 `ACCOUNT_SUSPENDED` |

## 공연·공연장·회차·내 정보 API

모두 로그인(Bearer 토큰)이 필요하다.

| Method | Path | 설명 |
|---|---|---|
| GET | /api/users/me | 내 정보 조회 |
| GET | /api/performances | 공연 목록 (제목 검색 query, page, size, asOf) |
| GET | /api/performances/lookup | 링크(sourceUrl)로 기존 공연 조회 ({exists, performanceId}) |
| GET | /api/performances/preview?sourceUrl= | 링크 공개 페이지에서 제목·공연장·공연 기간을 읽어 미리 채울 값을 돌려준다(저장하지 않음). 200 항상 `{status, site, title, venueName, periodStart, periodEnd, posterUrl, sessionCandidates}`. 자세한 규칙은 '링크 공연정보 미리보기' 절과 '회차 날짜 후보' 절 |
| GET | /api/performances/{id} | 공연 상세 |
| GET | /api/performances/{id}/poster | 공연 포스터 주소 `{posterUrl}`(문자열 또는 null). 로그인 필요, 공연이 없으면 404. 자세한 규칙은 '공연 포스터 표시' 절 |
| POST | /api/performances | 공연 등록 (201, 같은 링크가 있으면 409 + performanceId) |

## 링크 공연정보 미리보기 (2026-10-12, `feature/link-autofill`)

공연 등록 1단계에서 링크를 넣으면 그 링크의 공개 페이지에서 제목·공연장·공연 기간을 읽어 2단계에 미리 채울 값을 돌려준다. **저장하지 않으며 자동 등록도 하지 않는다**(사용자가 확인·수정한 뒤 등록). DB 변경은 없다(V12 아님).

- **대상 사이트**: YES24(`https://ticket.yes24.com/Perf/{id}`), 티켓링크(`https://www.ticketlink.co.kr/product/{id}`), 멜론(`https://ticket.melon.com/performance/index.htm?prodId={id}`). 인터파크는 robots.txt 때문에 제외(`UNSUPPORTED_SITE`). 로그인·캡차 우회, 내부 API(Ajax) 호출은 하지 않는다.
- **읽는 방법(실측, 2026-10-12, 실제 공개 페이지 raw HTML)**: YES24·티켓링크는 JSON-LD `Event`의 name, location.name, startDate/endDate(날짜만)를 읽는다. 멜론은 JSON-LD·og:title이 없어 본문 `.box_consert_txt p.tit`(제목), `dt.tit_info`가 공연장인 `dd`(비어 있는 상품도 있음), `#periodInfo`(기간)를 읽는다.
- **회차 시각은 읽지 않는다**: 세 사이트 모두 회차 목록·시각이 공개 HTML에 없고 Ajax(예매 달력)로만 온다. 그래서 회차는 채우지 않고, 기간이 있으면 회차 입력 근처에 기간만 안내한다(시작=끝이면 날짜 하나).
- **robots.txt 해석**: ticket.yes24.com·ticket.melon.com의 robots.txt는 규칙 대신 오류 페이지를 돌려줘 규칙이 없는 것으로 해석했다. 티켓링크는 상품 상세를 허용하고 `/reserve`·`/my`·`/ids`·`/facility`·`/search`는 금지한다.

### API

| Method | Path | 설명 |
|---|---|---|
| GET | /api/performances/preview?sourceUrl= | 로그인 필요. 200 항상 `{status, site, title, venueName, periodStart, periodEnd, posterUrl, sessionCandidates}`. `status`는 OK·UNSUPPORTED_SITE·NO_INFO·FETCH_FAILED, `site`는 yes24·ticketlink·melon·null |

- 링크 형식 오류는 400(기존 `SourceKeyResolver` 검증). 사용자당 분당 10회를 넘으면 429 `PREVIEW_RATE_LIMITED`와 `Retry-After`를 준다(형식 오류 400도 횟수에 센다). 사이트별 분당 30회·동시 실행 4개를 넘으면 429가 아니라 즉시 `FETCH_FAILED`다.
- 읽은 문자열은 태그를 지우고 공백을 정리하며 제어 문자를 뺀다. 제목 200자, 공연장 100자를 넘으면 그 필드만 null이다.
- 결과 캐시: 키 `site:id`, 10분, 최대 200개(LRU). OK·NO_INFO만 저장하고 FETCH_FAILED는 저장하지 않는다.

### SSRF 방어

- 사용자가 준 URL은 요청하지 않는다. `SourceKeyResolver.resolve`의 키가 `yes24:{숫자 id}`·`ticketlink:{id}`·`melon:{id}`일 때만 진행하고, 고정 템플릿 3종으로 URL을 직접 만든다. 그 밖(`url:` 일반 키, 인터파크, 잘못된 id)은 UNSUPPORTED_SITE이며 요청하지 않는다.
- `PageFetcher`(JDK HttpClient): https만 허용하고 리다이렉트를 따라가지 않는다(3xx는 FETCH_FAILED). 응답 전에 DNS 해석 주소가 모두 공인 IP인지 확인한다(루프백·사설·링크로컬·CGNAT·멀티캐스트·IPv6 ULA·IPv4-mapped 차단). 연결 5초, 전체 10초, 본문 2 MiB 상한, Content-Type `text/html`, `Accept-Encoding: identity`, User-Agent `MatJariBot/0.1 (+portfolio project; contact: ...)`, 쿠키·인증 없음.
- 알려진 한계: 검증한 IP로 연결을 고정(pinning)하지 않는다. 대상이 코드에 고정된 공개 도메인 3개라 DNS를 공격자가 바꿀 수 없다는 전제에 기댄다.

### 회차 날짜 후보 (sessionCandidates, 2026-10-13, `feature/session-prefill`, DB 변경 없음)

- 응답 `sessionCandidates`는 `[{date:"yyyy-MM-dd", time:null}]`이며 항상 배열이다(없으면 `[]`). 공연 기간(`periodStart`·`periodEnd`)의 시작·종료가 모두 있고 시작~종료가 **100일 이하**(시작·종료 포함, 상수 `PerformancePreviewResponse.MAX_PERIOD_DAYS_FOR_CANDIDATES`=100)일 때 첫날부터 마지막 날까지 모든 날짜를 담는다. 100일 초과, 기간 없음, 시작>종료면 빈 배열이다(2026-10-14에 2일 이하에서 확대).
- `time`은 지금 항상 null이다. 세 사이트의 회차 시각은 Ajax라 읽지 못하며, 나중에 AI 등 상세정보 출처가 붙으면 같은 필드를 채워 내려 주도록 구조만 열어 두었다(구현 없음).
- 생성은 `PerformancePreviewResponse.ok`가 `candidatesFromPeriod`로 한다. 실패·NO_INFO 응답(`empty`)은 항상 빈 배열이다.
- 새 외부 요청은 없다. 기존 파싱 결과의 기간으로 계산하므로 SSRF 규칙·속도 제한·캐시는 그대로다. 포스터 스위치가 꺼져 `posterUrl`만 null이 돼도 후보는 유지된다(`withoutPoster`).
- 테스트: 단위 `PerformancePreviewResponseTest`(하루·이틀은 날짜만, 3일 이상·기간 없음·역전은 빈 배열, `withoutPoster`가 후보 유지), 컨트롤러 슬라이스(응답 JSON의 `sessionCandidates` 형태). 전체 백엔드 테스트 수치는 이 문서에 기록하지 않았다.
- 알려진 한계: 시각은 직접 입력한다. 3일 이상 공연(뮤지컬 등)은 날짜 후보가 없다. 하루에 여러 회차(낮·밤)인 2일 공연은 후보가 날짜당 1개라 같은 날짜를 다시 골라 추가해야 한다. 공연 기간이 실제 회차 날짜와 다를 수 있다(휴연일 등).

### 구조와 테스트

- 구조: `service/linkinfo/PageFetcher`, `LinkInfoParsers`(사이트별 파서 3종과 `ParsedInfo`), `PerformancePreviewService`(속도 제한 → resolve → 키 판별 → 캐시 → 세마포어 → fetch → parse), `dto/response/PerformancePreviewResponse`. 설정은 `matjari.linkinfo.*`(application.yml). 컨트롤러는 `PerformanceController`의 `/preview`, 429 핸들러는 `GlobalExceptionHandler`.
- 테스트 신규 32건: 파서 10, PageFetcher 9, 서비스 8, 컨트롤러 슬라이스 5. 외부 인터넷 접속 테스트는 없다(파서는 실측 구조를 줄인 fixture로 검증).
- 알려진 한계: 사이트 HTML이 바뀌면 NO_INFO가 된다(어댑터 취약성). 속도 제한·캐시·세마포어는 단일 인스턴스 메모리다. 티켓링크·멜론 상품 페이지는 공연장이 비어 있을 수 있다.

## 공연 포스터 표시 (2026-10-13, `feature/poster`, DB 변경 없음)

링크 공개 페이지의 포스터 이미지 **주소 문자열만** 읽어 돌려주고, 이미지는 브라우저가 원 사이트에서 직접 불러온다(인라인 링크). 서버는 이미지를 요청·저장·프록시하지 않으므로 이미지에 대한 SSRF 면이 없다. DB에 저장하지 않는다. 설계 근거와 한계는 [공연 포스터 표시 설계 근거](../../wiki/decisions/poster-display-design.md).

### API

| Method | Path | 설명 |
|---|---|---|
| GET | /api/performances/preview?sourceUrl= | 응답에 `posterUrl`(문자열 또는 null) 추가. 나머지 필드와 규칙은 '링크 공연정보 미리보기' 절과 같다 |
| GET | /api/performances/{id}/poster | 로그인 필요. `{posterUrl}`(문자열 또는 null). 공연이 없으면 404. 공연의 `sourceUrl`로 미리보기와 같은 읽기 경로를 쓴다 |

- 포스터는 부가 정보다. 읽기 실패, 사이트 제한, 스위치 꺼짐은 오류 없이 `posterUrl: null`이다.

### 허용 호스트와 주소 검증 (`LinkInfoParsers.posterUrl`)

- 사이트별 고정 허용 목록(2026-10-13 실측): YES24 `tkfile.yes24.com`, 티켓링크 `image.toast.com`, 멜론 `cdnticket.melon.co.kr`. 다른 사이트의 허용 호스트도 거부한다.
- 읽는 순서: `og:image`, 없으면 JSON-LD `Event.image`의 첫 값.
- 주소 규칙: https만 인정한다(`//`로 시작하면 https로 보정). userinfo·비표준 포트·공백·제어 문자·비ASCII 문자는 거부하고, 2048자를 넘으면 거부한다. 경로에 `noimage`가 든 자리표시 이미지는 거부한다(YES24는 포스터가 없을 때 `_noimage.gif`를 og:image로 준다).

### 속도 제한과 스위치 (`PerformancePreviewService.poster`)

- 상세용 조회는 미리보기와 같은 캐시(10분, 키 `site:id`)·사이트 분당 30회·동시 실행 4개를 쓴다. 사용자 제한은 별도로 분당 `matjari.poster.user-per-minute`(기본 60)다. 제한에 걸리면 429가 아니라 조용히 `null`이다.
- 설정 `matjari.poster.enabled`(기본 true, 환경변수 `MATJARI_POSTER_ENABLED`)가 false면 포스터 요청과 주소 응답을 전혀 하지 않는다(미리보기의 `posterUrl`도 null). 사이트 쪽에서 중단 요청이 오면 즉시 끄는 스위치다.

| 키 | 기본값 | 설명 |
|---|---|---|
| `matjari.poster.enabled` | true | 포스터 기능 전체 스위치. false면 포스터 요청·주소를 전혀 내지 않는다 |
| `matjari.poster.user-per-minute` | 60 | 사용자별 분당 포스터 조회 상한(넘으면 조용히 null) |

### 테스트

- 단위·슬라이스: 파서(허용 호스트 허용·거부, JSON-LD 폴백), 서비스 `poster()`(스위치가 꺼져 있으면 이미지 주소 요청 0회), 컨트롤러 JSON 형태.
- 외부 인터넷 접속 테스트는 없다. 실제 페이지의 포스터 로드는 브라우저(Playwright)로 확인했다.

### 알려진 한계

1. 인라인(임베디드) 링크의 저작권 판단에 확정 판례가 없고, 포스터의 저작물성과 티켓 3사 이용약관은 확인하지 못했다. 공개 운영 전 약관 확인과 전문가 검토가 필요하고(요구사항 문서 §7 Q-26), 요청이 오면 `matjari.poster.enabled=false`로 끈다.
2. 허용 호스트는 2026-10-13 실측 기준이다. 사이트가 CDN을 바꾸면 포스터가 안 뜬다(실패 시 영역을 숨기므로 화면은 깨지지 않는다).
3. YES24는 포스터가 없는 공연이 많다. 인터파크는 robots.txt 때문에 지원하지 않는다.
4. 사이트가 핫링크를 막으면 포스터는 보이지 않는다. 대체 링크는 두지 않는다.
5. 포스터 삭제·표시 중단 요청은 개인정보처리방침의 문의처로 받는다. 방침에는 아직 관련 문구가 없다.

## 티켓 API

모두 로그인이 필요하고 본인 티켓만 다룬다.

| Method | Path | 설명 |
|---|---|---|
| POST | /api/tickets | 티켓 등록 (body `{sessionId, zone, row, col}`, 201). 같은 좌석의 활성 티켓이 있으면 409 `{message, code: SEAT_ALREADY_REGISTERED \| MY_TICKET_ALREADY_REGISTERED}`(보유자 정보 없음), 활성 티켓 상한 초과 422 `{code: TICKET_LIMIT_REACHED, message}`, 지난 회차·없는 회차·좌석 입력 오류 400(필드 키 `sessionId`/`zone`/`row`/`col`) |
| GET | /api/tickets/me | 내 활성 티켓 (회차 시각 오름차순, 공연 제목·공연장 이름·회차 시각 포함) |
| DELETE | /api/tickets/{id} | 티켓 내리기(소프트 삭제 → INACTIVE, 204). 이미 내린 티켓도 204, 본인 티켓이 아니면 404. 그 티켓의 교환 요청은 CLOSED로 바뀐다. EXCHANGED(교환 완료된 기존 티켓)는 409 `TICKET_EXCHANGED`로 내릴 수 없다 |

오류 형식은 API마다 다르다 (프론트는 상태 코드와 키로 구분한다).

| 상황 | 상태 | 본문 |
|---|---|---|
| 입력 검증(@Valid)·좌석 입력 오류·없는/지난 회차 | 400 | `{필드: 메시지}` — 좌석 입력 오류는 zone/row/col 오류를 한 번에 모아서 반환, `sessionId` 오류(없는 회차·마감)는 별도 |
| 본문 누락·JSON/타입 불일치(`/api/tickets/abc` 포함) | 400 | `{message}` |
| 인증 없음·토큰 오류 | 401 | `{message}` |
| 남의 티켓 내리기·없는 티켓 | 404 | `{message}` (존재 여부 비노출) |
| 같은 좌석 중복 | 409 | `{message, code}` |
| 활성 티켓 상한 | 422 | `{code, message}` |
| 교환 완료된 티켓 내리기(`DELETE /api/tickets/{id}`) | 409 | `{message, code: TICKET_EXCHANGED}` (이미 내린 INACTIVE 티켓은 기존대로 멱등 204) |

(회차 마감 지난 등록은 현행대로 400 `sessionId`이며 422 `SESSION_CLOSED`로 바꾸지 않았다. 프론트 작업 때 재검토.)

좌석 정규화(비교 키): NFKC, 공백 제거, 영문 대문자. 제어·서식·제로폭·사설·미할당 문자(`\p{C}`)와 변이 선택자는 제거하지 않고 `<구역|열|번>에 사용할 수 없는 문자가 있습니다.`로 거부한다(label·key 공통). 숫자(아랍-인도 숫자 등 Nd)는 ASCII로 바꾸며 `03A`처럼 섞인 값은 그대로 허용한다(앞 0 제거는 순수 숫자만). 열은 끝의 '열', 번은 끝의 '번' 제거, 숫자는 앞 0 제거(`03` → `3`). 숫자는 1 이상 상한 이하(기본 999), 문자(`A`, `가`)도 허용한다. 구역 접미사('구역', '층')는 지우지 않는다. 표시용 원문은 공백만 정리해 `*_label`에 저장한다.
설정(`application.yml` `ticket.*`, 환경변수): `TICKET_MAX_ACTIVE_PER_USER`(20), `TICKET_MAX_ROW_NUMBER`(999), `TICKET_MAX_COL_NUMBER`(999).

## 교환 희망 조건 API

모두 로그인이 필요하고 본인 티켓·요청만 다룬다. **남의 티켓/요청은 403, 없는 id는 404**다(티켓 API는 존재 비노출 404였으나 이쪽은 403).

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/requests | 희망 조건 등록 (201). 내 ACTIVE 티켓만, 티켓당 미삭제 요청 1개 (삭제한 뒤에는 같은 티켓에 새로 등록 가능) |
| GET | /api/exchange/requests/me | 내 요청 목록 (id 오름차순, **삭제된 요청 제외**). `?ticketId=`로 티켓별. 펼친 좌석 목록은 담지 않고 `wantSeatCount`(겹침 제거 후 개수)만 |
| PATCH | /api/exchange/requests/{id} | 범위(추가금 포함)·희망 회차 전체 교체 (200). 펼친 좌석을 전부 지우고 같은 트랜잭션에서 다시 만든다. 이 요청의 CHATTING 매칭은 시스템 취소, RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`, 삭제된 요청은 409 `REQUEST_DELETED` |
| DELETE | /api/exchange/requests/{id} | **소프트 삭제** (204): status `DELETED` + `deleted_at`, 범위·좌석·회차 행은 같은 트랜잭션에서 삭제. 이미 삭제됐으면 멱등 204. CHATTING 매칭은 시스템 취소(`canceledBy: SYSTEM`), RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`, 교환 완료(COMPLETED) 매칭이 있어도 삭제 가능(매칭 행·추가금 스냅샷은 남는다) |

요청 본문 (POST는 `ticketId` 추가, PATCH는 `ticketId` 없음):

```json
{"ticketId": 500,
 "wantSessions": [{"sessionId": 8, "priority": 1}, {"sessionId": 7, "priority": 2}],
 "ranges": [{"zone": "1층 A", "rowFrom": "3", "rowTo": "4", "colFrom": "3", "colTo": "5", "extraType": "NEG", "extraAmount": -10000},
            {"zone": "1층 A", "rowFrom": "5", "rowTo": "5", "colFrom": "1", "colTo": "2", "extraType": "ANY"}]}
```

- **추가금은 희망 범위 단위다(V6)**: 범위마다 `extraType`(필수)과 `extraAmount`가 있고 요청 단위 추가금은 없다. `extraType`은 `X`(추가금 X) / `ANY`(상관없음) / `POS`(받아야만 교환, 금액 > 0) / `NEG`(낼 의향, 금액 < 0). X/ANY는 `extraAmount`를 보내면 400, POS는 0보다 큰 값, NEG는 0보다 작은 값(오류 키 `ranges[i].extraType`/`ranges[i].extraAmount`). 판정은 유형만 보며 불성립은 POS–POS·POS–X·X–POS뿐(X–X·NEG–NEG 성립, 확정)이다. 금액은 매칭 계산에 쓰지 않는 참고 표시용이며 + 는 내가 받을 금액, − 는 내가 낼 수 있는 금액이다.
- 희망 회차 `wantSessions`는 최소 1개, 내 티켓과 같은 공연의 회차만(내 티켓의 회차도 가능), 중복 불가. `priority`는 1(가장 높음)~999이며 같은 값도 허용한다.
- 범위: 구역은 필수·범위 대상 아님. 숫자 열·번만 `from~to` 범위이고 문자 열·번은 `from == to`(하나씩 추가, 대소문자 무시). 시작 > 끝, 0 이하(`0`, `-1`), 숫자-문자 혼합, 문자 from ≠ to는 필드 오류 400. 정규화는 티켓과 같은 `SeatKeyNormalizer`(공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거, 숫자 상한 999)이며 오류 키에 범위 인덱스가 붙는다(`ranges[0].colTo`, `wantSessions[1].sessionId`).
- 범위끼리 겹치면 **합집합**이다(같은 좌석은 한 번만 저장). 단 **겹치는 좌석의 추가금(유형 또는 금액)이 다르면 422 `WANT_EXTRA_CONFLICT`**이고 `conflicts: [[i, j], ...]`(충돌하는 범위 인덱스 쌍, 최대 20쌍)를 담는다. 유형·금액이 완전히 같은 겹침은 허용한다. 좌석 한 행이 하나의 추가금만 가지므로(`exchange_want_seat` PK 불변) 후보 SQL의 조인 행 수는 늘지 않는다. 응답의 `ranges`는 입력한 범위를 그대로(추가금 `extraType/extraAmount` 포함) 돌려주되 zone은 표시용 원문, 열·번 from/to는 정규화 값이다(`03열` → `3`).
- **희망 회차가 내 티켓의 회차 하나뿐일 때만**, 내 티켓의 좌석(구역·열·번)이 펼친 희망 좌석에 포함되면 422 `WANT_INCLUDES_OWN_SEAT`. 같은 회차에서는 같은 좌석의 활성 티켓이 1개(`uk_ticket_active_seat`)이고 본인끼리는 매칭되지 않기 때문이다. 희망 회차에 다른 회차가 하나라도 있으면 내 좌석 위치가 범위에 들어 있어도 허용한다(다른 회차의 같은 자리 교환).
- 티켓 등록과 같은 마감 정책: 내 티켓의 회차가 마감(회차 당일 끝, 다음날 0시 KST)을 지났으면 등록·수정 모두 422 `SESSION_CLOSED`, 희망 회차가 이미 마감된 회차면 400 `wantSessions[i].sessionId`. 시계는 `SessionTimePolicy`(Clock 하나)만 쓴다.
- 열·번의 부호 붙은 정수형(`-3`, `+3`, 유니코드 마이너스 U+2212, 전각 부호)은 티켓 좌석과 희망 범위 모두 400(`<열|번>은 부호 없는 숫자(1 이상)로 입력해주세요.`)이다. 숫자 사이가 아닌 `A-3` 같은 값은 문자로 허용한다.
- 수정은 티켓이 INACTIVE이거나 요청이 CLOSED이면 422 `TICKET_NOT_ACTIVE`. 수정·삭제 전 열린 매칭 처리는 `ExchangeRequestService.cancelChattingOrRejectReserved` 한 곳에 있다: 이 요청의 열린 매칭 id를 오름차순으로 읽어 하나씩 `FOR UPDATE`로 잠근 뒤(OR 조건의 한 방 FOR UPDATE는 무관한 행까지 잠글 수 있어 쓰지 않음), RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`(아무것도 바꾸지 않음), 아니면 CHATTING을 모두 시스템 취소한다(`canceled_by` NULL). 모든 검증이 끝난 뒤에 실행하므로 검증 실패 시 채팅은 그대로다. 티켓 행은 잠그지 않는다.

### 교환 요청 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| 필드 검증·추가금 규칙·범위/회차 오류 | 400 | `{필드: 메시지}` — `ticketId`, `wantSessions`, `ranges`, `wantSessions[i].sessionId/priority`, `ranges[i].zone/rowFrom/rowTo/colFrom/colTo/extraType/extraAmount`. 추가금 오류와 범위 오류는 한 번에 모아서 반환 |
| 남의 티켓·남의 요청 | 403 | `{message}` |
| 없는 티켓·없는 요청 | 404 | `{message}` |
| 티켓에 이미 요청 있음 (동시 요청의 유일 제약 위반 포함) | 409 | `{message, code: REQUEST_ALREADY_EXISTS}` |
| 내린 티켓/CLOSED 요청 | 422 | `{code: TICKET_NOT_ACTIVE, message}` |
| 겹치는 범위의 추가금이 다름 | 422 | `{code: WANT_EXTRA_CONFLICT, message, conflicts: [[0,1], ...]}` |
| 예약(RESERVED)된 매칭이 있는 요청의 수정·삭제 | 409 | `{message, code: ACTIVE_MATCH_EXISTS}` |
| 삭제된 요청의 수정·후보 조회·내 요청으로 제안(상대 요청이 삭제된 제안은 422 `NOT_A_CANDIDATE`) | 409 | `{message, code: REQUEST_DELETED}` (삭제 API 자체는 멱등 204) |
| 내 좌석이 희망 좌석에 포함 | 422 | `{code: WANT_INCLUDES_OWN_SEAT, message}` |
| 펼친 희망 좌석 수 상한 초과 | 422 | `{code: WANT_SEAT_LIMIT_EXCEEDED, message, count, limit}` — count는 구역별 직사각형 합집합 크기(응답의 `wantSeatCount`와 같은 의미)이며 좌표 압축으로 펼치기 전에 계산해 거부 |
| 내 티켓의 회차가 마감됨 | 422 | `{code: SESSION_CLOSED, message}` |
| 범위 개수 상한 초과 | 422 | `{code: WANT_RANGE_LIMIT_EXCEEDED, message, count, limit}` |

서버 안전 상한 설정(`application.yml` `exchange.want.*`, 사용자 대상 상한이 아니라 DoS 방어용): `EXCHANGE_WANT_MAX_SEATS`(5000, 요청당 펼친 좌석), `EXCHANGE_WANT_MAX_RANGES`(50, 요청당 범위). 열·번 숫자 상한은 `ticket.max-row-number`/`max-col-number`(999)를 재사용한다. DTO의 `@Size`(회차 100, 범위 1000)는 비정상적으로 큰 본문을 거르는 거친 한도이며 초과 시 400이다.

동시성: 등록은 **티켓 행 `FOR UPDATE`**(트랜잭션의 첫 쿼리)로 직렬화하고 `uk_exchange_request_live_ticket` 위반도 같은 409로 바꾼다. 수정·삭제는 **요청 행 `FOR UPDATE`**(`PESSIMISTIC_WRITE`)로 직렬화한다. 티켓 내리기도 티켓 행을 잠가(잠금 순서 티켓 → 요청) 등록·수정과 엇갈리지 않는다. 락 대기 실패는 503 `BUSY`.

## 매칭 API (후보 선택 -> 채팅 -> 예약)

모두 로그인이 필요하다. 매칭은 조건 일치 판정으로 찾은 후보를 사용자가 골라 시작하며 점수화·랭킹·신뢰도는 쓰지 않는다. 흐름: 후보 선택 -> 채팅(CHATTING, 한 요청에 여러 개 동시 가능) -> **예약 제안**(채팅 화면, 제안 컬럼만 기록, V15) -> **상대가 예약 수락**(RESERVED, 두 티켓 잠금, **티켓당 예약 1개**, V8). 예약 거절·제안 철회는 CHATTING을 유지한다 -> 양도 후 양쪽 '교환 수락'(`complete`, 2026-10-11 구현) -> COMPLETED. 예약은 누구든(예약한 사람이 아니어도) 취소할 수 있고 취소하면 CHATTING으로 돌아가 같은 쌍이 다시 예약할 수 있다(횟수 제한·쿨다운 없음). 채팅 종료(cancel)·거절(reject)은 CHATTING에서만 가능하고 **RESERVED에서는 409 `MATCH_STATE_CONFLICT`('먼저 예약을 취소해주세요.')**다. 예약된 티켓의 새 제안·새 예약만 막히고 이미 열려 있던 다른 채팅은 유지된다. 재매칭 불가는 차단·신고뿐이며 둘 다 V13에서 구현됐다.

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/requests/{id}/proposals | 후보를 골라 매칭 생성 (201, 본문 `{"targetRequestId": 800}`). `{id}`는 내 요청. 응답은 아래 매칭 응답 |
| POST | /api/exchange/matches/{id}/reserve | **예약 제안** (200, 상태는 CHATTING 그대로, 응답 `reservationProposal`=MINE). CHATTING이고 제안이 없을 때만. 내가 이미 제안했으면 멱등 200. 상대가 이미 제안했으면 409 `RESERVATION_PROPOSAL_PENDING`. RESERVED면 409 `MATCH_STATE_CONFLICT`. 두 티켓 중 하나라도 다른 매칭에서 잠겼으면 409 `TICKET_ALREADY_RESERVED`(제안 시점에 미리 거른다), EXCHANGED면 409 `TICKET_EXCHANGED`. 제안은 티켓을 잠그지 않고 매칭 행만 `FOR UPDATE`로 잠근다. 회차 마감 검사는 하지 않는다 |
| POST | /api/exchange/matches/{id}/reserve/accept | **예약 수락** (200, 제안받은 쪽만). 제안이 없으면 409 `RESERVATION_PROPOSAL_NOT_FOUND`, 제안자가 누르면 409 `RESERVATION_PROPOSAL_OWN`. 기존 예약 로직을 그대로 쓴다(잠금 순서 티켓 id↑ → 요청 id↑ → 매칭, 잠근 뒤 티켓 ACTIVE·요청 OPEN 재확인, `exchange_ticket_lock` 2행). `reserved_by_id`는 **제안자**, `reserved_at`은 수락 시각이고 제안 컬럼은 비운다. RESERVED에서 다시 누르면 양쪽 모두 멱등 200. 다른 매칭에서 잠긴 좌석이면 409 `TICKET_ALREADY_RESERVED`. 다른 채팅은 자동 취소하지 않는다 |
| POST | /api/exchange/matches/{id}/reserve/decline | **예약 제안 거절** (200, 제안받은 쪽만). 제안을 지우고 CHATTING 유지, `RESERVATION_REJECTED`를 제안자에게 보낸다. 제안이 없으면 멱등 200. 내가 낸 제안을 내가 거절하면 409 `RESERVATION_PROPOSAL_OWN`. 닫힌 매칭은 409 `MATCH_STATE_CONFLICT` |
| POST | /api/exchange/matches/{id}/reserve/withdraw | **제안 철회** (200, 제안자만). 제안을 지우고 CHATTING 유지. 알림은 없다. 제안이 없으면 멱등 200. 상대가 낸 제안을 철회하면 409 `RESERVATION_PROPOSAL_NOT_OWN`. 닫힌 매칭은 409 `MATCH_STATE_CONFLICT` |
| POST | /api/exchange/matches/{id}/unreserve | 예약 취소 (200). 두 참여자 누구나(예약자가 아니어도) RESERVED -> CHATTING, `reserved_*`와 교환 수락 표시(`a/b_completed_at`) 초기화, 잠금 2행 삭제. CHATTING이면 멱등 200. COMPLETED/CANCELED는 409 |
| POST | /api/exchange/matches/{id}/complete | 교환 수락 (200, 응답은 매칭 응답). RESERVED에서만, 참여자 누구나 누른다(호출자 쪽 `a/b_completed_at` 기록). 첫 수락은 RESERVED를 유지하고, **두 번째 수락이 한 트랜잭션으로 교환 완료**한다: 잠금 순서 티켓 id↑ → 요청 id↑ → 매칭, 기존 두 티켓을 `EXCHANGED`로 바꾸고 flush한 뒤 새 티켓 2개를 INSERT(소유자 유지, 회차·구역·열·번은 상대의 기존 티켓 값), `exchange_history` 2행, 두 기존 요청 CLOSED(`status = OPEN` 조건), `exchange_ticket_lock` 2행 삭제, 매칭 COMPLETED(`reserved_*` NULL). 내가 이미 수락했으면 멱등 200. CHATTING이면 409 `MATCH_STATE_CONFLICT`(message `예약한 뒤에 교환 수락할 수 있어요.`), CANCELED·COMPLETED도 409. 마감 검사·활성 20개 상한 검사는 없다. 같은 티켓의 다른 CHATTING 매칭은 취소하지 않는다(Q-15) |
| POST | /api/exchange/matches/{id}/reject | 제안받은 쪽(b)의 거절 (200, 결과 CANCELED). 제안한 쪽이 부르면 403. **RESERVED에서는 409**(먼저 예약 취소) |
| POST | /api/exchange/matches/{id}/cancel | 참여자 누구나 채팅 종료 (200, 결과 CANCELED). **RESERVED에서는 409 `MATCH_STATE_CONFLICT`**(message `먼저 예약을 취소해주세요.`), 먼저 `unreserve` |
| GET | /api/exchange/matches/me | 내 매칭 목록(읽기 전용). `role=SENT\|RECEIVED\|ALL`(기본 ALL; 보낸=내가 제안자 a측, 받은=b측), `status=CHATTING\|RESERVED\|COMPLETED\|CANCELED`(선택, 반복 또는 쉼표로 여러 개), `page`(0부터), `size`(기본 20, 1 미만은 400 필드 오류, 100 초과는 100으로 보정). `updated_at` 내림차순(동률 id 내림차순). 잘못된 role·status·page는 400 필드 오류. 응답은 PageResponse |
| GET | /api/exchange/matches/{id} | 내 매칭 단건(목록 항목과 같은 모양). 비참여자·없는 매칭은 똑같이 404 `매칭을 찾을 수 없습니다.` |

호출 예시 (`T`는 로그인 토큰):

```bash
curl -s -X POST localhost:8080/api/exchange/requests/721/proposals -H "Authorization: Bearer $T" \
     -H 'Content-Type: application/json' -d '{"targetRequestId":722}'      # 201 {"id":401,"status":"CHATTING",...}
curl -s -X POST localhost:8080/api/exchange/matches/401/reserve -H "Authorization: Bearer $T"     # 예약 제안 (상대가 reserve/accept로 수락해야 RESERVED)
curl -s -X POST localhost:8080/api/exchange/matches/401/reserve/accept -H "Authorization: Bearer $T"   # 예약 수락 (제안받은 쪽, RESERVED)
curl -s -X POST localhost:8080/api/exchange/matches/401/unreserve -H "Authorization: Bearer $T"   # 예약 취소 (CHATTING 복귀)
curl -s -X POST localhost:8080/api/exchange/matches/401/cancel -H "Authorization: Bearer $T"      # 채팅 종료 (CHATTING에서만)
```

매칭 응답 (POST 응답·목록·단건이 모두 같은 모양, 호출자 기준): `id, status(CHATTING|RESERVED|COMPLETED|CANCELED), mySide(A=제안자|B), role(SENT=내가 a측|RECEIVED=b측), myRequestId, myTicketId, mySeat{zone,row,col,sessionId,startsAt}, counterpartRequestId, counterpartTicketId, counterpartSeat{...}, counterpartNickname, myExtraType, myExtraAmount, counterpartExtraType, counterpartExtraAmount, myRequestDeleted, counterpartRequestDeleted, reservationProposal(V15, CHATTING의 예약 제안 여부 NONE|MINE|THEIRS, RESERVED·종료는 NONE), reservedBy(RESERVED일 때 예약을 수락해 확정된 쪽, 즉 예약 제안자 ME|COUNTERPART, 그 밖의 상태는 null), reservedAt(RESERVED일 때만 값), myAccepted, counterpartAccepted(교환 수락 표시 = 호출자 쪽 `a/b_completed_at`이 있는지, 예약 취소 때 초기화), myTicketExchanged, counterpartTicketExchanged(그 쪽 티켓이 EXCHANGED인지, 다른 매칭의 교환 완료로 닫힌 좌석), myTicketReservedElsewhere, counterpartTicketReservedElsewhere(그 쪽 티켓이 이 매칭이 아닌 다른 매칭의 예약 잠금인지, 카드의 '(다른 사용자와 예약 중인 좌석)' 문구용), canceledBy(ME|COUNTERPART|SYSTEM, CANCELED일 때만), canceledAt, createdAt, updatedAt`. 좌석의 zone·row·col은 사용자가 입력한 표시용 원문이고 `startsAt`은 `yyyy-MM-dd'T'HH:mm`. 상대의 이메일 등 개인정보는 내려가지 않고 닉네임만 있다. 추가금 유형은 X/ANY/POS/NEG이며 금액은 참고용이다. **추가금은 매칭을 만들 때 저장한 스냅샷**(`exchange_match.a/b_extra_*`: 내 범위 중 상대 좌석을 포함한 범위의 값, 상대 범위 중 내 좌석을 포함한 범위의 값)이라 이후 요청을 수정·삭제해도 바뀌지 않는다. `myRequestDeleted/counterpartRequestDeleted`는 그 쪽 요청이 삭제(DELETED)됐는지다(매칭 기록은 남는다). (V8에서 `myReservedAt`/`counterpartReservedAt`를 제거하고 `reservedBy`/`reservedAt`/`myAccepted`/`counterpartAccepted`로 바꿨다. 나머지 필드는 그대로다.)

```json
{"id":401,"status":"RESERVED","mySide":"B","role":"RECEIVED","myRequestId":721,"myTicketId":611,
 "mySeat":{"zone":"A구역","row":"1","col":"1","sessionId":7,"startsAt":"2026-11-01T19:00"},
 "counterpartRequestId":722,"counterpartTicketId":612,
 "counterpartSeat":{"zone":"B구역","row":"2","col":"3","sessionId":8,"startsAt":"2026-11-02T19:00"},
 "counterpartNickname":"상대3","myExtraType":"X","myExtraAmount":null,"counterpartExtraType":"NEG","counterpartExtraAmount":-10000,
 "myRequestDeleted":false,"counterpartRequestDeleted":false,
 "reservationProposal":"NONE","reservedBy":"COUNTERPART","reservedAt":"2026-10-08T11:30:00","myAccepted":false,"counterpartAccepted":false,"myTicketExchanged":false,"counterpartTicketExchanged":false,"myTicketReservedElsewhere":false,"counterpartTicketReservedElsewhere":false,"canceledBy":null,"canceledAt":null,
 "createdAt":"2026-10-08T11:00:00","updatedAt":"2026-10-08T12:00:05"}
```

**내 매칭 조회 구현 메모.** `ExchangeMatchQueryRepository`(JdbcTemplate, `STRAIGHT_JOIN`)가 매칭 1건당 한 번의 조인으로 양쪽 티켓 좌석·회차·추가금 스냅샷·요청 삭제 여부·닉네임을 읽는다(목록은 COUNT 1회 + 목록 1회, 단건 1회로 행 수와 무관). 읽기 전용 트랜잭션·잠금 없음. POST 응답(제안·수락·거절·취소)도 같은 조인으로 만들며 쓰기 트랜잭션 안에서 읽어 방금 쓴 상태를 그대로 돌려준다. **인덱스는 새로 만들지 않았다.** EXPLAIN(매칭 5,400행): SENT=`idx_exchange_match_user_a` ref, RECEIVED=`idx_exchange_match_user_b` ref, ALL=`index_merge` union(user_a, user_b), 나머지 8개 조인은 모두 PK `eq_ref`. 정렬은 사용자당 소수의 행에 대한 filesort라 전용 인덱스는 필요 없다. 사용자당 매칭이 수천 건이 되면 후속 마이그레이션에서 `(user_a_id, updated_at)`/`(user_b_id, updated_at)` 인덱스 또는 id 선조회 후 조인을 검토한다(주의: FK 인덱스에 갱신 컬럼을 넣지 말 것 규칙과 충돌하므로 FK용 단일 인덱스는 유지하고 별도로 추가). 목록은 INNER JOIN 8개, COUNT는 `exchange_match`만 세며 FK 때문에 고아 행이 없다는 전제다. **users 익명화·티켓 삭제를 도입하면 LEFT JOIN 또는 COUNT에도 같은 조인을 쓴다.** 참고: 매칭 행의 좌석은 항상 교환 전 자리다. 교환 후 자리는 새 티켓·교환 이력(V9)이 담당한다. V8 이후 SELECT는 `m.reserved_by_id, m.reserved_at, m.a_completed_at, m.b_completed_at`를 읽을 뿐 조인·인덱스·정렬은 그대로다.

**제안 시 재검증(쌍 단위, 후보 SQL과 같은 판정)**: 같은 공연, 상대 티켓의 회차 ∈ 내 희망 회차·내 티켓의 회차 ∈ 상대 희망 회차, 상대 좌석 ∈ 내 희망 좌석·내 좌석 ∈ 상대 희망 좌석, 추가금 유형 호환, 양쪽 요청 OPEN·티켓 ACTIVE, 상대 회차 마감 전, 다른 사용자, 양쪽 티켓 예약 잠금 없음. 후보 화면이 오래돼 조건이 바뀌었으면 422.

**허용/불허 상태 전이 표** (`ExchangeMatch.isAllowed`, `ExchangeMatchService` Javadoc과 같은 표. 불허는 모두 409 `MATCH_STATE_CONFLICT`):

| 상태 \ 동작 | propose | reserve(제안) | reserve/accept | reserve/decline·withdraw | unreserve | reject(b측) | cancel | complete |
|---|---|---|---|---|---|---|---|---|
| CHATTING | 409(같은 쌍 열린 매칭) | 허용(제안 기록, 상대 제안이 있으면 409) | 제안받은 쪽만 허용(RESERVED로 전이) | 허용(제안 지움, 없으면 멱등 200) | 멱등 200 | 허용 | 허용 | 불허(예약 전, 409 `MATCH_STATE_CONFLICT`) |
| RESERVED | 409(같은 쌍 열린 매칭) | 멱등 200 | 양쪽 모두 멱등 200 | 멱등 200(제안 없음) | 허용(CHATTING 복귀, 잠금 해제) | 409 (먼저 예약 취소) | 409 (먼저 예약 취소) | 첫 수락은 RESERVED 유지, 두 번째 수락에서 COMPLETED(V9) |
| COMPLETED | 허용(새 매칭) | 409 | 409 | 409 | 409 | 409 | 409 | 409 |
| CANCELED | 허용(새 매칭) | 409 | 409 | 409 | 409 | 409 | 409 | 409 |

propose의 상태는 '같은 요청 쌍의 가장 최근 매칭' 기준이다. 열린 매칭(CHATTING·RESERVED)이 없으면 취소·완료된 뒤에도 같은 쌍으로 다시 만들 수 있다.

### 매칭 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| `targetRequestId` 누락 | 400 | `{targetRequestId: 메시지}` |
| 남의 요청으로 제안 / 제안한 쪽의 reject | 403 | `{message}` |
| 없는 요청·없는 매칭, **매칭 참여자가 아닌 사용자**(없는 매칭과 같은 404, 존재 은닉) | 404 | `{message}` |
| 같은 쌍의 열린 매칭이 이미 있음 (반대 방향 제안 포함) | 409 | `{message, code: MATCH_ALREADY_OPEN, matchId}` |
| 상태 전이 불허 (이미 CANCELED/COMPLETED 등, **RESERVED에서 cancel/reject**: message `먼저 예약을 취소해주세요.`) | 409 | `{message, code: MATCH_STATE_CONFLICT, status, action}` |
| 예약 제안 충돌 (`reserve`·`reserve/accept`·`reserve/decline`·`reserve/withdraw`) | 409 | `{message, code}`: `RESERVATION_PROPOSAL_PENDING`(상대 제안 대기), `RESERVATION_PROPOSAL_NOT_FOUND`(수락할 제안 없음), `RESERVATION_PROPOSAL_OWN`(제안자가 수락·거절), `RESERVATION_PROPOSAL_NOT_OWN`(상대 제안 철회) |
| 열린 매칭이 있는 요청 수정·삭제 (`PATCH/DELETE /api/exchange/requests/{id}`) | 409 | `{message, code: ACTIVE_MATCH_EXISTS}` |
| (제거됨) 완료된 매칭 기록이 있는 요청 삭제 — 소프트 삭제라 이제 허용되며 `MATCH_HISTORY_EXISTS`는 쓰지 않는다 | - | - |
| 내 요청이 닫힘/티켓 내림 | 422 | `{code: TICKET_NOT_ACTIVE, message}` |
| 내 회차 마감 (propose만; 이미 시작한 채팅의 reserve/unreserve에는 적용하지 않음) | 422 | `{code: SESSION_CLOSED, message}` |
| 후보 조건 불충족 (자기 자신·같은 사용자·상대 요청 닫힘·좌석/회차/추가금 불일치) **또는 상대 티켓이 예약 잠금**(후보에서 빠진 것과 같게 취급) | 422 | `{code: NOT_A_CANDIDATE, message}` |

**예약 잠금(같은 원인)에서 나오는 코드 묶음** — 한 티켓은 동시에 한 매칭에서만 예약되며, 상황별로 코드가 다르다.

| 코드 | 상태 | 언제 |
|---|---|---|
| `TICKET_LOCKED` | 422 | **내** 티켓이 예약 잠금인데 제안하거나 후보를 조회할 때. 상대 티켓이 잠긴 경우에는 쓰지 않는다(`NOT_A_CANDIDATE`로 합쳐 상대의 예약 상태를 노출하지 않는다) |
| `TICKET_ALREADY_RESERVED` | 409 | 매칭 참여자가 예약 제안(reserve)이나 수락(reserve/accept)을 하는데 이 매칭의 티켓이 이미 다른 매칭에서 예약됨 (참여자 사이의 화면이라 노출해도 되는 정보) |
| `TICKET_RESERVED` | 409 | 예약 잠금이 걸린 티켓을 내리려 할 때 (`DELETE /api/tickets/{id}`) |
| `TICKET_EXCHANGED` | 409 | 교환 완료로 `EXCHANGED`가 된 티켓을 예약하려 할 때(reserve에서 일반 검사보다 먼저 확인), 또는 내리려 할 때(`DELETE /api/tickets/{id}`) |

**제안 검증 순서**: 남의 요청 403 -> 없는 요청 404 -> 같은 쌍 열린 매칭 409 -> 내 요청 닫힘/내린 티켓 422 `TICKET_NOT_ACTIVE` -> 내 회차 마감 422 -> **후보 판정**(불충족·상대 티켓 잠금 포함) 422 `NOT_A_CANDIDATE` -> 내 티켓 잠금 422 `TICKET_LOCKED`. 후보가 아닌 상대의 티켓 예약 상태는 어떤 응답으로도 드러나지 않는다.

### 연동 규칙

- **티켓 내리기**: EXCHANGED(교환 완료된 기존 티켓)면 409 `TICKET_EXCHANGED`(V9), 예약 잠금이 있으면 409 `TICKET_RESERVED`. 없으면 기존대로 그 티켓의 요청을 CLOSED로 닫고, 그 티켓이 참여한 **CHATTING 매칭은 시스템 취소**(`canceled_by_id` NULL, 응답 `canceledBy: SYSTEM`)한다.
- **요청 수정·삭제**: 그 요청이 a측이든 b측이든 RESERVED 매칭이 있으면 409 `ACTIVE_MATCH_EXISTS`(아무것도 바꾸지 않음), 없으면 CHATTING 매칭을 모두 시스템 취소한 뒤 수정·삭제한다. 삭제는 소프트 삭제라 매칭 행은 지우지 않고, COMPLETED 매칭이 있어도 삭제는 허용된다.
- **후보 조회**: 같은 요청 쌍의 열린 매칭이 있는 상대와 예약 잠금 티켓은 후보에서 빠진다. 취소·완료된 매칭은 보지 않는다(재매칭 허용). 내 티켓이 잠겨 있으면 조회 자체가 422다. 차단 양방향 제외와 정지 사용자 티켓 제외는 V13에서 구현됐다(아래 '차단·신고·이용 정지' 절).

- **예약 제안이 걸린 매칭**: cancel·reject·요청 수정·삭제·티켓 내리기·차단·정지의 시스템 취소는 제안이 걸려 있어도 그대로 동작하고, 같은 문장에서 `reserve_proposed_*`를 NULL로 비운다(V15 CHECK: CHATTING이 아니면 제안 컬럼은 NULL).

### 동시성 (요청 행 잠금 기반)

- 잠금 순서는 **항상 티켓(id 오름차순) -> 요청(id 오름차순) -> 매칭**이다. `users` 행은 티켓 이후에 잡는다(`TicketService.create`가 users 행을 먼저 잡고 티켓은 INSERT만 하므로 지금은 순환이 없다). **새 코드에서 users 행을 X 잠금한 뒤 티켓·요청을 잠그지 않는다**(`TicketService.deactivate`, `ExchangeRequestService`와 같은 규약). 요청·매칭의 불변 컬럼(티켓 id, 사용자 id)을 트랜잭션 밖에서 먼저 읽어 잠금 대상을 정하고, 잠금 읽기가 트랜잭션의 첫 쿼리들이 되게 한다(MySQL REPEATABLE READ 스냅샷이 잠금 뒤에 시작해야 최신 상태를 본다).
- 예약 제안 경합(V15): 양쪽 동시 제안은 매칭 행 잠금으로 직렬화되어 하나만 성공하고 나머지는 409 `RESERVATION_PROPOSAL_PENDING`. 제안 vs 철회, 수락 vs 철회·거절, 수락 vs 다른 매칭의 같은 티켓 수락, 수락 vs 취소·차단·정지·티켓 내리기·요청 삭제, 예약 취소 vs 재제안은 `ReserveProposalMysqlTest`에서 교착 0과 불변식(잠금 행 = 2×RESERVED, 제안은 CHATTING에만)을 확인했다.
- 같은 쌍 동시 제안은 하나만 201이고 나머지는 열린 매칭을 보고 409(최종 방어선은 `uk_exchange_match_open_pair`). 같은 티켓을 건 두 매칭이 동시에 예약하려 하면 하나만 RESERVED이고 나머지는 409(최종 방어선은 `exchange_ticket_lock` PK). 같은 매칭을 두 사람이 동시에 reserve하면 티켓 행 잠금에서 직렬화되어 한 번만 처리되고 나중 쪽은 멱등 200이다. 불변식: `exchange_ticket_lock` 행이 있는 매칭 <=> RESERVED이고 정확히 2행, RESERVED <=> `reserved_by_id`·`reserved_at` NOT NULL(V8 CHECK `ck_exchange_match_reserved`). RESERVED를 벗어나는 모든 UPDATE는 같은 문장에서 `reserved_*`를 NULL로 만든다(unreserve는 `a/b_completed_at`도 함께). 시스템 취소 경로(요청 수정·삭제, 티켓 내리기)는 CHATTING만 취소하므로 `reserved_*`가 이미 NULL이다. `reserved_by_id` 갱신(NULL -> 값, reserve)은 FK 검사로 users 행에 S 잠금을 걸지만 잠금 순서 맨 끝이고 users 행을 X로 잡고 매칭을 기다리는 곳이 없어 순환이 없다. 앞으로 차단(`user_block`) 서비스가 users 행을 X로 잡은 뒤 티켓·요청·매칭을 잠그면 안 된다.
- **교착 사례(해결됨)**: FK가 쓰는 인덱스를 `(ticket_a_id, status)` 같은 복합 인덱스로 만들면 `status`만 바꾸는 UPDATE(티켓 내림으로 인한 시스템 취소)도 InnoDB가 FK 인덱스 변경으로 보고 부모 `ticket` 행에 S 잠금을 걸어, 잠금 순서 밖에서 다른 티켓을 잡다가 양쪽 수락과 교착이 났다(실제 MySQL 경쟁 테스트에서 재현). 그래서 V5의 FK 인덱스는 모두 단일 컬럼이다. 이후 `exchange_match`에 인덱스를 더할 때 FK 컬럼에 `status` 같은 갱신 컬럼을 붙이지 않는다.
- 락 대기 실패·교착은 503 `BUSY`로 응답한다.

### COMPLETED 시점의 티켓 처리 규칙 (구현 완료 2026-10-11, V9)

**구현 완료(2026-10-11, V9), 확정 설계: 티켓 처리는 COMPLETED 시점(양쪽이 '교환 수락'을 누르는 순간)에 한 트랜잭션에서 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT한다**(소유자는 그대로, 회차·구역·열·번은 상대의 기존 티켓 값). 두 기존 티켓을 먼저 `ACTIVE -> EXCHANGED`로 바꿔 `active_flag`를 NULL로 만들어 `uk_ticket_active_seat`에서 빼고 나서 새 티켓을 INSERT하므로 유일 제약 위반이 없다. 그 전에는 각자 수락 시각(`a/b_completed_at`)만 기록한다. 매칭 행의 좌석은 교환 전 자리이고 교환 후 자리는 새 티켓·교환 이력(`exchange_history`의 `old_ticket_id`/`new_ticket_id`)에서 본다. 교환 이력 스냅샷을 남기고, 기존 두 티켓의 교환 요청은 CLOSED로 닫는다(Q-15 확정). 같은 기존 티켓에 걸린 **다른 CHATTING 매칭은 자동 취소하지 않고** 그 매칭 카드에서 예약·교환 수락 버튼을 비활성화하며 "이미 교환된 좌석이에요"를 본인과 상대방 모두에게 보여준다('교환됨' 여부는 티켓 상태 EXCHANGED로 판단, 예약 잠금은 푼다). 교환 완료된 매칭은 취소할 수 없다. 잠금 순서는 티켓 -> 요청 -> 매칭이다. 한쪽만 수락하고 방치돼도 자동 완료·취소는 없고 7일 경과 알림만 보낸다. (폐기된 안: 두 티켓의 좌석·회차를 교체하는 갱신 방식)

## 매칭 후보 조회 API

로그인 필요, 본인 요청만. 조건 일치 판정으로 후보를 찾을 뿐 **점수·랭킹·신뢰도는 없다**(우선순위는 사용자가 정한 희망 회차 priority).

| Method | Path | 설명 |
|---|---|---|
| GET | /api/exchange/requests/{id}/candidates?page=0&size=20 | 내 요청과 서로 조건이 맞는 상대 요청 목록. size 기본 20, 최대 100(초과·0 이하는 보정), page 0부터(음수 400) |

| GET | /api/exchange/requests/{id}/candidates/scroll?cursor=&size=20 | **keyset(커서) 조각.** 같은 판정·정렬이지만 전체 개수와 OFFSET이 없다. 처음엔 cursor를 비우고 응답 `{content,size,hasNext,nextCursor}`의 nextCursor를 그대로 다음 요청에 넣는다(`hasNext=false`면 끝). 잘못된 cursor는 400 `{cursor}` 필드 오류. size 기본 20, 최대 100(보정) |

keyset 조각의 내부: 저장소에 `size+1`건을 요청해 다음 조각 유무를 알고, 마지막 행의 `(wantPriority, requestedAt, requestId)`를 URL-safe Base64 불투명 커서로 만든다. SQL은 목록 쿼리(`STRAIGHT_JOIN`·제외 조건 포함)에 `AND (wsa.priority > ? OR (wsa.priority = ? AND (b.created_at < ? OR (b.created_at = ? AND b.id < ?))))`만 더하고 `LIMIT ?`만 쓴다(`b.id`가 PK라 정렬 키가 유일해 겹침·누락이 없다). 조각 사이에 후보가 닫히거나 새로 생겨도 커서 뒤의 행만 읽으므로 중복 없이 이어지고, 커서보다 앞(더 최신)에 새로 생긴 후보는 처음부터 다시 읽을 때 보인다. 권한·상태 검증(403/404/409/422)은 `page` 방식과 같은 메서드를 쓴다. 테스트: `ExchangeCandidateQueryTest`(실제 MySQL) keyset 5건(offset 방식과 같은 순서, 우선순위·시각·id 동률, 조각 크기 1~20, 조각 사이 변경, 커서 해석), `ExchangeCandidateServiceTest` 4건, 컨트롤러 슬라이스 2건.

오류(403/404 구분은 같은 `/api/exchange/requests/{id}` 계열 API와 일관되게 유지한다. 티켓 API의 존재 비노출 404와는 다르다): 남의 요청 403 `{message}`, 없는 요청 404 `{message}`, 요청 CLOSED 또는 내 티켓 INACTIVE 422 `{code: TICKET_NOT_ACTIVE}`, 내 티켓 회차 마감 422 `{code: SESSION_CLOSED}`, 인증 없음 401.

판정(전부 만족해야 후보):
- 같은 공연이고, 상대 티켓의 회차 ∈ 내 희망 회차이고 내 티켓의 회차 ∈ 상대 희망 회차.
- 상대 티켓의 (구역, 열, 번)이 내 희망 좌석에 있고, 내 티켓의 (구역, 열, 번)이 상대 희망 좌석에 있다(`exchange_want_seat` PK 점조회).
- 추가금은 **범위(좌석) 단위로 유형만** 본다: 내 희망 좌석 중 상대 티켓 좌석 행의 유형(wa)과 상대 희망 좌석 중 내 티켓 좌석 행의 유형(wb)을 비교한다. 불성립은 POS-POS, POS-X(양방향)뿐이고 나머지(X-X, NEG-NEG 포함)는 성립이다(2026-10-08 확정). 금액은 판정에 쓰지 않는다.
- 삭제된(DELETED) 요청은 후보가 되지 않는다. 상대 요청은 `b.live_flag = 1 AND b.status = 'OPEN'`으로 조인한다(`uk_exchange_request_live_ticket` 점조회).
- 상대 티켓 ACTIVE, 상대 요청 OPEN, 상대 사용자는 나와 달라야 함(내 요청은 자동 제외), 상대 회차가 마감 전(회차 당일 끝=`starts_at` 다음날 0시 KST, 쿼리에서는 `starts_at >= 오늘 0시`로 같은 뜻).
- (V5) 같은 요청 쌍의 열린 매칭(CHATTING·RESERVED)이 있는 상대와 예약 잠금 티켓은 제외한다. 취소·완료된 매칭은 보지 않는다. 내 티켓이 예약 잠금이면 조회 자체가 422 `TICKET_LOCKED`. 차단 양방향 제외와 정지 사용자 티켓 제외는 V13에서 구현됐다.
- 정렬: 내 희망 회차 priority 오름차순 -> 상대 요청 등록 최신순 -> 요청 id 내림차순. (같은 회차 우선 같은 별도 규칙 없음) 의도는 '사용자가 정한 회차 우선순위 안에서 최신 요청 우선'이다.

응답 예시(`PageResponse`: content, page, size, totalElements, totalPages):

```json
{"content":[{"requestId":885,"ticketId":885,"zone":"1F","row":"10","col":"21","sessionId":1,
  "startsAt":"2026-11-07T19:00","nickname":"nick885","wantPriority":1,
  "extraType":"NEG","extraAmount":-8000,"myExtraType":"POS","myExtraAmount":5000,
  "settlementHint":{"min":5000,"max":8000},"requestedAt":"2026-10-07T06:59:22"}],
 "page":0,"size":20,"totalElements":11,"totalPages":1}
```

- `zone/row/col`은 상대가 입력한 표시용 원문, `wantPriority`는 내 희망 회차 중 상대 티켓 회차의 우선순위, `myExtraType/myExtraAmount`는 내 희망 범위 중 상대 좌석을 포함한 범위의 추가금, `extraType/extraAmount`는 상대 희망 범위 중 내 좌석을 포함한 범위의 추가금이다. 이메일 등 개인정보와 신뢰도는 담지 않는다(닉네임만).
- `settlementHint`는 **참고 표시용**이며 매칭 여부와 무관하다. 한쪽 POS(받아야 하는 최소 m)·다른 쪽 NEG(낼 수 있는 최대 p=-금액)이고 둘 다 금액이 있을 때 p>=m이면 `{min:m, max:p}`, p<m이거나 다른 조합이면 null이다(POS-NEG는 금액이 안 맞아도 후보가 된다). 부호: POS=받을 금액 양수, NEG=낼 수 있는 금액 음수.
- 구현: 읽기 전용 트랜잭션, 요청·티켓 조회 2회 + 후보 SELECT 1회(닉네임·회차까지 한 번의 조인) + 총계 COUNT 1회로 후보 수와 무관한 상수 쿼리 수(N+1 없음). 잠금을 잡지 않아 잠금 순서(티켓 -> 요청)와 무관하다.

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 새 기준선 5개 테이블, V2 = `venue` 삭제·`performance.venue_name` 추가, V3 = `ticket` 좌석(구역·열·번)·상태 컬럼과 활성 좌석 유일 제약, V4 = 교환 희망 쪽 테이블 4개, V5 = 매칭·예약 잠금 테이블 2개, V6 = 추가금을 요청에서 희망 범위로 이동, V7 = 요청 소프트 삭제, V8 = 예약 방식 변경(`reserved_by_id`·`reserved_at`), V9 = 교환 완료·교환 이력(`ticket.status` EXCHANGED, `exchange_history`), V10 = 알림(`notification`), V11 = 채팅(`chat_message`, `chat_read_state`), V12 = 관리자 처리 대상(`performance_edit_proposal`, `seat_claim`), V13 = 차단·신고·이용 정지(`user_block`, `abuse_report`, `user_sanction`), V14 = 소셜 계정(`social_account`), V15 = 예약 제안 컬럼·알림 유형 6종, V16 = `users.password_login_enabled`·`social_account` (user_id, provider) 유니크, V17 = `users.withdrawn_at`, V18 = 폐기된 `users.trust_score` 제거, 한국어 주석)
- 적용 이력: `SELECT * FROM flyway_schema_history;` (docker: `docker exec matjari-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" matjari -e "SELECT * FROM flyway_schema_history"'`)
- 규칙: 스키마 변경은 새 V 파일로만, 적용된 파일 수정 금지, `ddl-auto: validate`, 엔티티 변경과 마이그레이션을 함께 작성
- 앱 기동 시 자동 적용된다.
- 2026-10-07에 좌석표 트랙을 걷어내며 기준선을 새로 만들었다. 이전 V1~V3(12개+좌석표 테이블)와 좌석표 코드는 git 태그
  `archive/seatmap-track-20261007`에 보관되어 있다. **이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 볼륨을 지운 뒤 다시 띄운다**
  (지우지 않으면 `flyway_schema_history`에 남은 옛 V1 체크섬이 달라 기동하지 않는다).
- **V2 적용 안내 (`venue` 삭제)**: 공연장은 `performance.venue_name` 텍스트가 되고 `venue` 테이블은 삭제된다.
  - 적용 전 점검: `SELECT COUNT(*) FROM performance p LEFT JOIN venue v ON v.id = p.venue_id WHERE v.id IS NULL;` 이 0이어야 한다(venue에 연결되지 않은 공연). 사라질 정보(`SELECT * FROM venue;`)도 확인한다.
  - 백업 권장: 적용 전 `mysqldump`로 `venue`·`performance`를 받아 둔다.
  - 되돌릴 수 없는 손실: `venue.address`, `status`, `verified_by`, `verified_at`, `normalized_name`.
  - 가드 실패(venue 매칭 없는 공연이 있어 SIGNAL로 중단)한 경우: 임시 프로시저 `v2_drop_venue`와 NULL 허용 `venue_name` 컬럼이 남을 수 있다. 데이터를 고친 뒤 `flyway repair`로 실패 기록을 지우고 다시 적용하면 남은 단계부터 이어서 진행된다(프로시저는 재실행 시 먼저 DROP 된다).
- **V6 적용 안내 (추가금을 희망 범위 단위로 이동, `V6__exchange_extra_per_range.sql`)**: `exchange_want_range`·`exchange_want_seat`에 `extra_type`(NOT NULL)·`extra_amount`와 CHECK(`ck_exchange_want_range_*`, `ck_exchange_want_seat_*`), `exchange_match`에 매칭 시점 스냅샷 4컬럼 `a/b_extra_type`·`a/b_extra_amount`와 CHECK를 추가하고, `exchange_request.extra_type/extra_amount`와 그 CHECK 2개를 제거한다. 이관은 요청 단위였던 값을 그 요청의 모든 범위·좌석에, 매칭 스냅샷은 a/b 각 요청의 값으로 복사한다(무손실; 범위가 0개인 요청의 추가금만 사라지는데 서비스로는 만들 수 없다). V6 맨 앞에 레거시 가드가 있다: 요청의 (POS/NEG인데 금액 NULL, 부호 불일치, X/ANY인데 금액 있음) 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패하며(원본 보존, 사전 점검 SQL과 복구 안내는 파일 상단 주석), 고친 뒤 `flyway repair` 후 재적용한다. 백필은 요청 컬럼이 남아 있는 동안 항상 덮어쓴다(부분 적용 뒤 원본을 고쳐도 반영). CHECK는 POS/NEG에 `extra_amount IS NOT NULL`을 명시했다(식이 NULL이면 CHECK가 통과해 V4의 요청 CHECK는 POS + 금액 NULL을 막지 못했다).
  - 되돌릴 수 없는 단계는 마지막 요청 컬럼 DROP 하나다. 적용 전 `mysqldump` 권장, 건수 확인: `SELECT COUNT(*) FROM exchange_request;`.
  - 재실행 가능(V2처럼 INFORMATION_SCHEMA 가드 프로시저): 중간에 실패하면 원인을 고치고 `flyway repair` 후 다시 적용하면 남은 단계부터 이어진다. 임시 프로시저 `v6_extra_per_range`는 재실행 시 먼저 DROP 한다.
- **V7 적용 안내 (요청 소프트 삭제, `V7__exchange_request_soft_delete.sql`)**: `exchange_request.status`에 `DELETED` 추가(CHECK), `deleted_at`, 생성 컬럼 `live_flag = IF(status='DELETED', NULL, 1)`, 유일 키 `uk_exchange_request_ticket(ticket_id)`를 `uk_exchange_request_live_ticket(live_flag, ticket_id)`로 교체하고 FK 전용 `idx_exchange_request_ticket(ticket_id)`를 둔다. 유일 키의 맨 앞이 `live_flag`인 이유는 FK 인덱스 규칙(갱신 컬럼이 FK 인덱스에 들어가면 UPDATE가 부모 행에 S 잠금을 걸어 교착)이다. 기존 행은 모두 `live_flag = 1`이라 위반이 없고 데이터 가드는 없다. 재실행 가능. 되돌리기(UNIQUE(ticket_id) 복원)는 DELETED 행이 있으면 실패하므로 롤포워드로 처리한다.
  - `closeByTicketId`는 `status = 'OPEN'` 조건을 유지해야 DELETED를 CLOSED로 되살리지 않는다(테스트로 고정).
  - 검증(2026-10-09, 임시 MySQL 8.0.46): V1→V7 순서 적용 + `ddl-auto: validate` 통과. 요청 4행·범위 5·좌석 6·매칭 2행이 있는 V5 상태에서 V7로 올리는 이관(범위·좌석·매칭 스냅샷 값 일치, 요청 컬럼·옛 제약 제거, `NOT NULL` 전환)과 history 삭제 후 재적용(재실행)은 `MigrationV6V7MysqlTest`(별도 DB `*_mig_it`)가 자동으로 검증한다.
- **V8 적용 안내 (한 명 예약 방식, `V8__exchange_match_single_reserve.sql`, 2026-10-10)**: `exchange_match`에 `reserved_by_id`(FK users, 예약한 사람)·`reserved_at`을 추가한다. 기존 RESERVED 행은 `a/b_reserved_at` 중 이른 쪽 사용자(동시이면 a측)를 예약자로, 그 시각을 `reserved_at`으로 백필하고 시각이 둘 다 NULL인 비정상 행은 `updated_at`을 쓴다. 구 모델의 반쪽 동의(CHATTING인데 한쪽만 `a/b_reserved_at`)는 변환하지 않고 `reserved_*`를 NULL로 둔다(승격하면 동의 없이 상대 티켓이 잠긴다; 화면의 반쪽 동의 표시는 사라진다). `a/b_reserved_at`는 삭제하지 않고 미사용 레거시(COMMENT `DEPRECATED(V8)`)로 둔다. CHECK 3개: `ck_exchange_match_reserved`(RESERVED <=> `reserved_*` NOT NULL), `ck_exchange_match_reserved_by_party`(예약자는 참여자), `ck_exchange_match_completed`(CHATTING이면 `a/b_completed_at` NULL, COMPLETED이면 둘 다 NOT NULL; README L7 해소. 교환 완료 설계가 다르면 새 V 파일로 교체). FK 전용 인덱스 `idx_exchange_match_reserved_by`는 단일 컬럼이다. 재실행 가능(프로시저 가드), 완료 시각이 상태와 맞지 않는 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패한다. 구 코드(`accept`)와는 호환되지 않으므로 마이그레이션과 앱을 함께 배포한다. 요구 MySQL 8.0.16 이상.
  - 검증(2026-10-10, 임시 MySQL 8.0.46): `MigrationV8MysqlTest`(별도 DB `*_mig_it`)가 7가지 매칭 행(CHATTING 무예약, CHATTING 반쪽 동의, RESERVED(a가 이른·b가 이른·동시·시각 NULL), CANCELED에 옛 시각)의 백필 값, 레거시 컬럼·잠금 행 불변, 새 FK·인덱스·CHECK, history 삭제 후 재적용(재실행), 중간 실패 이어서 적용, 가드 실패 후 repair, 제약 위반 거부를 자동으로 검증한다.
- **V9 적용 안내 (교환 완료·교환 이력, `V9__exchange_complete_history.sql`, 2026-10-11)**: `ticket.status`의 `ck_ticket_status`를 ACTIVE/INACTIVE/EXCHANGED로 확장한다(옛 정의에 EXCHANGED가 없을 때만 DROP 후 ADD). 새 테이블 `exchange_history`(append-only, 매칭 1건 완료 시 사용자별 1행씩 2행)를 만든다: `match_id`·`user_id`·`old_ticket_id`·`new_ticket_id`·`performance_id` FK(ON DELETE/UPDATE 동작 없음), 스냅샷 `performance_title`·`venue_name`·`old_starts_at`/`new_starts_at`·`old_`/`new_` 구역·열·번 label, `created_at`. UK `uk_exchange_history_old_ticket`·`uk_exchange_history_new_ticket`(한 티켓은 한 번만 교환), 단일 컬럼 KEY `idx_exchange_history_match`·`_user`·`_performance`, CHECK `ck_exchange_history_tickets`(old <> new). 기존 데이터 이관과 파괴적 단계는 없고, V2·V6~V8과 같은 INFORMATION_SCHEMA 가드 프로시저라 재실행 가능하다. 되돌리기는 롤포워드(이력 삭제 후 EXCHANGED 행 정리, CHECK 축소)로 한다. 요구 MySQL 8.0.16 이상. 검증: `MigrationV9MysqlTest`(실제 MySQL, 별도 DB `*_mig_it`, `MATJARI_IT_*`가 있을 때만 실행).
- **V10 적용 안내 (알림, `V10__notification.sql`, 2026-10-11)**: 새 테이블 `notification`만 만든다. 기존 테이블 변경·데이터 이관·파괴적 단계는 없다. 컬럼: `id`, `user_id`(FK users, 수신자), `type`(VARCHAR(40), CHECK로 3종만), `match_id`(FK exchange_match, NOT NULL, CHECK `ck_notification_match_required`), `dedup_key`(VARCHAR(100) NULL, UNIQUE `uk_notification_dedup`), `read_at`(NULL = 안 읽음), `created_at`, 생성 컬럼 `unread_user_id`(STORED, 안 읽었으면 user_id, 안 읽은 개수 인덱스 `idx_notification_unread`, FK 아님). FK 인덱스 `idx_notification_user`·`idx_notification_match`는 단일 컬럼이고 FK에 ON DELETE/UPDATE 동작을 두지 않는다. 같은 INFORMATION_SCHEMA 가드 프로시저라 재실행 가능하다. 되돌리기는 롤포워드(알림 삭제 뒤 테이블 정리). 요구 MySQL 8.0.16 이상. 마이그레이션 전용 테스트는 없고, 실제 MySQL의 `NotificationApiMysqlTest`가 V10이 적용된 DB에서 생성·읽음·삭제를 검증한다. 설계 가안의 `(user_id, type, match_id)` 유니크는 쓰지 않는다(재예약 때 위반으로 예약이 롤백되므로, 한 번만 생기는 제안 알림만 `dedup_key`로 막는다).
- **V11 적용 안내 (채팅, `V11__chat_message.sql`, 2026-10-11)**: 새 테이블 2개만 만든다. 기존 테이블 변경·데이터 이관·파괴적 단계는 없다. `chat_message`(append-only, 만든 뒤 수정·삭제하지 않음): `match_id`·`sender_id` FK(ON DELETE/UPDATE 동작 없음), `content` VARCHAR(1000)(코드 포인트 기준은 앱에서 검사), `client_message_id` VARCHAR(36) ascii_bin(대소문자 구분), UNIQUE `uk_chat_message_client(client_message_id, sender_id)`, 단일 컬럼 KEY `idx_chat_message_match`·`idx_chat_message_sender`, CHECK 2개(본문 공백 아님, id 길이 8~36). `chat_read_state`: PK(`match_id`, `user_id`), `last_read_message_id` BIGINT(FK 아님, 0 = 아무것도 안 읽음, CHECK >= 0), `updated_at`, FK 2개와 `idx_chat_read_state_user`. 같은 INFORMATION_SCHEMA 가드 프로시저라 재실행 가능하다. 되돌리기는 롤포워드(두 테이블 삭제). 요구 MySQL 8.0.16 이상. 검증: `ChatSchemaMysqlTest`(실제 MySQL, `MATJARI_IT_*`가 있을 때만).
- **V12 적용 안내 (관리자 처리 대상, `V12__admin_edit_proposal_seat_claim.sql`, 2026-10-12)**: 새 테이블 2개만 만든다(`performance_edit_proposal`, `seat_claim`). 기존 테이블 변경·데이터 이관·파괴적 단계는 없다. 두 테이블 모두 상태 `PENDING/APPROVED/REJECTED`(CHECK)이고, 'PENDING은 사용자(·좌석)당 1건'을 생성 컬럼 `pending_proposer_id`·`pending_claimant_id`(PENDING일 때만 사용자 id, 아니면 NULL)와 UNIQUE로 강제한다. 처리되면 NULL이 되어 유니크에서 빠지므로 다시 제안·요청할 수 있다. FK 인덱스는 모두 단일 컬럼이고 FK에 ON DELETE/UPDATE 동작을 두지 않는다. `seat_claim.image_key`는 소문자 UUID(UNIQUE, CHECK 정규식), `image_content_type`은 jpeg/png/webp, `image_size`는 1~5242880(5 MiB), `resolved_ticket_id`는 FK가 아니다(승인 때 내려진 티켓 id 기록용). 같은 INFORMATION_SCHEMA 가드 프로시저라 재실행 가능하다. 되돌리기는 롤포워드(두 테이블 삭제). 요구 MySQL 8.0.16 이상. 검증: `AdminSchemaMysqlTest`(실제 MySQL, `MATJARI_IT_*`가 있을 때만).
- **V13 적용 안내 (차단·신고·이용 정지, `V13__block_report_sanction.sql`, 2026-10-12)**: 새 테이블 3개만 만든다. 기존 테이블(`users` 포함) 변경·데이터 이관·파괴적 단계는 없다. `user_block`: `blocker_id`·`blocked_id` FK(ON DELETE/UPDATE 동작 없음), UNIQUE `uk_user_block_pair(blocker_id, blocked_id)`가 blocker FK 인덱스를 겸하고 KEY `blocked_id` 단일 컬럼, CHECK 자기 차단 금지. `abuse_report`: reason 5종(`ABUSE`/`FRAUD`/`NO_SHOW`/`FAKE_SEAT`/`OTHER`)과 status(`OPEN`/`RESOLVED`/`DISMISSED`) CHECK, 'OPEN은 (신고자, 매칭)당 1건'을 생성 컬럼 `open_reporter_id`(status가 OPEN일 때만 신고자 id, 아니면 NULL)와 UNIQUE(`open_reporter_id`, `match_id`)로 강제, `sanction_id`는 순환 FK를 피하려고 FK가 아니다, 처리 상태별 CHECK(처리됐으면 `handled_*` 필수), OTHER면 `detail` 필수, reporter ≠ reported CHECK. 관리자 목록용 status 인덱스와 대상별 누적 조회용 `reported_id` 단일 인덱스를 둔다. `user_sanction`: `user_id`·`report_id`(NULL 가능)·`created_by_id`·`revoked_by_id` FK, type `SUSPEND` CHECK, `ends_at` NULL = 무기한, `revoked_at`(해제 시각, 있으면 `revoked_by_id` 필수), CHECK `ends_at > starts_at`·자기 제재 금지. '사용자당 활성 제재 1건'은 DB 제약이 없고 서비스가 대상 `users` 행을 FOR UPDATE로 잠근 뒤 검사한다. 같은 INFORMATION_SCHEMA 가드 프로시저라 재실행 가능하다. 되돌리기는 롤포워드(세 테이블 삭제). 요구 MySQL 8.0.16 이상. 검증: `BlockReportSchemaMysqlTest` 7건(실제 MySQL, `MATJARI_IT_*`가 있을 때만).
- **V14 적용 안내 (소셜 로그인, `V14__social_account.sql`, 2026-10-12)**: 새 테이블 `social_account` 하나만 만든다. 기존 테이블(`users` 포함) 변경·데이터 이관·파괴적 단계는 없다. 컬럼: `id`, `user_id`(FK users, 단일 컬럼 KEY), `provider` VARCHAR(20) utf8mb4_bin(GOOGLE/KAKAO/NAVER CHECK), `provider_user_id` VARCHAR(100) utf8mb4_bin(빈 값 CHECK), `created_at`. UNIQUE(provider, provider_user_id). 재실행 가능한 프로시저 패턴. 다음 마이그레이션은 V16.
- **V15 적용 안내 (예약 제안·채팅 알림, `V15__reserve_proposal_chat_notification.sql`, 2026-10-13)**: `exchange_match`에 `reserve_proposed_by_id`(FK users, 단일 컬럼 인덱스)·`reserve_proposed_at`을 추가한다(둘 다 NULL이거나 둘 다 NOT NULL, 제안은 CHATTING에서만, 제안자는 참여자라는 CHECK 3종. FK는 `reserved_by_id`와 같은 방식). `notification.type` CHECK를 6종으로 넓히고 `ck_notification_chat_dedup`(CHAT_MESSAGE는 `dedup_key = 'CHAT_MESSAGE:{matchId}:{recipientId}'` 형식 강제)을 추가한다. 기존 행은 백필 없이 만족한다. 재실행 가능한 프로시저 패턴. 테이블 수는 20 그대로다.
- **V16 적용 안내 (비밀번호 로그인 가능 여부·사용자당 제공자 1개, `V16__user_password_login.sql`, 2026-10-13)**: 새 테이블은 없다(테이블 수 20 그대로). `users.password_login_enabled TINYINT(1) NOT NULL DEFAULT 1`을 추가하고, `social_account` 행이 있는 사용자는 백필로 0이 된다(0으로만 바꾸는 멱등 UPDATE). `social_account`에 `uk_social_account_user_provider (user_id, provider)`를 추가하며, 기존 중복 행이 있으면 SIGNAL로 중단한다. 재실행 가능한 프로시저 패턴, 파괴적 단계 없음. 다음 마이그레이션은 V17.
- **V17 적용 안내 (회원 탈퇴 시각, `V17__user_withdrawn_at.sql`, 2026-10-13)**: 새 테이블은 없다(테이블 수 20 그대로). `users.withdrawn_at DATETIME(6) NULL`(NULL이면 활성)만 추가한다. 인덱스·CHECK·백필 없음, 파괴적 단계 없음. 되돌리기는 롤포워드(새 V 파일에서 컬럼 DROP).
- **V18 적용 안내 (폐기된 신뢰도 점수 컬럼 제거, `V18__drop_user_trust_score.sql`, 2026-10-13)**: 새 테이블은 없다(테이블 수 20 그대로). `users.trust_score`(DOUBLE NULL, 가입 때 0.0만 저장되던 값)를 DROP한다. 컬럼이 있을 때만 드롭하는 재실행 가능한 프로시저이고, 인덱스·FK·CHECK가 이 컬럼을 참조하지 않는다. 엔티티 `User.trustScore`와 `GET /api/users/me` 응답의 `trustScore`도 함께 없앴다. 파괴적 단계이며 값은 복구되지 않는다(되돌리기는 롤포워드, 새 V 파일에서 컬럼을 다시 추가).
- 차단·신고·이용 정지(`user_block`, `abuse_report`, `user_sanction`)는 **V13에서 구현됐다**(V8은 예약 방식 변경, V9는 교환 완료·교환 이력, V10은 알림, V11은 채팅, V12는 관리자 처리 대상 두 테이블에 사용됨)(예전 문서의 'V6 이후'는 V6/V7이 추가금·소프트 삭제에 쓰이면서 밀렸다).
- **V3 적용 안내 (`ticket` 좌석 컬럼)**: `zone_label/zone_key`, `row_key`, `col_key`, `status`, `created_at/updated_at`, 생성 컬럼 `active_flag`, `uk_ticket_active_seat`(회차·구역·열·번·active_flag), `idx_ticket_user_status`를 추가하고 `row_label`/`col_label`을 NOT NULL VARCHAR(20)으로 바꾼다.
  - 가드: `ticket`에 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패한다(새 NOT NULL 구역 컬럼에 채울 값이 없음). 적용 전 `SELECT COUNT(*) FROM ticket;`가 0인지 확인한다. 행이 있어 실패했다면 테스트 행을 지우고 `flyway repair`(실패 기록 삭제) 후 다시 적용한다. 임시 프로시저 `v3_guard_ticket_empty`가 남을 수 있으나 재실행 시 먼저 DROP 한다.
  - 주의: `./gradlew bootRun`은 `backend/.env`를 읽어 `SPRING_DATASOURCE_URL`을 덮어쓴다. 임시 DB로 검증하려면 `bootRun`이 아니라 `bootJar` 후 `java -jar`로 환경변수를 지정해 실행한다.
- **V4 적용 안내 (교환 희망 테이블)**: `exchange_request`(티켓당 1개 `uk_exchange_request_ticket`, 추가금 유형·금액 CHECK), `exchange_want_range`(입력한 범위, 수정 화면 복원용), `exchange_want_seat`(펼친 희망 좌석, PK = request_id + 구역·열·번 키), `exchange_want_session`(희망 회차 + 우선순위, PK = request_id + 회차)를 만든다. 자식 3개는 요청 삭제 시 `ON DELETE CASCADE`다. 기존 데이터를 건드리지 않아 가드가 없다. 차단·매칭·예약 잠금·채팅·이력 테이블은 V5 이후다. `exchange_want_range`의 `row_from/row_to/col_from/col_to`에는 정규화 키를 저장한다.
- **V5 적용 안내 (매칭·예약 잠금)**: `exchange_match`(후보를 골라 시작한 매칭. a = 제안자, b = 고른 상대. 요청·티켓·사용자 a/b, 상태 4종 CHATTING/RESERVED/COMPLETED/CANCELED, `a/b_reserved_at`·`a/b_completed_at`, `canceled_by_id`(NULL = 시스템 취소)·`canceled_at`, 생성 컬럼 `request_low_id`/`request_high_id`/`open_flag`와 `uk_exchange_match_open_pair`로 같은 쌍의 열린 매칭 1개)와 `exchange_ticket_lock`(PK ticket_id, 티켓당 예약 1개, `match_id` FK ON DELETE CASCADE)을 만든다. 기존 데이터를 건드리지 않아 가드가 없다. 차단(`user_block`)·채팅(`chat_message`)·이력(`exchange_history`)은 후속 V 파일이다. FK 인덱스는 단일 컬럼이어야 한다(위 '동시성'의 교착 사례).
- **V21 적용 안내 (공연 구역 목록·좌석표 비율, `V21__performance_zone.sql`, 2026-10-18)**: 새 테이블 `performance_zone`(`performance_id` FK, `name` VARCHAR(30), `name_key` VARCHAR(50) utf8mb4_bin, `sort_order` SMALLINT, `shape_polygons` JSON NULL, `created_at`, UNIQUE(name_key, performance_id), FK 전용 단일 컬럼 KEY(performance_id))와 `performance.seatmap_aspect` DECIMAL(6,3) NULL(0 초과 CHECK)을 추가한다. 기존 데이터는 바뀌지 않는다. JSON CHECK는 조각 1~8개·꼭짓점 3~40개 같은 구조만 검사하고 좌표 범위·쌍 형식은 서비스가 검사한다. 재실행 가능한 프로시저 패턴, 파괴적 단계 없음. 다음 마이그레이션은 V22.
- MySQL 최소 버전 8.0.16 — 그 미만은 CHECK 제약을 문법만 받고 강제하지 않는다 (현재 docker 이미지는 mysql:8.0).

## 관리자 권한

- 관리자는 DB에서 직접 부여한다: `UPDATE users SET role = 'ADMIN' WHERE email = '...';` — **반드시 대문자 `ADMIN`으로만**.
  `users.role`은 `utf8mb4_bin` 컬럼이라 소문자(`admin`)는 CHECK 제약에서 거부된다.
  가입은 항상 USER이며 요청 본문의 role은 무시된다. role은 토큰에 넣지 않고 매 요청 DB에서 읽는다(변경 즉시 반영).

## 관리자 API · 공연 수정 제안 · 내 티켓 인증 (V12, 2026-10-12, `feature/admin`)

관리자가 처리하는 것은 두 가지다. ① 공연 수정 제안(제목·공연장) ② 좌석 소유 인증 요청(화면 이름 '내 티켓 인증'). 알림은 만들지 않고, 제안자·요청자는 `/my/...` 목록에서 결과를 본다. 관리자 권한은 위 '관리자 권한' 절과 같다(`/api/admin/**`는 ADMIN, role은 매 요청 DB에서 읽는다).

### API

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| POST | /api/performances/{id}/edit-proposals | 로그인 | 공연 수정 제안 (201). 본문 `{newTitle, newVenueName, reason, sessionChanges?}`, 제목·공연장·회차 변경 중 하나 이상. `sessionChanges` = `[{type: ADD\|CHANGE\|REMOVE, sessionId?, startsAt?}]`(최대 20개, `startsAt`은 `YYYY-MM-DDTHH:mm` KST, 10분 단위·지난 시각 불가). 오류: 400 `sessionChanges`(형식·중복·없는 회차·회차 0개·시각 겹침), 422 `SESSION_IN_USE`(티켓·희망 조건이 걸린 회차 삭제). 응답에 `sessionChanges: [{type, sessionId, startsAt, currentStartsAt}]` |
| GET | /api/performances/edit-proposals/me | 로그인 | 내 수정 제안 목록 |
| POST | /api/seat-claims | 로그인 | 내 티켓 인증 요청 (201). multipart: `sessionId`, `zone`, `row`, `col`, `memo`(선택), `image`(사진 1장) |
| GET | /api/seat-claims/me | 로그인 | 내 인증 요청 목록 |
| GET | /api/seat-claims/{id}/image | 요청자 본인 또는 ADMIN | 사진 바이트. 그 외 404, 삭제됐으면 410 `IMAGE_DELETED` |
| GET | /api/admin/performance-edit-proposals?status&page&size | ADMIN | 수정 제안 목록 (status 생략 시 전체) |
| POST | /api/admin/performance-edit-proposals/{id}/approve | ADMIN | 승인: 공연에 반영 (이전 값을 `old_title`·`old_venue_name`에 스냅샷, 회차 변경은 삭제→시각 변경→추가 순서로 반영하고 기존 시각을 기록). 승인 시점에 회차 상태가 달라졌으면 409 `SESSION_EDIT_CONFLICT`(제안은 PENDING 유지) |
| POST | /api/admin/performance-edit-proposals/{id}/reject | ADMIN | 거절. 본문 `{note}` |
| GET | /api/admin/seat-claims?status&page&size | ADMIN | 인증 요청 목록: 사진·요청자·현재 그 좌석을 가진 티켓 요약(holder) |
| POST | /api/admin/seat-claims/{id}/approve | ADMIN | 승인: 현재 그 좌석의 활성 티켓을 시스템이 내린다 |
| POST | /api/admin/seat-claims/{id}/reject | ADMIN | 거절. 본문 `{note}`, 사진 삭제 |

### 공연 수정 제안 규칙
- 제안 값이 현재 값과 같은 항목은 null로 정규화한다. 둘 다 같으면 422 `EDIT_NO_CHANGE`. 회차·링크는 수정 대상이 아니다.
- 사용자당 PENDING 3건까지(422 `EDIT_PROPOSAL_LIMIT_REACHED`), 같은 공연에는 PENDING 1건만(409 `EDIT_PROPOSAL_ALREADY_PENDING`). 같은 공연의 다른 PENDING 제안은 승인되어도 그대로 둔다. 이미 처리된 제안을 다시 처리하면 409 `PROPOSAL_ALREADY_REVIEWED`.
- 잠금 순서: 제안 행 FOR UPDATE → 공연 행 FOR UPDATE(단일 트랜잭션). 생성은 첫 쿼리로 사용자 행을 FOR UPDATE해 상한을 동시 요청에서도 지킨다.
- 공연을 바꾸는 유일한 경로는 `Performance.applyApprovedEdit`(관리자 승인)다.

### 좌석 소유 인증 규칙
- 티켓 등록에서 같은 좌석이 이미 등록돼 있으면(409 `SEAT_ALREADY_REGISTERED`) 화면이 '내 티켓 인증' 링크를 보인다.
- 요청은 **회차·구역·열·번(좌석 좌표)** 으로 만든다. 409 응답이 상대 티켓 id를 노출하지 않도록 티켓 id가 아니라 좌석 키를 담는다. 정규화는 `SeatKeyNormalizer`와 같다.
- 사진은 예매내역 사진 1장, jpeg·png·webp(매직 넘버로 형식 판별), 5 MiB 이하(초과 413 `IMAGE_TOO_LARGE`)다. 서버 저장 이름은 UUID만 쓰고, 저장 위치는 `matjari.upload.dir`(기본 `./data/seat-claims`, `MATJARI_UPLOAD_DIR`로 지정, git 추적 제외, 웹에서 직접 서빙하지 않음)다. 사진에 개인정보가 있을 수 있어 예매번호·이름을 가리고 올리라고 안내한다.
- 사용자당 PENDING 5건(422 `SEAT_CLAIM_LIMIT_REACHED`), 같은 좌석에 PENDING 1건(409 `SEAT_CLAIM_ALREADY_PENDING`). 이미 내 활성 티켓이면 409 `MY_TICKET_ALREADY_REGISTERED`, 회차가 마감됐으면 422 `SESSION_CLOSED`, 그 좌석에 등록된 티켓이 없으면 422 `SEAT_NOT_REGISTERED`.
- 승인: 현재 그 좌석의 활성 티켓을 INACTIVE로 내리고, 그 티켓의 교환 요청을 CLOSED로 바꾸고, 그 티켓의 CHATTING 매칭을 시스템 취소한 뒤 요청을 APPROVED로 바꾼다. 예약 잠금 중이면 409 `TICKET_RESERVED`, 교환 완료된 티켓이면 409 `TICKET_EXCHANGED`로 승인할 수 없다. 이미 비어 있으면 승인은 성공하고 `resolved_ticket_id`는 NULL이다. 요청자는 승인 뒤 일반 티켓 등록으로 직접 등록한다(자동 등록 없음).
- 이미 처리된 요청을 다시 승인·거절하려 하면 409 `CLAIM_ALREADY_REVIEWED`다.
- 승인·거절 모두 **커밋 뒤에** 사진 파일을 지우고 `image_deleted_at`을 기록한다. 파일 삭제가 실패해도 처리 결과는 유지되고, 남은 파일은 아래 정리 작업이 지운다. 처리된 요청은 `imageAvailable=false`로 응답하고 사진 조회는 410이다.
- 사진 응답 헤더: `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-store`. 본인과 ADMIN만 조회할 수 있고, 그 외에는 존재를 숨기려고 404를 준다.

### 잠금 순서
- 인증 요청 행 FOR UPDATE → 티켓 id↑ → 요청 id↑ → 매칭. 좌석 키 보조 유니크 인덱스로 티켓에 FOR UPDATE를 걸면 PK부터 잠그는 `deactivate`와 교착이 나므로, 티켓은 id를 잠금 없이 읽고 PK로 잠근 뒤 상태를 다시 판단한다(`TicketService.deactivateLocked` 추출, 기존 동작 불변).

### 알려진 한계
- 커밋 뒤 사진 삭제가 실패하거나 서버가 종료되면 파일이 남을 수 있다. 정리 작업이 주기적으로 지운다(아래 '사진 정리 작업'). 정리 사이에는 파일이 남아 있어도 조회는 불가능하다(처리된 요청은 410).
- 정리 작업은 서버 한 대 기준이다(여러 대여도 없는 파일 삭제는 성공으로 보므로 안전). 파일 시각(마지막 수정)이 서버 시계와 어긋나면 grace 판정이 틀릴 수 있다.

### 사진 정리 작업 (`SeatClaimImageCleanupService`)
- `@Scheduled` fixedDelay(`SeatClaimImageCleanupScheduler`, 기본 1시간마다, 기동 5분 뒤 첫 실행)로 두 가지를 지운다.
  1. **처리된 요청인데 사진이 남은 것**: `status<>PENDING AND image_deleted_at IS NULL AND reviewed_at < now-grace`인 요청의 파일을 지우고(이미 없어도 성공) `image_deleted_at`을 기록한다. 삭제에 실패하면 기록하지 않고 다음 주기에 다시 한다.
  2. **요청 행이 없는 고아 파일**: 저장 디렉터리에서 UUID 이름이고 마지막 수정이 grace보다 오래된 일반 파일 중 `seat_claim.image_key`가 가리키지 않는 것을 지운다. 저장 직후 INSERT가 커밋되기 전의 짧은 틈을 지키려고 grace(기본 60분)보다 어린 파일은 보지 않는다.
- PENDING 요청의 사진, UUID 형식이 아닌 이름, 하위 디렉터리, 심볼릭 링크는 건드리지 않는다. 한 번에 `batch`(기본 200)건까지만 하고 나머지는 다음 주기에 한다. 한 단계가 실패해도 다른 단계는 계속하고 로그에는 예외 종류와 건수만 남긴다.
- 설정(`matjari.upload.cleanup.*`, 환경변수 `MATJARI_UPLOAD_CLEANUP_ENABLED`·`_INTERVAL_MS`·`_INITIAL_DELAY_MS`·`_GRACE_MINUTES`·`_BATCH`): `enabled=false`로 끌 수 있다.
- 테스트: `SeatClaimImageCleanupServiceTest` 6건(임시 디렉터리), `SeatClaimMysqlTest`의 정리 시나리오 1건(실제 MySQL).
- 관리자 좌석 인증 목록의 holder 조회는 N+1이다(최대 100건).
- 관리자가 자기 제안·자기 인증 요청을 승인하는 것은 409 `SELF_REVIEW`로 막는다(거절은 허용). 승인 시점에 좌석을 요청자 본인이 가졌다면 본인 티켓은 내리지 않고 승인만 완료한다(resolved 없음). 승인 트랜잭션은 READ COMMITTED로 돌려 예약 잠금 검사가 낡은 스냅샷을 읽지 않게 한다(검증에서 발견한 TOCTOU).
- 이미지 저장소는 서버 로컬 디스크라 다중 인스턴스에서는 쓸 수 없다.
- 사진의 EXIF는 제거하지 않는다(처리 후 삭제로 대응).
- 신고 처리용 관리자 기능은 이번 범위가 아니다(차단·신고 기능과 함께 후속).

### 테스트
- 백엔드 708건 통과(임시 MySQL IT 포함, 실패 0·skip 0). V12 스키마 `AdminSchemaMysqlTest` 6건, 수정 제안 24건(동시 승인 10라운드×8스레드에서 1건만 성공 등), 좌석 인증 31건(동시 승인·거절, 승인+소유자 내리기+제3자 제안+예약 혼합 경합에서 교착 0 등).
- 프론트 `tsc`·빌드 통과. 브라우저 점검은 하지 않았다.

## 차단·신고·이용 정지 (V13, 2026-10-12, `feature/block-report`)

차단·신고, 관리자 신고 처리, 이용 정지(제재)를 한 브랜치에서 구현했다. DB는 V13 세 테이블(`user_block`, `abuse_report`, `user_sanction`)이다. 확정 설계와 JSON 계약의 원문은 `산출물/08_ERD/exchange-schema-design.md`의 V13 절과 `산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md`를 따른다. 관리자 신고 처리는 Q-21 확정안(신고 목록·채팅 근거 열람·처리 + 이용 정지 + 차단 현황 조회)이다.

### 사용자 API

모두 로그인(Bearer)이 필요하다. 상대는 매칭에서 정해지므로 임의의 userId를 차단·신고하는 API는 없다(자기 자신을 대상으로 삼는 경로도 없다).

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/matches/{matchId}/block | 상대 차단 (200). 참여자만(비참여자·없는 매칭은 404). 응답 `{blockId, blockedNickname, canceledMatches, alreadyBlocked}` |
| GET | /api/blocks?page&size | 내가 차단한 목록. `PageResponse` of `{id, blockedNickname, createdAt}` |
| DELETE | /api/blocks/{id} | 차단 해제 (204). 본인 행만(남의 것·없는 것은 404). 멱등이 아니다 |
| POST | /api/exchange/matches/{matchId}/report | 신고 (201). body `{reason, detail?}` → `{id, status:"OPEN", createdAt}`. 참여자만, 닫힌(CANCELED·COMPLETED) 매칭에서도 가능 |
| GET | /api/reports/me?page&size | 내 신고 내역. `{id, matchId, reportedNickname, reason, status, createdAt, handledAt\|null}`. 처리 메모·제재 내용은 숨긴다 |

- 신고 사유 `reason`: `ABUSE`(욕설·괴롭힘), `FRAUD`(사기 의심·허위 예매내역), `NO_SHOW`(연락 두절·교환 불이행), `FAKE_SEAT`(허위 좌석), `OTHER`. `detail`은 500자 이하이며 `OTHER`면 필수다.
- 매칭 응답(`ExchangeMatchResponse`)과 후보 응답에는 차단·신고 관련 필드를 넣지 않았다.

### 관리자 API (`/api/admin/**`, ADMIN 전용)

| Method | Path | 설명 |
|---|---|---|
| GET | /api/admin/reports?status&reason&page&size | 신고 목록(상태·사유 필터). `AdminReport` = `{id, matchId, reason, detail, status, createdAt, handledAt, handlerNickname, handlerNote, reporter:{id,nickname}, reported:{id,nickname,totalReports,openReports,activeSanction:null\|{id,until}}}` |
| GET | /api/admin/reports/{id} | 신고 상세 |
| GET | /api/admin/reports/{id}/messages?before&size | 신고된 매칭의 채팅 근거를 최신부터 위로 `{messages:[{id,senderId,senderNickname,content,createdAt}], hasMore}`. size 기본 50, 최대 200. 신고된 매칭의 메시지만 본다 |
| POST | /api/admin/reports/{id}/resolve | 조치 (200, `AdminReport`). body `{note?, sanction?: {days: 7\|30\|null, note}}`. 제재를 함께 걸면 같은 트랜잭션에서 `user_sanction` INSERT, 열린 매칭 시스템 취소, 신고 `sanction_id` 기록. `days` null은 무기한. 제재 사유는 필수(≤300) |
| POST | /api/admin/reports/{id}/dismiss | 기각 (200, `AdminReport`). body `{note?}` |
| GET | /api/admin/sanctions?active=true\|false\|(생략=전체)&page&size | 제재 목록. `{id, userId, nickname, type, note, startsAt, endsAt, revokedAt, active, createdByNickname, reportId}` |
| POST | /api/admin/sanctions/{id}/revoke | 제재 해제 (200, 같은 형식). 해제해도 취소된 매칭은 되살아나지 않는다 |
| GET | /api/admin/blocks?page&size | 차단 현황(읽기 전용). `{id, blocker:{id,nickname}, blocked:{id,nickname}, createdAt}` |

- 처리 메모·제재 사유는 ≤300자다. 채팅 근거 열람은 관리자 전용이며 화면 상단에 '처리 목적 외 사용 금지' 안내를 둔다.
- 관리자 계정(ADMIN)은 제재 대상이 될 수 없다(409 `CANNOT_SANCTION_ADMIN`).

### 정지 계정 응답 형식

정지(활성 제재) 중인 사용자는 모든 인증 API가 다음 형식의 403을 받는다.

```json
{"message":"이용이 정지된 계정입니다.","code":"ACCOUNT_SUSPENDED","until":"2026-11-01T00:00:00"}
```

`until`은 기간이 있으면 `yyyy-MM-ddTHH:mm:ss`, 무기한이면 null이다. 로그인(비밀번호가 맞은 뒤에만 검사)·토큰 재발급도 같은 403이다. `/api/auth/logout`은 정지 중에도 204로 동작한다.

**정지 중 제한 로그인(탈퇴 전용 토큰)**: `POST /api/auth/login`에서 **비밀번호가 맞고 정지 중이면** 같은 403 본문에 `"withdrawToken":"…"`이 더해진다(비밀번호가 틀리면 일반 로그인 실패라 정지 여부도 알리지 않는다). 이 토큰은 JWT `type=withdraw`, 유효 10분(`jwt.withdraw-token-validity-ms`, 환경변수 `JWT_WITHDRAW_TOKEN_VALIDITY_MS`)이고, `JwtAuthenticationFilter`가 `DELETE /api/users/me`에서만 인증으로 인정한다(다른 경로는 미인증 401, `/api/auth/refresh`·STOMP CONNECT는 거부). 탈퇴 자체의 비밀번호 확인·속도 제한·처리는 일반 탈퇴와 같다. 소셜 전용 계정은 비밀번호 로그인이 없어 이 경로가 없다.

### 차단 규칙
- 차단은 **방향이 하나**(blocker → blocked)지만 **효과는 양방향**이다. 누가 차단했든 ① 서로 후보에서 빠지고 ② 새 제안은 막힌다(후보 재검증 422 `NOT_A_CANDIDATE`) ③ 둘 사이의 열린 매칭(CHATTING·RESERVED)은 차단 시점에 시스템 취소(`canceled_by_id` NULL)되고 예약 잠금이 풀린다 ④ 취소된 매칭의 채팅은 읽기 전용이다 ⑤ COMPLETED 매칭과 교환 이력은 그대로다.
- 차단당한 쪽에는 알리지 않는다. 열린 매칭은 기존 취소 표시(상대가 채팅을 종료했어요와 같은 문구)로만 보인다.
- 멱등: 이미 차단 중이면 200(`alreadyBlocked: true`). 사용자당 차단 상한 200(422 `BLOCK_LIMIT_REACHED`). 상한 검사는 사용자 행을 잠그지 않으므로 동시 요청이 몰리면 근소하게 넘을 수 있다(알려진 한계).
- 해제는 `DELETE /api/blocks/{id}`로 한다. 해제해도 취소된 매칭은 복구되지 않으며, 다시 제안하는 것은 가능하다.
- 재매칭 불가는 차단 때만이다(신고만으로는 자동 차단하지 않는다).

### 신고 규칙
- 같은 매칭에 내가 낸 OPEN 신고는 1건이다(409 `REPORT_ALREADY_OPEN`). 사용자당 OPEN 신고 상한은 10건이다(422 `REPORT_LIMIT_REACHED`, 동시 요청 시 근소 초과 가능).
- 신고는 대상에게 알리지 않으며 자동 제재·자동 차단이 없다. 신고 INSERT는 트랜잭션의 마지막이다.
- 신고된 매칭의 채팅 메시지는 근거 보존을 위해 관리자가 볼 수 있다(메시지 무기한 보관 규칙 그대로, 신고자·대상 외에는 관리자만).
- 처리 상태: `OPEN` → `RESOLVED`(조치, 제재 포함 가능) | `DISMISSED`(기각). 처리된 신고는 다시 처리할 수 없다(409 `REPORT_ALREADY_HANDLED`). 신고자는 '내 신고 내역'에서 상태만 본다.

### 이용 정지(제재) 규칙
- 제재는 관리자가 신고를 처리하며 함께 건다. 기간 `days` = 7 | 30 | null(무기한). 사유 필수.
- 정지를 거는 순간 대상의 열린 매칭(CHATTING·RESERVED) 전부를 시스템 취소하고 잠금을 푼다(`OpenMatchCanceller.cancelAllOf`). 완료된 교환·티켓은 그대로이고 티켓은 후보에서만 빠진다. 정지 중인 사용자 티켓은 후보로 나오지 않으며 정지 사용자는 후보 조회를 할 수 없다(403).
- 기간 만료는 조회 시점에 `ends_at > now`로 판단한다(스케줄러 없음). 관리자는 `revoke`로 해제할 수 있다.
- 이미 정지 중인 대상에 또 제재를 걸면 409 `SANCTION_ALREADY_ACTIVE`, 해제·만료된 제재를 해제하면 409 `SANCTION_NOT_ACTIVE`.
- 강제 위치: `CustomUserDetailsService.loadUserById`(요청마다 DB 1회 조회) → `AccountSuspensionChecker`. 그래서 JWT 발급 뒤에 정지되어도 다음 요청부터 403이다. STOMP CONNECT도 같은 검사로 거부한다.
- **STOMP 연결**: 이용 정지는 커밋 뒤 대상의 열려 있는 STOMP 연결을 모두 닫는다(`WebSocketSessionRegistry.closeAllOf`, 1008). 차단은 연결을 닫지 않고 열린 매칭 취소로 쓰기를 막는다. 정지 후 새 CONNECT도 거부된다.

### 잠금 순서와 동시성
- 차단·신고·제재는 기존 규약을 따른다: 티켓 id↑ → 요청 id↑ → 매칭. `user_block`·`abuse_report`·`user_sanction` INSERT는 트랜잭션의 마지막이다.
- 열린 매칭 시스템 취소는 `OpenMatchCanceller`(인터페이스, `cancelBetween`·`cancelAllOf`, 활성 트랜잭션 필수)가 한다. 구현 `OpenMatchCancellerImpl`은 대상 매칭 id를 잠금 없이 읽고 → 관련 티켓 id 오름차순 FOR UPDATE → 매칭 id 오름차순 FOR UPDATE → 상태 재확인 후 CANCELED·잠금 삭제를 한다. 요청 상태는 건드리지 않는다. 호출 트랜잭션은 READ COMMITTED다(티켓 잠금 뒤 매칭 id를 다시 읽기 위해).
- `resolve`: 대상의 신고 행들을 먼저 FOR UPDATE로 잠가 같은 대상의 동시 제재를 직렬화한다(READ COMMITTED라 갭 잠금이 없다) → ADMIN 여부 확인 → 활성 제재 잠금 조회 → `cancelAllOf` → `user_sanction` INSERT → 신고 갱신. 대상 `users` 행은 티켓 뒤에 잡으므로 잠그지 않는다.
- 차단 제외 SQL은 `ExchangeCandidateRepository.additionalExclusions()`에 있다. 차단 조건은 `NOT EXISTS` **두 개로 나눠** 쓴다(양방향 OR로 묶으면 옵티마이저가 전체 인덱스 스캔을 고른다). 두 서브쿼리 모두 `uk_user_block_pair`(`blocker_id, blocked_id`) eq_ref로 커버링된다(EXPLAIN). 별칭 `ub`는 쓰지 않는다(`users ub`와 겹침). 조각에 `?`를 넣을 수 없다(`normalizeExclusions` 규칙). 정지 사용자 제외는 활성 제재 존재 조회(`user_sanction`, `idx_user_sanction_user`)를 쓴다.

### 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| 비참여자·없는 매칭에서 차단·신고 | 404 | `{message}` |
| 차단 상한 초과 | 422 | `{code: BLOCK_LIMIT_REACHED, message}` |
| 남의 차단 행 해제·없는 차단 행 해제 | 404 | `{message}` |
| 신고 입력 오류(`reason`·`detail`, OTHER인데 `detail` 없음) | 400 | `{reason: 메시지}` 또는 `{detail: 메시지}` |
| 같은 매칭에 OPEN 신고 있음 | 409 | `{message, code: REPORT_ALREADY_OPEN}` |
| OPEN 신고 상한 초과 | 422 | `{code: REPORT_LIMIT_REACHED, message}` |
| 이미 처리된 신고 처리 | 409 | `{message, code: REPORT_ALREADY_HANDLED}` |
| ADMIN 대상 제재 | 409 | `{message, code: CANNOT_SANCTION_ADMIN}` |
| 이미 정지 중인 대상에 제재 | 409 | `{message, code: SANCTION_ALREADY_ACTIVE}` |
| 활성이 아닌 제재 해제 | 409 | `{message, code: SANCTION_NOT_ACTIVE}` |
| 정지 계정의 모든 인증 API·로그인·재발급 | 403 | 위 '정지 계정 응답 형식' |

### 테스트
- 백엔드 전체(임시 MySQL IT 포함) 781건 통과, 실패 0·skip 0(2026-10-12). 기본 모드와 IT 모드를 나눈 수치는 기록하지 않는다.
- 신규 범위: V13 스키마 `BlockReportSchemaMysqlTest` 7건, 차단 17건(`BlockServiceTest`·`BlockMysqlTest`: 취소 범위, 차단과 reserve·unreserve·티켓 내리기·제안 경합 20라운드에서 교착 0, `cancelAllOf` 경합), 신고·제재 약 45건(`AbuseReportServiceTest`·`AdminModerationServiceTest`·`ModerationMysqlTest`·`ModerationControllerSliceTest`·`BlockControllerSliceTest`: OPEN 중복·동시 신고, resolve·dismiss 경합에서 1건만 성공, 정지 중 로그인·API·재발급 403, 해제·만료 뒤 복귀, ADMIN 거부, 채팅 근거 범위), `AccountSuspensionCheckerTest`·`AuthServiceSuspensionTest`·`CustomUserDetailsServiceTest`(정지 강제), `StompAuthChannelInterceptorTest`·`StompChatMysqlIT`(CONNECT 거부).
- 프론트 `tsc`·빌드 통과. 브라우저 수동 점검은 하지 않았다.

### 알려진 한계
- STOMP: 이용 정지는 커밋 뒤 대상의 열려 있는 STOMP 연결을 모두 닫는다(`WebSocketSessionRegistry.closeAllOf`, 1008). 차단은 연결을 닫지 않고 열린 매칭 취소로 쓰기를 막는다.
- 차단 상한 200과 신고 상한 10은 동시 요청 시 근소하게 넘을 수 있다(사용자 행을 잠그지 않음).
- 같은 대상을 동시에 제재하면 한쪽이 교착으로 503 `BUSY`가 될 수 있다(중복 제재는 생기지 않는다).
- 신고 처리 결과는 신고자에게 상태만 보인다(메모·제재 내용 숨김).
- 채팅 DTO의 상대 숫자 id(`senderId`)는 그대로 노출한다(`mine` 플래그로 바꾸지 않음).
- `OpenMatchCanceller` 주입은 `ObjectProvider`를 쓴다.

## 소셜 로그인 (V14, 2026-10-12, `feature/social-login`)

카카오·구글·네이버 로그인을 백엔드가 직접 처리한다(Authorization Code, Spring oauth2-client 미사용). 확정 규칙 요약은 CLAUDE.md '소셜 로그인 (구현됨, V14)' 절이다. 이 절은 구현 세부를 적는다.

### 구성
- `controller/SocialAuthController`(`/api/auth/social/**`, 인증 API 표 참고). 엔드포인트는 `SecurityConfig`의 `/api/auth/**` permitAll 아래에 있다.
- `service/social/`: `SocialLoginService`(사용자 결정 규칙), `SocialUserRegistrar`(가입·자리표시 이메일·비밀번호 해시), `SocialProviderClient`(token·userinfo 호출), `SocialProfileParser`(제공자별 응답 → 식별자·검증된 이메일·닉네임), `SocialPkce`, `SocialStateCookie`(HMAC 서명 쿠키), `SocialLoginCodeStore`(일회용 코드), `SocialRateLimiter`, `SocialPropertiesValidator`(기동 검증), `SocialProperties`, `SocialLoginFailure`·`SocialAuthException`(콜백 실패 코드).
- `domain/SocialAccount`(엔티티, 연결 때 INSERT·해제 때 DELETE), `domain/SocialProvider`(enum), `repository/SocialAccountRepository`, `dto/request/SocialExchangeRequest`.
- 토큰 발급은 `AuthService.issueTokens`를 재사용한다. 오류 매핑은 `GlobalExceptionHandler`.

### 흐름
1. FE가 `GET /api/auth/social/{provider}/authorize`로 이동한다. 서버는 state(32바이트)와 PKCE verifier(32바이트, 구글·카카오만)를 만들고, 서명한 쿠키 `ss_oauth`(HttpOnly, Path=`/api/auth/social`, SameSite=Lax, Max-Age 600초, Secure는 `cookie-secure`)에 `provider|state|verifier|만료`를 담아 제공자 authorize URL로 302한다.
2. 제공자가 `{backend-base-url}/api/auth/social/{provider}/callback?code&state`로 돌려보낸다. 콜백은 쿠키 서명·만료·provider 일치·state 상수시간 비교를 먼저 본다. 실패하면 코드 교환을 하지 않는다. 쿠키는 항상 삭제(Max-Age=0)한다. `error` 파라미터는 ACCESS_DENIED다.
3. code를 token 엔드포인트로 교환하고(form: grant_type, code, redirect_uri, client_id, client_secret, code_verifier; 네이버는 state 포함) access_token으로 userinfo를 호출한다.
4. 사용자 결정 후 일회용 로그인 코드를 발급해 `302 {frontend-base-url}/auth/callback#code={code}`로 보낸다.
5. FE `/auth/callback`이 프래그먼트를 읽고 URL을 정리한 뒤 `POST /api/auth/social/exchange {code}`를 한 번만 호출한다(StrictMode 이중 실행 방지). 응답은 기존 `TokenResponse`다.

### 제공자별 규칙

| 제공자 | scope | 이메일 사용 조건 | 식별자 | PKCE |
|---|---|---|---|---|
| 구글 | `openid email profile` | `email_verified`가 true | `sub` | S256 |
| 카카오 | `profile_nickname account_email` | `is_email_valid`·`is_email_verified` 둘 다 true (동의 안 하면 이메일 없음) | `id`(숫자) | S256 |
| 네이버 | 없음(앱 설정으로 결정) | 쓰지 않음(검증 여부를 알 수 없어 항상 자리표시 이메일, 2026-10-13) | `response.id` | 미지원(state 쿠키 검증으로 CSRF 방어) |

엔드포인트는 코드에 고정되어 있다. 구글 authorize `https://accounts.google.com/o/oauth2/v2/auth`, token `https://oauth2.googleapis.com/token`, userinfo `https://openidconnect.googleapis.com/v1/userinfo`. 카카오 `https://kauth.kakao.com/oauth/authorize`, `https://kauth.kakao.com/oauth/token`, `https://kapi.kakao.com/v2/user/me`. 네이버 `https://nid.naver.com/oauth2.0/authorize`, `https://nid.naver.com/oauth2.0/token`, `https://openapi.naver.com/v1/nid/me`. 설정으로 덮어쓸 수 있으나 https만 허용하고, 루프백 http만 테스트용으로 허용한다.

### 사용자 결정 규칙 (`SocialLoginService`)
1. `social_account(provider, provider_user_id)`가 있으면 그 사용자로 로그인한다(정지 검사). 이메일이 바뀌었는지는 보지 않는다.
2. 없으면 가입한다. 검증된 이메일이 `users`에 있으면 가입하지 않고 `EMAIL_EXISTS`(자동 연결 없음)다. 이메일이 없거나 미검증이면 `{provider}.{SHA-256("provider:providerUserId") 앞 16자 hex}@social.matjari.invalid`를 쓴다(`.invalid` 예약 도메인. 식별자는 대소문자를 구분하므로 항상 해시한다). 비밀번호는 무작위 32바이트의 BCrypt 해시라 비밀번호 로그인은 불가다. 비밀번호 로그인 경로(`AuthService.login`)는 그대로 둔다.
3. 닉네임은 `AuthInputNormalizer.normalizeNickname`으로 정리하고 보이지 않는 문자를 뺀 뒤 2~20자로 맞춘다(소셜 닉네임은 UTF-16 길이 기준으로 자른다). 2자 미만이면 `{제공자 한글명}사용자` + 숫자 4자리 랜덤.
4. `User.create`(role USER)와 `social_account` 1행을 한 트랜잭션에서 만든다. 같은 소셜 계정의 동시 첫 로그인이 UNIQUE에 걸리면 다시 조회해 기존 사용자로 진행한다(1회 재시도). 이메일 UNIQUE 경합은 `EMAIL_EXISTS`다.
5. 가입 후 정지·차단·신고 규칙은 일반 사용자와 같다. 정지 검사는 callback·exchange·요청마다 한다.

### 오류 코드
- socialError(302 쿼리, 프런트 매핑 키): `INVALID_STATE`, `ACCESS_DENIED`, `PROVIDER_ERROR`, `EMAIL_EXISTS`, `ACCOUNT_SUSPENDED`(+`until`), `SERVER_BUSY`(일회용 코드 보관 상한 1만 건 초과).
- REST 직접 호출: `SOCIAL_PROVIDER_DISABLED`(404), `INVALID_LOGIN_CODE`(400), `SOCIAL_RATE_LIMITED`(429), 정지 403 `ACCOUNT_SUSPENDED`.
- 콜백은 미분류 예외를 포함해 JSON을 내지 않고 항상 로그인 화면 302와 쿠키 삭제로 끝난다.

### 설정 (`matjari.social.*`)
- `{google|kakao|naver}.client-id`·`client-secret`(환경변수 `MATJARI_SOCIAL_GOOGLE_CLIENT_ID`·`..._SECRET`, `..._KAKAO_...`, `..._NAVER_...`), `authorize-uri`·`token-uri`·`userinfo-uri`(기본값은 위 고정 주소).
- `backend-base-url`(`MATJARI_SOCIAL_BACKEND_BASE_URL`, 기본 `http://localhost:8080`): 리다이렉트 URI = `{backend-base-url}/api/auth/social/{provider}/callback`을 각 개발자 콘솔에 등록한다.
- `frontend-base-url`(`MATJARI_SOCIAL_FRONTEND_BASE_URL`, 기본 `http://localhost:5173`): `matjari.websocket.allowed-origins`에 포함돼야 하며 기동 때 검증한다(위반이면 기동 실패).
- `state-secret`(`MATJARI_SOCIAL_STATE_SECRET`, 32바이트 이상. 기본값은 개발용이라 운영에서는 거부), `cookie-secure`(`MATJARI_SOCIAL_COOKIE_SECURE`, 기본 false, 운영 true 요구).
- client-id가 비어 있으면 그 제공자는 비활성이다. 모두 비면 소셜 기능과 기동 검증이 꺼진다.
- `.env.example`·`docker-compose.yml`에 `SOCIAL_*` 변수를 추가했다(값이 비면 비활성).
- 프록시 뒤에 배포하면 `server.forward-headers-strategy` 설정이 필요하다. 속도 제한은 `getRemoteAddr()`만 믿는다.

### 보안 요구와 구현
- `Cache-Control: no-store`를 authorize·callback·exchange 응답에 붙인다.
- 아웃바운드 HTTP: 연결 3초·응답 5초, 리다이렉트 비추종, 본문 64 KiB 상한, Content-Encoding 거부, 동시 8개 상한. 사용자 입력 URL은 요청하지 않는다. 실패(타임아웃·4xx·5xx·JSON 파싱 불가)는 `PROVIDER_ERROR`로 리다이렉트한다.
- 로그에는 제공자·오류 종류만 남긴다. secret·토큰·code·이메일은 남기지 않는다.
- 리다이렉트 목적지는 설정 `frontend-base-url` 고정이다. 요청 파라미터로 목적지를 받지 않는다(오픈 리다이렉트 금지).
- 일회용 코드는 32바이트 랜덤, 서버 메모리, TTL 60초, 꺼내면 즉시 삭제, 보관 상한 1만 건. exchange 때 정지를 다시 검사한다.
- 속도 제한: authorize·callback·exchange 버킷을 나눠 IP당 분당 30회(슬라이딩 윈도우, 메모리). authorize·exchange 초과는 429 `SOCIAL_RATE_LIMITED`, callback 초과는 302 `{frontend-base-url}/login?socialError=SERVER_BUSY`다.
- 예외: 꺼져 있거나 알 수 없는 제공자의 callback은 항상 302가 아니라 404 JSON `SOCIAL_PROVIDER_DISABLED`다(그 밖의 callback 응답은 모두 302, JSON 없음).
- Fable 검증 결과: 높음 없음. 중간 4건(콜백 예외 JSON 노출, state 쿠키 재사용·콜백 무제한, 프런트 `__proto__` 오류 코드 크래시, 프록시 IP 공유)과 낮은 등급 일부는 수정했다.

### 테스트
- 제공자 스텁: JDK `com.sun.net.httpserver.HttpServer`(무작위 포트)로 token·userinfo를 흉내 낸다(성공, 4xx, 잘못된 JSON, 느린 응답). 설정으로 엔드포인트를 스텁 URL로 덮어쓴다. authorize는 302 Location의 state·code_challenge(S256)·redirect_uri·scope·client_id를 검증한다.
- 케이스: 신규 가입(이메일 있음·없음·미검증), 재로그인(이메일이 바뀌어도), EMAIL_EXISTS(사용자·social_account 증가 0), 정지 사용자(callback·exchange), state 쿠키 누락·변조·만료·provider 불일치, 코드 재사용·만료, 비활성 제공자, `error=access_denied`, 동시 첫 로그인 경합, 비밀번호 로그인 불가, 오픈 리다이렉트 불가, 속도 제한, 로그·응답에 secret 미노출.
- MySQL IT는 임시 컨테이너(고유 이름·포트, DB 이름 `_it`)에서만 돌렸다. 개발 DB와 `bootRun`은 쓰지 않았다.
- 브라우저(Playwright, 스텁 제공자): 신규 가입, 재로그인, EMAIL_EXISTS, 이메일 없음, 정지 계정, 위조 콜백, 코드 없는 `/auth/callback`.

### 알려진 한계
- 실제 카카오·구글·네이버 키로 검증하지 못했다. 카카오 PKCE·`is_email_verified`, 네이버 client_secret 필수 여부 등은 실연동 때 확인한다. 네이버 client_secret 필수 검증은 없다(런타임 PROVIDER_ERROR).
- 로그인 코드·속도 제한·사용된 state는 메모리다(서버 한 대 기준). 재시작하면 코드가 사라진다(허용).
- `__Host-` 쿠키 접두를 쓰지 않는다(Path 설계 때문).
- 일반 가입에 이메일 인증이 없어 타인 이메일을 먼저 쓰면 그 사람의 소셜 로그인이 EMAIL_EXISTS로 막힌다(기존 한계).
- 기존 계정에 소셜을 붙이는 연결은 마이페이지(V16 절)에서 로그인한 본인이 명시적으로 할 때만 가능하다. 본문 정체 워치독 테스트가 없다.

## 프로필 수정·소셜 계정 연결 (V16, 2026-10-13, `feature/profile-edit`)

설계 근거는 [프로필 수정·소셜 계정 연결 설계 근거](../../wiki/decisions/profile-edit-design.md). 확정 규칙의 기준은 루트 `CLAUDE.md`다.

### API (모두 로그인 필요)

| Method | Path | 설명 |
|---|---|---|
| PATCH | /api/users/me | `{nickname}` 닉네임 수정. 가입과 같은 규칙(JS trim 뒤 2~20자, 제어·제로폭·채움 문자 거부), 중복 허용, 같은 값이면 멱등 200, 규칙 위반은 400 필드 오류. 응답은 `UserResponse` |
| GET | /api/users/me | 응답에 `emailPlaceholder`(소셜 가입 자리표시 이메일이면 true, 이때 `email`은 null)와 `passwordLoginEnabled`를 더했다 |
| GET | /api/users/me/social | `{accounts:[{provider, linkedAt}], providers:[활성 제공자], canUnlink}`. `canUnlink` = `passwordLoginEnabled` 또는 연결 2개 이상 |
| POST | /api/users/me/social/{provider}/link | 연결 시작. `{authorizeUrl}`(`/api/auth/social/{provider}/authorize?link={ticket}`, 상대 경로)을 준다. 티켓은 (사용자, 제공자)에 묶인 32바이트 1회용(60초, 서버 메모리, `SocialLinkTicketStore`). 이미 연결돼 있으면 409 `SOCIAL_ALREADY_LINKED`, 비활성 제공자면 404 `SOCIAL_PROVIDER_DISABLED`, 사용자당 분당 10회(`matjari.social.link-per-minute`) 초과는 429 `SOCIAL_RATE_LIMITED` |
| POST | /api/users/me/social/confirm | `{code}` 연결 확정(아래 흐름). 200 `{provider}` |
| DELETE | /api/users/me/social/{provider} | 연결 해제. 204, 연결이 없으면 멱등. 마지막 로그인 수단이면 409 `LAST_LOGIN_METHOD`. 제공자가 비활성이어도 해제 가능 |

### 연결 흐름 (2단계 확정)

1. `POST .../link`로 티켓을 받고 `authorizeUrl`로 이동한다. authorize가 티켓을 1회 소비해 서명 쿠키 `ss_oauth`에 `linkUserId`를 담는다. 쿠키 payload는 `provider|state|verifier|linkUserId|만료` 5칸이고, 예전 4칸 쿠키는 무효다. 티켓이 없거나 만료·재사용됐거나 제공자가 맞지 않으면 쿠키 없이 `302 {frontend}/me?socialLinkError=INVALID_LINK`로 보낸다.
2. 제공자 콜백은 **DB에 저장하지 않는다.** 쿠키·state 검증과 제공자 호출을 마친 뒤 (linkUserId, provider, providerUserId)를 담은 일회용 **연결 확정 코드**(32바이트, 서버 메모리, 60초, `SocialLinkConfirmStore`)를 발급해 `302 {frontend}/me#linkCode={code}`로 보낸다. 실패는 `{frontend}/me?socialLinkError=` 뒤에 `INVALID_STATE`·`ACCESS_DENIED`·`PROVIDER_ERROR`·`SERVER_BUSY`·`ACCOUNT_SUSPENDED`(&until)를 붙인다. 쿠키 검증 전 실패(쿠키 없음·변조·state 불일치·콜백 IP 제한)도 쿠키의 linkUserId 칸이 양의 정수면 `/me`로, 아니면 `/login?socialError=`로 보낸다. 이 값은 목적지를 고를 뿐이고 보안 판단에는 쓰지 않는다.
3. 프런트가 `/me#linkCode`를 읽어 주소를 즉시 지우고, Bearer 토큰으로 `POST /api/users/me/social/confirm {code}`를 호출한다. 서버는 코드를 **먼저 소비한다**(다른 사용자의 코드여도 소진). 없음·만료·다른 사용자의 코드는 400 `INVALID_LINK_CODE`다. 일치하면 users 행을 FOR UPDATE로 잡고 정지 여부를 다시 검사(403 `ACCOUNT_SUSPENDED`)한 뒤, 같은 제공자가 이미 있으면 409 `SOCIAL_ALREADY_LINKED`, 그 계정이 다른 사용자에게 있으면 409 `SOCIAL_LINKED_OTHER`를 준다(유니크 위반의 제약 이름으로 구분). 통과하면 저장하고 200을 준다. 확정은 사용자당 분당 10회(버킷 `lc:{userId}`)이며, 제한에 걸리면 코드를 소비하지 않는다.
4. 이메일 일치는 보지 않는다. 로그인한 본인이 명시적으로 연결하므로 자동 연결이 아니다.

### 해제와 잠금

- 확정과 해제는 같은 순서를 쓴다: users 행 FOR UPDATE → 판단 → `social_account` INSERT/DELETE. 마지막 로그인 수단 = `password_login_enabled = false`이고 연결이 이것 하나뿐인 상태다.
- 티켓·요청·매칭은 잠그지 않는다.

### 보안 요구

- 연결 시작·확정·해제 응답은 모두 `Cache-Control: no-store`다. 티켓·확정 코드·이메일은 로그에 남기지 않는다.
- authorize는 permitAll이라 연결 티켓을 쓰는 쪽이 발급자와 같은 브라우저인지 서버가 알 수 없다. 그래서 저장은 Bearer 세션이 하는 확정 API에서만 한다. 피해자의 JWT로 공격자의 확정 코드를 제출하면 400이다.

### 테스트

- 실제 MySQL IT `SocialLinkMysqlTest`(환경변수 `MATJARI_IT_*`가 있을 때만): 동시 해제 대 해제, 같은 사용자 동시 확정(1건만 성공), 서로 다른 사용자가 같은 계정을 확정(1건 성공, 나머지 `SOCIAL_LINKED_OTHER`), 다른 사용자의 코드 확정(400), 정지 사용자 등.
- 기본 `./gradlew test`와 임시 MySQL IT를 포함해 전체 통과했다(실행 수는 기록하지 않는다).

### 알려진 한계

- 실제 카카오·구글·네이버 키로 미검증이다(스텁 제공자 기준).
- 연결 티켓·확정 코드·속도 제한은 메모리다(단일 인스턴스). 저장소가 포화되면 60초간 `SERVER_BUSY`다.
- 같은 이름 쿠키(`ss_oauth`) 하나를 쓰므로, 탭 A에서 연결을 시작한 뒤 탭 B에서 소셜 로그인을 시작하면 A의 콜백이 `INVALID_STATE`가 된다.
- 티켓이 URL 쿼리에 실린다(1회용·60초, 사용 뒤에는 무가치).
- JWT 클레임에 자리표시 이메일 원문이 남는다(본인 토큰이며 화면에는 나오지 않는다).
- 비밀번호·이메일 변경은 없다. 소셜 가입자는 비밀번호를 설정할 수 없다.

## 회원탈퇴·개인정보처리방침 (V17, 2026-10-13, `feature/withdraw`)

설계 근거·보관 기간 조사·한계 전체는 [회원탈퇴·개인정보처리방침 설계 근거](../../wiki/decisions/withdraw-privacy-design.md). 확정 규칙의 기준은 루트 `CLAUDE.md`다.

### API

| 메서드 | 경로 | 설명 |
|---|---|---|
| DELETE | /api/users/me | 회원 탈퇴(로그인 필요). 본문 `{password?, confirmText?, reauthCode?}`. 성공 204, 기존 토큰은 즉시 무효 |
| POST | /api/users/me/social/{provider}/reauth | 소셜 전용 계정의 탈퇴 재인증 시작. 이미 연결된 제공자만(아니면 409 `SOCIAL_NOT_LINKED`, 꺼진 제공자 404, 분당 10회). 응답은 연결 시작과 같은 `{authorizeUrl}` |

- 비밀번호 로그인 계정(`password_login_enabled`=1): `password` 필수. BCrypt로 검사하며 72바이트 초과·빈 값·불일치는 400 `비밀번호가 올바르지 않습니다.`.
- 소셜 전용 계정: `confirmText`가 정확히 `탈퇴합니다`여야 한다(아니면 400, 응답 `{confirmText}`).
- **소셜 재인증(`reauthCode`)**: 연결된 제공자 중 지금 켜져 있는 것이 하나라도 있으면 `reauthCode`도 있어야 한다. 액세스 토큰만 훔친 사람이 되돌릴 수 없는 탈퇴를 하지 못하게 하려는 장치다. 흐름은 연결 모드를 재사용한다: `POST /api/users/me/social/{provider}/reauth`가 링크 티켓을 발급하고(이미 연결된 제공자만), 제공자 인증 뒤 콜백이 `/me#linkCode=…`(연결 확정 코드, 60초)로 돌려보낸다. 이 코드를 연결 확정이 아니라 탈퇴 요청의 `reauthCode`로 낸다. 서버는 `confirmText`를 먼저 확인하고, 코드를 **소비한 뒤**(불일치여도 소진) 코드의 사용자 = 요청 사용자이고 `social_account(user, provider, providerUserId)`가 실제로 있는지 본다. 실패는 모두 같은 400 `{reauthCode}`다(확인 문구가 틀리면 코드는 소비하지 않는다). 연결된 제공자가 모두 꺼져 있으면 재인증 수단이 없으므로 확인 문구만으로 허용한다.
- 테스트: `WithdrawServiceTest` 6건 추가(코드 필수·성공·다른 사용자/미연결 계정 코드·문구 오류 때 코드 미소비·제공자 꺼짐·비밀번호 계정), `WithdrawMysqlTest` 재인증 시나리오 1건, `SocialAccountServiceTest`·`UserSocialControllerSliceTest` 재인증 시작 각 3건·2건.
- ADMIN: 409 `ADMIN_CANNOT_WITHDRAW`. 역할을 DB에서 USER로 내린 뒤 탈퇴한다.
- 사용자당 분당 5회(성공·실패 모두 센다, 서비스 내부 메모리 슬라이딩 윈도). 초과 429 `WITHDRAW_RATE_LIMITED`(`retryAfterSeconds` + `Retry-After` 헤더).
- 이미 탈퇴한 계정(동시 탈퇴의 뒤쪽 포함)은 401.
- 이용 정지 중인 사용자는 인증 필터에서 403 `ACCOUNT_SUSPENDED`로 막힌다. 정책 미확정(한계 1).

### 처리 순서 (`WithdrawService`, READ_COMMITTED 한 트랜잭션)

1. PENDING 좌석 인증 요청을 거절한다(처리자는 탈퇴자 본인, 메모 "회원 탈퇴로 자동 거절되었습니다."). 사진은 커밋 뒤 삭제한다.
2. `OpenMatchCanceller.cancelAllOf`로 열린 매칭(CHATTING·RESERVED)을 시스템 취소하고 예약 잠금을 푼다.
3. OPEN 요청을 CLOSED로, 활성 티켓을 INACTIVE로 바꾼다(DELETED 요청은 그대로).
4. users 행을 FOR UPDATE로 잡고, 이미 탈퇴했으면 401. 그 뒤 1~3을 한 번 더 한다(2차 정리: 1차 정리 뒤 users 잠금 전에 생긴 활성 티켓 등).
5. `social_account`를 전부 삭제하고 users를 익명화한다. 이메일 `withdrawn.{id}@withdrawn.matjari.invalid`, 닉네임 `탈퇴한 회원`, 무작위 BCrypt, `password_login_enabled`=0, `withdrawn_at`=KST 현재 시각. role은 유지한다.
6. 수신자 알림을 삭제한다(500개씩 id 목록으로, 트랜잭션 맨 끝). 상대에게 가는 알림은 없다.
7. 커밋 뒤 `WebSocketSessionRegistry.closeAllOf`로 열린 STOMP 연결을 끊는다(실패는 삼킨다).

**보존**: 채팅·읽음 상태·교환 이력·차단·신고·제재·PENDING 공연 수정 제안은 지우지 않는다(FK 유지). 닉네임은 users에서 읽으므로 상대·관리자 화면에 '탈퇴한 회원'으로 나온다. 교환 이력 화면에는 상대 정보가 없다.

### 잠금 순서와 동시성

- 순서: 인증 요청(id↑) → 티켓(id↑) → 요청 → 매칭(id↑) → users → 알림. **users를 먼저 잡지 않는다.**
- 설계 초안의 'users 먼저'는 폐기했다. 제안은 티켓을 잠근 뒤 `exchange_match`를 INSERT하면서 users 행에 FK 공유 잠금을 요청하므로, 탈퇴가 users를 X로 먼저 잡으면 교착한다. 이용 정지도 같은 이유로 users 잠금 없이 취소한다.
- users 잠금을 잡은 뒤에는 새 활성 티켓·PENDING 인증·소셜 연결이 생길 수 없다. 티켓 등록·인증 요청·공연 수정 제안·소셜 연결은 모두 users FOR UPDATE로 시작하며 탈퇴 여부를 확인하기 때문이다. 2차 정리는 1차 정리와 users 잠금 사이에 생긴 것을 마저 정리한다.

### 인증 차단

- `CustomUserDetailsService`의 `loadUserById`·`loadUserByUsername`이 탈퇴 계정을 `UsernameNotFoundException`으로 거절한다 → 기존 액세스 토큰은 401, STOMP CONNECT는 거부된다.
- 로그인 400(기존 로그인 실패 응답과 같다), refresh 400, 소셜 exchange 400 `INVALID_LOGIN_CODE`, 소셜 연결 확정 400 `INVALID_LINK_CODE`, 소셜 연결 해제 401.
- 같은 이메일·같은 소셜 계정으로 재가입할 수 있다(원래 이메일이 비워졌고 social_account가 삭제됐으므로). 소셜 콜백의 `loginOrSignup`은 social_account가 없어 새 가입으로 간다.
- 관리자가 탈퇴한 사용자를 제재하려 하면 409 `TARGET_WITHDRAWN`(`AdminModerationService`).

### 테스트

- 단위·슬라이스: `WithdrawServiceTest`, `CustomUserDetailsServiceTest`, `UserControllerSliceTest`.
- 실제 MySQL IT `WithdrawMysqlTest`(환경변수 `MATJARI_IT_*`가 있을 때만): 탈퇴 vs 새 제안, 예약 수락·철회, 교환 수락, 탈퇴×탈퇴, 소셜 연결 확정, 티켓 등록 7건(15라운드). 불변식: 활성 티켓 0, 열린 매칭 0, OPEN 요청 0, 잠금 행 = 2×RESERVED, social_account 0, 알림 0, PENDING 인증 0, 익명화 이메일 형식.
- 기본 `./gradlew test`와 임시 MySQL IT를 포함해 전체 통과했다(실패·skip 0).
- Fable 검증: 높음 없음. 중간 2건은 코드 버그가 아니라 정책 사항이라 한계로 남겼다. 낮음 지적은 반영했다(loadUserByUsername 탈퇴 검사, 탈퇴 대상 제재 409, 방침 문구 보정).

### 알려진 한계

1. 이용 정지 중 탈퇴는 허용한다(`JwtAuthenticationFilter` 정지 예외에 `DELETE /api/users/me` 추가, 2026-10-13 결정). 정지 중에도 비밀번호 로그인이 맞으면 403 본문의 탈퇴 전용 토큰(`withdrawToken`)으로 탈퇴할 수 있다. 소셜 전용 정지 계정은 이 경로가 없어 방침 §8의 문의처로 요청한다.
2. 소셜 전용 계정은 연결된 소셜 계정으로 다시 인증한 `reauthCode`가 있어야 탈퇴한다(연결된 제공자가 모두 꺼져 있으면 확인 문구만). 재인증 코드는 연결 확정 코드와 같은 저장소·형식이라 목적 표지가 없고, 제공자의 "방금 인증"은 60초 코드 수명으로 갈음한다.
3. 인증 사진 삭제는 커밋 뒤 1회 시도하고 실패하면 로그만 남기지만, 정리 작업(`SeatClaimImageCleanupService`, 기본 1시간마다)이 처리됐는데 남은 사진과 고아 파일을 지운다(PENDING 사진은 지우지 않음). 정리 사이에도 조회는 불가하다.
4. 교착 창 2개는 감지기 의존이다(MySQL 교착 시 503, 재시도). ①본인이 두 탭에서 요청 수정·삭제와 탈퇴를 동시에 실행(요청→매칭 vs 티켓→매칭→요청) ②1차 정리와 users 잠금 사이에 본인이 새 티켓을 등록하고 상대가 그 티켓에 제안하는 밀리초 창. 불변식은 깨지지 않는다.
5. 보관 기간의 법적 근거는 확정되지 않았다. 자동 파기 스케줄은 없다(방침은 '서비스 운영 기간 동안 보관, 종료 시 파기').
6. 가입 시 동의 기록과 14세 미만 확인 수단은 없다(문구만).
7. JWT 클레임에 원래 이메일이 남지만 본인 브라우저에만 있고 로그아웃 때 지워진다. 인증 요청 거절 처리자 칸에는 '탈퇴한 회원'이 표시된다.
8. 탈퇴 속도 제한은 서비스 내부 메모리다(재시작 시 초기화, 단일 인스턴스).

## AI 공연 정보 추출 (2026-10-18, `feature/ai-extract-backend`, V21)

공연 등록 2단계의 'AI로 회차·구역 읽기'용 API다. 링크의 상세 정보에서 회차(날짜·시각)와 좌석 구역(이름·모양)을 읽어 돌려주며, **서버는 아무것도 저장하지 않는다.** 사용자가 확인·수정한 뒤 공연 등록 요청(`zones`·`seatmapAspect`)에 담아 보낼 때만 저장된다. 구현은 `service/aiextract/`(`DetailExtractionService` 오케스트레이션, `AnthropicDetailExtractor`, `AiUsageLimiter`, `DetailImageFetcher`, `ImageSlicer`)다.

### API

- `POST /api/performances/ai-extract` (로그인 필요). 요청 `{sourceUrl}`(필수, 2000자 이하).
- 응답: `{sessions[{date, time}], sessionsSource, zones[{name, shapePolygons}], seatmapAspect, aiEnabled, aiError, cached, truncatedSlices}`.
  - `sessionsSource`: `TEXT_RULE`(본문 규칙으로 읽음, 우선) / `AI` / `NONE`.
  - `aiEnabled`: AI를 실제로 부를 수 있었는지. `aiError`: AI 호출이 실패해 규칙 결과만 돌려줬는지. `cached`: 캐시에서 준 결과인지. `truncatedSlices`: 조각 상한(`max-slices`) 때문에 뒤쪽 이미지를 읽지 못했는지.
- 오류: 400 형식 오류 또는 `UNSUPPORTED_SITE`(YES24·티켓링크·멜론티켓 링크만 읽는다), 429 `AI_RATE_LIMITED`(+`Retry-After`), 503 `PAGE_FETCH_FAILED`(페이지를 읽지 못함), 503 `AI_UNAVAILABLE`(AI도 규칙 결과도 없음).

### 흐름과 안전장치

- **사용자 URL을 요청하지 않는다.** `SourceKeyResolver`로 사이트와 숫자 ID만 뽑고 `PerformancePreviewService.fetchPage`가 고정 템플릿 URL로 받는다(링크 자동 입력과 같은 SSRF 방어).
- 상세 이미지는 `matjari.ai.image-hosts`에 **정확히 일치하는 https 호스트**만 받는다(기본 `cdnticket.melon.co.kr`). AI 호출은 멜론 상세 이미지가 있을 때만 한다. 이미지가 없으면 본문 규칙 결과만 돌려주고 AI를 부르지 않는다.
- 이미지는 조각으로 나눠(폭 1000px 이하로 줄인 뒤 `slice-height`·`slice-overlap`) 1차 AI(회차·구역 이름·좌석표 위치), 필요할 때 2차 AI(구역 윤곽)로 보낸다. 같은 이름의 구역은 한 구역으로 합친다(`DetailExtractionService.mergeZones`).
- **호출 제한**(메모리 기반, 재시작하면 초기화, 단일 인스턴스 기준): 사용자당 하루(KST) 2회, 전체 하루 40회, 동시 2개(대기 없이 거절, 초과는 429). 캐시 적중과 AI를 부르지 않은 요청은 횟수에 세지 않는다.
- **캐시**: 성공 결과만 24시간(최대 200개) 보관한다.
- AI가 꺼져 있거나 키가 없으면 규칙 결과만 돌려주고 `aiEnabled=false`다. AI 호출이 실패하면 규칙 결과와 `aiError=true`를 돌려준다.
- 로그에는 개수·소요 시간만 남기고 이미지·본문·키는 남기지 않는다. 원본 이미지는 DB에 저장하지 않는다.

### 설정 (`matjari.ai.*`, `application.yml`)

| 설정 키 | 환경변수 | 기본값 | 뜻 |
|---|---|---|---|
| `enabled` | `MATJARI_AI_ENABLED` | `true` | `false`면 AI를 부르지 않는다 |
| `model` | `MATJARI_AI_MODEL` | `claude-haiku-5-5` | 모델 이름(날짜 접미사 없음) |
| `request-timeout-seconds` | `MATJARI_AI_REQUEST_TIMEOUT_SECONDS` | `60` | AI 호출 제한 시간(재시도 1회) |
| `daily-per-user` | `MATJARI_AI_DAILY_PER_USER` | `2` | 사용자당 하루(KST) 호출 상한 |
| `daily-global` | `MATJARI_AI_DAILY_GLOBAL` | `40` | 전체 하루 호출 상한 |
| `max-concurrent` | `MATJARI_AI_MAX_CONCURRENT` | `2` | 동시 호출 수 |
| `cache-ttl-hours` | `MATJARI_AI_CACHE_TTL_HOURS` | `24` | 성공 결과 캐시 유지 시간 |
| `cache-max-entries` | `MATJARI_AI_CACHE_MAX_ENTRIES` | `200` | 캐시 항목 상한 |
| `image-hosts` | `MATJARI_AI_IMAGE_HOSTS` | `cdnticket.melon.co.kr` | 상세 이미지 허용 호스트(정확히 일치, https) |
| `slice-height` | `MATJARI_AI_SLICE_HEIGHT` | `1400` | 조각 높이(px) |
| `slice-overlap` | `MATJARI_AI_SLICE_OVERLAP` | `0.2` | 조각 겹침 비율 |
| `max-slices` | `MATJARI_AI_MAX_SLICES` | `24` | 조각 개수 상한 |

- yml에 없고 코드 기본값만 있는 항목: 이미지 후보 4개(`maxCandidates`), 상세 이미지 3장(`maxDetailImages`), 이미지 한 장 10 MiB, 조각 폭 1000px, 조각 결과 높이 2400px, 1차 AI 출력 4000 토큰, 2차 AI 출력 6000 토큰, 이미지 수집 연결 5초·읽기 15초.
- **`ANTHROPIC_API_KEY`**는 환경변수 전용이다(설정 파일에 두지 않는다). 없으면 AI가 꺼진 것으로 동작한다. `.env.example`에 빈 값으로 들어 있다.

### 알려진 한계

- 제한·캐시가 메모리 기반이라 재시작하면 초기화되고, 여러 인스턴스에서는 제한이 합쳐지지 않는다.
- AI 호출은 멜론 상세 이미지에만 한다. YES24·티켓링크는 본문 규칙 결과만 준다.
- 이 절은 코드와 설정 기준이다. 실제 Anthropic 호출 결과와 읽기 정확도는 확인하지 않았다.

## 로컬 환경변수 (.env)

`./gradlew bootRun`으로 로컬 실행 시, `backend/.env` 파일이 있으면 `build.gradle`의
`bootRun` 태스크가 자동으로 읽어서 환경변수로 주입한다 (Docker Compose처럼 `.env`를
그냥 쓸 수 있게 하기 위함). 최초 1회:

```bash
cp .env.example .env
# .env를 열어 JWT_SECRET 등 실제 값으로 채운다 (openssl rand -base64 32로 생성)
./gradlew bootRun
```

`.env`는 `.gitignore`에 등록되어 있어 커밋되지 않는다.
소셜 로그인 설정은 `.env.example`의 `SOCIAL_*` 변수로 주입한다(client-id가 비어 있으면 그 제공자는 비활성).

## 후속 메모 (티켓 등록)
- V4 후보 조회·스케줄러에서 회차 마감(당일 끝)이 지난 회차의 티켓은 제외하거나 비활성 처리한다.
- `POST /api/tickets` 레이트 리밋을 도입할 때 함께 포함한다.
- (V4 반영 완료) 티켓 내리기는 티켓 행 `FOR UPDATE`(`findByIdForUpdate`)로 바뀌었고 그 티켓의 교환 요청을 CLOSED로 닫는다. 여러 티켓을 잠그는 매칭·예약 단계에서는 ticket id 오름차순으로 잠근다(데드락 방지).
- 내릴 때 '교환 요청이 있으면 409' 대신 요청을 CLOSED로 닫는 쪽으로 구현했다(설계 1.2: CLOSED = 티켓 내림). (V5 반영 완료) 예약 잠금이 있으면 `TicketService.ensureCanDeactivate`가 409 `TICKET_RESERVED`로 막고, 내릴 때 그 티켓의 CHATTING 매칭은 시스템 취소한다.

## 후속 메모 (교환 희망 조건)
- (V5 반영 완료) `ExchangeRequestService.ensureNoActiveProposal`이 열린 매칭이 있으면 409 `ACTIVE_MATCH_EXISTS`를 던진다(수정·삭제 공통).
- 후보 조회는 `exchange_want_seat` PK(request_id, 구역, 열, 번)와 `uk_ticket_active_seat`를 쓴다(설계 2절 SQL). 요청 CLOSED/티켓 INACTIVE는 후보 SQL의 `status='OPEN'`·`active_flag=1`로 걸러진다.
- 펼친 좌석 INSERT는 500행씩 다중 행 `INSERT`(JdbcTemplate)다. 상한(5,000) 요청의 등록·수정은 로컬 MySQL 기준 약 150~200ms였다.
- 요청 목록의 `ranges` 열·번은 정규화 값이다(원문 표기 복원이 필요하면 V5에서 label 컬럼을 추가한다).
- 알려진 한계(L4): 수정(PATCH)은 범위·좌석·회차를 전부 지우고 다시 만드는 방식이라 상한 근처(5,000석) 요청은 매번 5,000행을 다시 쓴다(약 150ms). 희망 좌석에는 회차가 없어 "같은 자리의 다른 회차만 원하고 같은 회차의 같은 자리는 원하지 않는" 구분은 할 수 없다. 두 한계 모두 현재 설계(1.3~1.5)의 결과다.

## 후속 메모 (매칭 후보 조회)
- **반영 상태: 같은 쌍 열린 매칭·예약 잠금 제외(V5)와 차단 양방향 제외·정지 사용자 티켓 제외(V13, 2026-10-12)는 모두 적용 완료다.** 아래 조각은 설계 당시 가안이며, V13 구현은 차단 조건을 OR 한 개가 아니라 NOT EXISTS 두 개로 나눠 쓴다(OR로 묶으면 전체 인덱스 스캔, EXPLAIN 근거는 '차단·신고·이용 정지' 절). 구현은 `additionalExclusions()`에 있다. 확장 지점은 `ExchangeCandidateRepository.additionalExclusions()` 한 곳이며, 후보 SELECT와 COUNT 양쪽에 같이 붙는다. 아래 조각을 `AND`로 이어 붙이면 된다(별칭 a=내 요청, b=상대 요청, ta=내 티켓, tb=상대 티켓, 파라미터 없음).

```sql
-- 차단 (양방향)
AND NOT EXISTS (SELECT 1 FROM user_block ub2
                WHERE (ub2.blocker_id = ta.user_id AND ub2.blocked_id = tb.user_id)
                   OR (ub2.blocker_id = tb.user_id AND ub2.blocked_id = ta.user_id))
-- 이미 같은 요청 쌍으로 열린 채팅
AND NOT EXISTS (SELECT 1 FROM exchange_match mo
                WHERE mo.request_low_id  = LEAST(a.id, b.id)
                  AND mo.request_high_id = GREATEST(a.id, b.id)
                  AND mo.open_flag = 1)
-- 상대 티켓이 예약 잠금 (예약이 취소되면 잠금이 풀려 복귀)
AND NOT EXISTS (SELECT 1 FROM exchange_ticket_lock l WHERE l.ticket_id = tb.id)
```
  주의: `users ub` 별칭이 이미 쓰이므로 차단 서브쿼리 별칭은 `ub2`다. 지시문의 '진행 중 제안이 있는 요청 제외'는 확정 설계(설계 2절)에 없다. 한 요청에 채팅이 여러 개 동시에 열릴 수 있으므로 상대 요청에 다른 채팅이 있다는 이유로는 빼지 않고, 같은 쌍의 열린 채팅과 잠금만 뺀다.
  내 티켓이 예약 잠금이면 후보 조회 자체를 막는 검사(`ExchangeCandidateService`, 422 `TICKET_LOCKED`)는 적용 완료. 신고 연동: 신고만으로는 후보에서 빼지 않는다(자동 차단·자동 제재 없음, V13).
- **STRAIGHT_JOIN 유지 (근거와 트레이드오프).** 조인 순서를 내 요청 -> 내 희망 회차·좌석 -> 상대 티켓 -> 상대 요청 -> 상대 희망 회차·좌석으로 고정한다.
  - 근거: 힌트가 없으면 요청이 적을 때(100~1,000건) 옵티마이저가 `exchange_request`(상대 요청)를 `type=ALL`로 전체 스캔하며 시작했다. 그러면 작업량이 전체 요청 수에 비례한다. 고정하면 작업량이 내 희망 좌석 수 x 희망 회차 수에 비례하고, 전체 요청 수에는 거의 영향을 받지 않는다.
  - 트레이드오프: 희망 좌석이 아주 많은 요청은 고정 순서가 약간 느리다(희망 2,400석, 요청 1,000건: DB 시간 12ms -> 27ms). 희망 좌석이 적은 요청은 8ms -> 1ms로 빨라진다. 통계가 바뀌어도 계획이 흔들리지 않는다는 점을 택했다.
  - EXPLAIN 요약(요청 4,001건 / 희망 좌석 200만 행 / 내 희망 5,000석): a·ta·psa const, wsa `ref`(PK), psb eq_ref, wa `ref`(PK 앞부분, Using index), tb eq_ref(`uk_ticket_active_seat`), b eq_ref(`uk_exchange_request_ticket`), wsb·wb eq_ref(PK, Using index), ub eq_ref(PK). `type=ALL` 없음. 정렬은 결과 집합의 filesort뿐.
  - **keyset 페이징으로 바꿀 기준(재측정 후 판단):** ① 후보 요청의 서버 시간(DB)이 중앙값 300ms를 넘거나 p95가 1초를 넘을 때, ② 정상 사용자의 후보가 1만 건을 넘어 deep page(OFFSET)가 흔해질 때, ③ 희망 좌석 상한(5,000석)을 올릴 때. **전환 완료(2026-10-13): 후보 화면은 keyset 조각(`/candidates/scroll`)을 쓴다. 기준 수치와 무관하게 호환용 `page` 엔드포인트를 남겨 두었다.** 전환 시 정렬 키(priority, created_at, id)를 커서로 쓰고 총계는 생략하거나 근사치로 바꾼다.
- 인덱스는 V4로 충분하다(새 마이그레이션 불필요). 역조회 `exchange_want_seat (zone_key, row_key, col_key)`는 지금 쓰지 않는다.
- **총계(COUNT)** 는 별도 쿼리로 한 번 더 돌린다. 결과에 영향이 없는 `users`(닉네임용, FK로 항상 존재) 조인을 뺀 `countSql()`을 쓰며 목록과 같은 판정 조인을 공유한다. `psa`는 FK로 항상 존재하지만 `psb.performance_id = psa.performance_id`(같은 공연 방어)에 쓰이므로 뺄 수 없다. 줄인 COUNT가 목록 전체 행 수와 같은지 `countMatchesListTotalAcrossMixedScenario`와 모든 개별 테스트의 count 단언으로 확인한다. 같은 읽기 전용 트랜잭션이라 목록과 총계는 한 연결을 쓴다(`listAndCountUseTheSameConnectionInsideAReadOnlyTransaction`로 확인; 서비스 전체의 트랜잭션 전파는 Spring 컨텍스트 테스트가 없어 이 메커니즘 수준의 검증이다).
- 시각 처리: 일시는 `LocalDateTime`으로 바인딩·읽는다(`Timestamp` 변환 없음). 앱·DB 모두 KST 벽시계 값이라 서버 JVM 시간대에 의존하지 않는다.
- 확장 조각 규칙(`additionalExclusions()`): 조각은 `AND`로 시작해야 하고 `?`(바인딩 파라미터)를 포함할 수 없다. 위반하면 `IllegalStateException`이며(`ExchangeCandidateSqlGuardTest`), 앞뒤 개행은 자동으로 붙는다.

## 후속 메모 (매칭 생성·예약)
- 남은 것: ① 7일 경과 알림(보류, 2026-10-13 사용자 결정). (사용자 차단은 V13으로 2026-10-12 구현 완료: 차단 시 후보 제외·채팅 가능 여부는 차단 절 참고, 열린 매칭은 시스템 취소하고 잠금 해제.) (채팅은 V11로 구현 완료, 2026-10-11.) (완료: 내 매칭 목록·단건 조회 API, 예약 방식 V8, COMPLETED·교환 이력 기록 V9(2026-10-11). 교환 이력 조회 API·화면은 다음 브랜치.)
- 회차 마감(당일 끝)이 지난 뒤에도 잠금이 남은 티켓의 자동 비활성은 스케줄러 구현 때 설계 6절 '확인 필요 3'(매칭 종료 뒤 비활성화)에 따른다.
- 같은 요청에 열린 매칭이 여러 개 있어도 요청 수정·삭제가 막히는 점(어느 한 쪽이라도 열린 매칭이 있으면 409)은 의도다. 채팅 중에 희망 조건이 바뀌는 것을 막는다.
- 상대 요청이 제안 직후 삭제되는 경쟁은 요청 행 잠금으로 막힌다(잠금 사이 삭제되면 404).

### 알려진 한계 (매칭 생성·예약)
- (해소) 요청 삭제가 소프트 삭제(V7)가 되어 매칭 행은 지워지지 않고 상대의 취소 기록이 남는다. 삭제된 요청 쪽은 응답의 `myRequestDeleted`/`counterpartRequestDeleted`로 표시한다.
- **낯선 사용자의 제안(CHATTING)은 b측 요청의 수정·삭제를 막는다.** 열린 매칭이 있는 요청은 409이므로, 아무 후보나 제안해 놓으면 상대가 먼저 취소/거절(reject)해야 자기 요청을 고칠 수 있다. 차단하면 그 쌍의 열린 매칭은 시스템 취소되어 풀린다(V13). 신고만으로는 풀리지 않으며 상대의 취소·거절 정책은 그대로 둔다.
- (L2) `TicketService.deactivate`의 CHATTING 매칭 시스템 취소 UPDATE(`ticket_a_id=? and status=CHATTING`)는 REPEATABLE READ에서 `idx_exchange_match_ticket_a/b` 구간을 status 필터 전에 next-key로 잠가, 과거 CANCELED/COMPLETED 행과 인접 gap까지 잠글 수 있다(이웃 티켓 id의 INSERT가 잠시 지연될 수 있으나 교착은 아님). 규모가 커지면 `SELECT id ... FOR UPDATE`로 id를 뽑아 PK로 갱신하는 방식으로 바꾼다.
- (L3) 한 요청에 열린 매칭이 여러 개일 때 하나가 RESERVED가 되어도 나머지 CHATTING 매칭은 그대로 남는다(자동 취소 없음: 새 제안·새 예약만 막는다). 사용자가 직접 취소해야 하며, 교환 완료로 기존 티켓이 EXCHANGED가 되어도 자동 취소하지 않고 버튼 비활성+"이미 교환된 좌석이에요" 안내로 처리한다. 다른 매칭에서 티켓이 잠긴 상태의 reserve는 409(`TICKET_ALREADY_RESERVED`)이고 이때 아무 흔적(`reserved_*`)도 남기지 않는다(어느 쪽 티켓이 잠겼는지는 응답에 싣지 않는다). 후보 카드의 '예약 중' 뱃지는 폐기(2026-10-11)해 후보 SQL은 바꾸지 않는다(예약 잠긴 티켓은 계속 제외). 알림은 V10(3종)·V15(6종)으로 구현 완료(실시간 푸시는 2026-10-11 `feature/notifications-realtime`).
- (L7, 해소) V5의 `ck_exchange_match_canceled`는 CANCELED의 `canceled_at` 필수와 열린 상태의 `canceled_*` NULL만 강제했다. V8이 `ck_exchange_match_reserved`(RESERVED <=> `reserved_*`)와 `ck_exchange_match_completed`(CHATTING이면 수락 표시 NULL, COMPLETED이면 둘 다 NOT NULL)로 상태별 시각 일관성을 DB가 강제하게 보강했다. 교환 완료(V9) 구현 때 이 CHECK와 COMPLETED 전이가 맞는지 확인했다(`ExchangeCompleteMysqlTest` 통과). 남은 것: 레거시 `a/b_reserved_at` 삭제는 충분히 뒤로 미룬다(백필 재계산 근거).
- (L8, 해소) 응답 변환이 닉네임을 위해 `userRepository.findById`를 따로 호출하던 문제는 조인 한 번(`ExchangeMatchQueryRepository`)으로 바꿔 해소했다. 내 매칭 목록·단건 조회 API도 구현됐다. 회차 마감 후 잠금이 남은 티켓의 자동 비활성(스케줄러)은 아직 없다.

## 채팅 (V11, 2026-10-11, `feature/chat`)

매칭 참여자끼리 텍스트로 대화한다. 보내기는 REST, 받기는 STOMP 사용자 큐 푸시다. 시스템 메시지는 저장하지 않는다. 새 메시지는 수신자별 `CHAT_MESSAGE` 알림 행 1개를 재사용한다(V15에서 추가, 2026-10-13 기준).

### 구성
- 도메인: `domain/ChatMessage`(`chat_message`, append-only). 읽음 포인터(`chat_read_state`)는 엔티티 없이 `ChatReadStateRepository`(JdbcTemplate). 메시지 목록은 `ChatMessageRepository`의 `FORCE INDEX (idx_chat_message_match)` 네이티브 쿼리로 읽는다.
- 서비스: `ChatService`(전송·목록·읽음 규칙), `ChatRateLimiter`(사용자별 메모리 슬라이딩 윈도), `ChatPushPublisher`(인터페이스)·`ChatPushPublisherImpl`(STOMP 전파).
- 컨트롤러 `ChatController`(`/api/exchange/matches/{id}/messages`), DTO `dto/response/ChatDtos`.
- STOMP: `config/WebSocketConfig`(`/ws`, 순수 WebSocket, 사용자 큐만 있는 simple broker), `security/StompAuthChannelInterceptor`(CONNECT 인증·구독 제한·SEND 거부), `websocket/WebSocketSessionRegistry`(세션·구독·사용자당 연결 수·만료 정리).
- 응답 필드: 매칭 응답의 `unreadChatCount`(상대가 보낸 메시지 중 내 읽음 포인터보다 큰 id의 수, 보낸 사람 제외, CANCELED·COMPLETED 매칭도 센다).

### REST API

모두 로그인이 필요하고 매칭 참여자만 쓴다. 비참여자와 없는 매칭은 같은 404(`매칭을 찾을 수 없습니다.`)다.

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/matches/{id}/messages | 전송. body `{content, clientMessageId}` -> 201(새 메시지). 같은 `clientMessageId`의 재전송은 200(기존 메시지, 다시 전파하지 않음) |
| GET | /api/exchange/matches/{id}/messages | 목록. 기본은 최신 30건. `before=<id>`는 그보다 오래된 건(위로 스크롤, 기본 30), `after=<id>`는 그보다 새 건(재연결 뒤 빈 구간, 기본 100). `size` 최대 100. `before`와 `after`를 함께 주면 400. 종료된 매칭도 열람 가능 |
| PUT | /api/exchange/matches/{id}/messages/read | 읽음. body `{lastReadMessageId}` -> 204. 그 매칭의 실제 메시지 id 중 그 값 이하의 최대값으로 잘라 저장하며 줄어들지 않는다(단조 증가). 올라갔을 때만 상대에게 READ 푸시 |

목록 응답: `{messages, hasMore, myLastReadMessageId, counterpartLastReadMessageId}`(`messages`는 id 오름차순). 메시지: `{id, senderId, content, clientMessageId, createdAt}`(`createdAt`은 오프셋 없는 KST 초 단위). `senderId`는 상대의 users.id를 그대로 보낸다(차단·신고 브랜치에서도 `mine`으로 바꾸지 않았다. 후속).

본문 규칙(전송, 400 필드 `content`): CRLF를 LF로 바꾸고 앞뒤 공백을 뺀 뒤 빈 본문은 거부, 1000 코드 포인트 이하. 줄바꿈·탭 외 제어 문자(C0·DEL·C1), 제로폭·BiDi 제어 문자(U+200D ZWJ만 허용), 짝 없는 서로게이트, 단독 CR은 거부한다. `clientMessageId`는 `^[A-Za-z0-9_-]{8,36}$`(400 필드 `clientMessageId`)이다. 텍스트만 저장하며 HTML 이스케이프·링크 변환은 하지 않는다(프론트가 텍스트로만 그린다).

### 채팅 오류 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| 종료된 매칭(CANCELED·COMPLETED)에 전송 | 409 | `{message: "종료된 채팅에는 메시지를 보낼 수 없어요", code: MATCH_STATE_CONFLICT, status, action: SEND}`. 목록·읽음은 허용 |
| 다른 매칭에서 쓴 `clientMessageId` 재사용(같은 보낸 사람) | 409 | `{message, code: CLIENT_MESSAGE_ID_REUSED}` |
| 전송 속도 제한 초과 | 429 | `{code: CHAT_RATE_LIMITED, message, retryAfterSeconds}` + `Retry-After` 헤더 |
| 본문·clientMessageId 규칙, `before`+`after` 동시, `size` < 1, 음수 커서, `lastReadMessageId` 누락·음수 | 400 | `{필드: 메시지}` |
| 행 잠금 대기 초과·교착 | 503 | `{code: BUSY, message}` |
| 인증 없음 | 401 | `{message}` |

### 전송·읽음 규칙
- **전송 순서**: 트랜잭션 밖에서 참여자를 확인한 뒤, 트랜잭션의 첫 쿼리로 매칭 행을 `FOR UPDATE`로 잠근다(매칭 행만 잠그고 UPDATE하지 않으므로 `updated_at`과 정렬이 바뀌지 않는다). 같은 (`clientMessageId`, 보낸 사람) 행이 이미 있으면 상태와 무관하게 그 행을 돌려준다(응답 유실 뒤 재전송 보호, 속도 제한 미소모). 없으면 상태가 CHATTING·RESERVED가 아닐 때 409, 아니면 속도 제한(새 INSERT에만 소모) 뒤 INSERT한다. **커밋 뒤**에 두 참여자에게 푸시하며, 푸시가 실패해도 저장은 유지된다.
- 같은 매칭의 전송은 매칭 행 잠금으로 직렬화되므로 id 순서가 커밋 순서다. 종료(cancel·complete) 커밋 뒤에는 메시지가 커밋되지 않는다.
- **읽음 포인터**: `ChatReadStateRepository.advance`는 UPDATE(GREATEST) -> 없으면 INSERT -> 중복 키면 재UPDATE 순서이며, **호출자 트랜잭션 밖(autocommit, `NOT_SUPPORTED`)**에서 실행한다. 트랜잭션 안에서는 갭 잠금 교착이 재현됐다. 매칭이나 users 행을 잠근 트랜잭션 안에서 호출하지 않는다.
- **잠금 순서**: 채팅 트랜잭션은 매칭 행 하나만 잠그므로 티켓 -> 요청 -> 매칭 규약과 충돌하지 않는다. 채팅 전송은 트랜잭션 맨 끝에서 상대의 `CHAT_MESSAGE` 알림을 (수신자, 매칭)당 1행 재사용으로 쓴다(V15): PK 행을 먼저 갱신하고 없을 때만 `INSERT … ON DUPLICATE KEY UPDATE`. 같은 `clientMessageId` 재전송은 알림을 다시 쓰지 않는다. 읽음 포인터가 올라가면 `NotificationService.markChatRead`가 그 알림을 읽음 처리한다(매칭·users 잠금 밖의 독립 트랜잭션).
- **상태별 허용**: CHATTING·RESERVED는 읽기·쓰기 모두 허용한다(교환된 좌석이나 다른 예약 매칭이 걸린 채팅 포함). CANCELED·COMPLETED는 읽기·읽음만 허용한다.
- 메시지는 무기한 보관하며 수정·삭제하지 않는다(신고 근거 보관, 탈퇴는 익명화). 새 메시지는 `exchange_match.updated_at`을 바꾸지 않는다.
- **속도 제한**: 사용자별 최근 5초 5건과 최근 60초 60건을 둘 다 지켜야 한다(슬라이딩 윈도, 한도를 넘은 시도는 기록하지 않음). 서버 한 대의 메모리이며 재시작하면 초기화된다.

### STOMP 실시간 수신
- 엔드포인트 `/ws`(순수 WebSocket, SockJS 없음). 허용 Origin은 `matjari.websocket.allowed-origins`(환경변수 `MATJARI_WS_ALLOWED_ORIGINS`, 기본 `http://localhost:5173`, REST CORS와 같은 설정)이며 정확히 일치해야 한다. `*`를 포함하면 시작 시 거부한다. 허용되지 않은 Origin은 403이다.
- **CONNECT**: 네이티브 헤더 `Authorization: Bearer <access>`만 읽는다(쿼리스트링과 passcode는 무시). 서명·만료를 검증하고 refresh 토큰은 거부하며, 사용자를 로드해 사용자 이름을 userId로 설정한다. 사용자당 동시 연결 5개(`MATJARI_WS_MAX_CONNECTIONS_PER_USER`), 중복 CONNECT는 거부한다. CONNECT 하지 않은 세션은 10초 뒤 1008로 닫는다.
- **SUBSCRIBE**: `/user/queue/chat`과 `/user/queue/notifications`(알림 신호)를 정확히 일치할 때만 허용하며, **목적지당 구독 1개**다. 같은 목적지의 두 번째 SUBSCRIBE나 이미 쓰인 구독 id 재사용은 거부하고 세션을 1008로 닫는다(세션당 최대 2개). UNSUBSCRIBE·DISCONNECT는 허용한다. 클라이언트 SEND·ACK 등 나머지는 전부 거부한다(ERROR 뒤 close). 전송은 REST로만 한다.
- 토큰이 만료된 세션은 주기 정리(`MATJARI_WS_SWEEP_INTERVAL_MS`, 기본 30초)가 close 1008로 닫는다.
- 크기·시간 한도: 메시지 16KB, sendBuffer 512KB, sendTime 15초, heartbeat 10초/10초.
- CSRF는 `csrf.disable()`이고 spring-security-messaging을 쓰지 않아 해당 요건이 없다. `/ws/**`는 permitAll이라 핸드셰이크는 통과하고, 인증은 CONNECT에서 한다.
- **푸시 본문**: `{type:"MESSAGE", matchId, message:{id, senderId, content, clientMessageId, createdAt}}`, `{type:"READ", matchId, lastReadMessageId}`. 수신자는 참여자 두 사람의 사용자 큐다(`convertAndSendToUser`).
- **알림 신호**: 알림 푸시는 채팅과 다른 목적지 `/user/queue/notifications`로 `{type:"NOTIFICATION_CREATED", notificationId, notificationType, matchId}`만 보낸다(알림 문구·닉네임·좌석은 싣지 않는다). 자세한 규칙은 '알림 실시간 푸시' 절.

### 테스트
- MySQL 없이 도는 것: `ChatServiceTest` 18건, `ChatControllerSliceTest` 7건, `ChatRateLimiterTest` 3건, `StompAuthChannelInterceptorTest` 14건.
- 실제 MySQL(`MATJARI_IT_*` 필요, 없으면 건너뜀): `ChatServiceMysqlTest` 11건, `ChatSchemaMysqlTest` 6건, `StompChatMysqlIT` 10건(실제 STOMP 연결로 CONNECT·구독·푸시·거부 규칙 검증).
- 임시 MySQL 수동 검증(2026-10-11): 전송·읽음·교환 수락·예약 토글 혼합 스트레스에서 교착 0건, 동시 전송 160건 누락 0건.

### 미구현·알려진 한계 (채팅)
- **차단·정지(V13 구현 완료)**: 이용 정지는 커밋 뒤 대상의 STOMP 연결을 닫는다(차단은 열린 매칭 취소로 쓰기만 막는다).
- 신고는 V13에서 구현됐다(채팅 화면에서 신고, 관리자가 채팅 근거를 열람). 메시지 수정·삭제 없음(정책). 이미지·첨부 없음(텍스트만). 시스템 메시지는 저장하지 않는다. 새 메시지는 `CHAT_MESSAGE` 알림(수신자별 1행 재사용)으로 헤더 벨에 뜨며, 헤더 채팅 아이콘은 없다(V15).
- 속도 제한과 연결 제한은 서버 한 대의 메모리다. 다중 인스턴스로 가려면 공유 저장소가 필요하다.
- 미인증 소켓은 CONNECT 기한(10초) 뒤 닫히지만 정리 주기 때문에 최대 약 40초 유지될 수 있다(운영 프록시 연결 한도로 흡수). 하트비트 미응답 종료와 16KB 초과 프레임 거부는 실측으로 확인하지 않았다.
- 응답·푸시에 상대 `senderId`(users.id)가 노출된다.
- 실제 브라우저와 `@stomp/stompjs` 연동 점검은 아직 하지 않았다.

## 알림 (V10, 2026-10-11, `feature/notifications`)

알림 6종(V15에서 3종 추가)의 쓰기 경로와 조회·읽음·삭제 API다. 실시간 푸시(STOMP 신호)는 `feature/notifications-realtime`(2026-10-11)에서 구현했고, 토스트는 만들지 않았다('알림 실시간 푸시' 절).

- 엔티티·저장소: `domain/Notification`(`notification` 테이블; `readAt`은 조회용 매핑이고 갱신은 벌크 쿼리로 한다), `domain/NotificationType`(PROPOSAL_RECEIVED / RESERVATION_PROPOSED / RESERVATION_REJECTED / MATCH_RESERVED / RESERVATION_CANCELED / CHAT_MESSAGE), `NotificationRepository`(저장·읽음·삭제), `NotificationQueryRepository`(목록·개수 조회). 서비스 `NotificationService`, 컨트롤러 `NotificationController`(`/api/notifications`), 응답 `NotificationResponse`·`UnreadCountResponse`.

### 알림 API

모두 로그인(Bearer)이 필요하고 본인 알림만 다룬다. 남의 알림과 없는 알림은 똑같이 404 `알림을 찾을 수 없습니다.`다(존재 비노출). 생성 API는 없다(제안·예약·예약 취소 트랜잭션 안에서만 만든다).

| Method | Path | 설명 |
|---|---|---|
| GET | /api/notifications?page=0&size=20&unreadOnly=false | 내 알림 목록, id 내림차순. size 기본 20, 100 초과는 100으로 보정. page 음수·size 1 미만·잘못된 값은 400. 닫힌 매칭의 알림도 포함 |
| GET | /api/notifications/unread-count | `{total, byType}`. byType에는 6종 키가 항상 있다(0 포함). 닫힌 매칭 알림도 센다 |
| POST | /api/notifications/{id}/read | 단건 읽음 (204, 이미 읽었어도 멱등) |
| POST | /api/notifications/read-all?type= | 일괄 읽음 (204). type을 생략하면 전체, 잘못된 type은 400 |
| DELETE | /api/notifications/read | 읽은 알림 삭제 (200, `{"deleted": n}`). 안 읽은 알림과 남의 알림은 지우지 않는다. **알림이 사라지는 경로는 이것과 회원탈퇴(수신자 알림 전부)뿐**이다 |

unread-count 예시: `{"total":3,"byType":{"PROPOSAL_RECEIVED":0,"RESERVATION_PROPOSED":1,"RESERVATION_REJECTED":0,"MATCH_RESERVED":0,"RESERVATION_CANCELED":0,"CHAT_MESSAGE":2}}`

목록 항목(`NotificationResponse`): `id, type, matchId, read, readAt, createdAt, matchStatus, matchClosed, role(SENT|RECEIVED), counterpartNickname, performanceTitle, mySeat{zone,row,col}, counterpartSeat{zone,row,col}`.
- `role`은 매칭 기준이다(SENT = 내가 제안자 a측, RECEIVED = b측). `matchClosed`는 CANCELED·COMPLETED일 때 true다.
- 좌석은 매칭 행의 자리이므로 항상 교환 전 자리다. 닉네임·공연 제목은 조회 때 조인하고, 이메일 등 개인정보는 응답에 없다.

### 생성 규칙 (쓰기 경로)

`ExchangeMatchService`의 `propose`·예약 제안(`reserve`)·수락(`reserve/accept`)·거절(`reserve/decline`)·`unreserve`와 `ChatService`의 전송이 **상태를 실제로 바꾼 경우에만**, 트랜잭션의 맨 끝에서 `NotificationService.notify`(MANDATORY, saveAndFlush)로 수신자당 1건을 쓴다. 멱등 경로와 오류 경로에서는 쓰지 않으며, 트랜잭션이 롤백되면 알림도 함께 사라진다.

| type | 생성 시점 | 수신자 | dedup_key |
|---|---|---|---|
| PROPOSAL_RECEIVED | 제안으로 CHATTING 매칭이 생성됨 | 제안받은 쪽(b측) | `PROPOSAL_RECEIVED:{matchId}` (같은 제안은 한 번만) |
| RESERVATION_PROPOSED | 예약 제안이 생김 (CHATTING, 제안 컬럼 기록) | 제안받은 쪽 | `RESERVATION_PROPOSED:{matchId}:{recipientId}` (재사용 1행) |
| RESERVATION_REJECTED | 제안받은 쪽이 예약 제안을 거절함 | 제안자 | NULL (거절마다 1건) |
| MATCH_RESERVED | 제안받은 쪽이 수락해 RESERVED가 됨 (문구: 상대가 예약을 수락했어요) | 제안자 | NULL (예약마다 1건) |
| RESERVATION_CANCELED | 한쪽이 예약을 취소함 (RESERVED -> CHATTING) | 호출자의 반대편 | NULL (취소마다 1건) |
| CHAT_MESSAGE | 상대가 채팅 메시지를 저장함 (같은 `clientMessageId` 재전송 제외) | 상대 | `CHAT_MESSAGE:{matchId}:{recipientId}` (재사용 1행) |

예약 제안 철회(`reserve/withdraw`)는 알림을 만들지 않는다.

- **dedup_key 규칙**: 한 번만 생기는 알림(제안)만 `dedup_key`로 막는다(`uk_notification_dedup` 단일 유니크, NULL은 중복 허용). 예약 수락·거절·예약 취소는 같은 매칭에서 반복되므로 키를 두지 않고 반복마다 1건을 쌓는다. 예약 제안과 채팅 메시지는 (수신자, 매칭)당 1행을 재사용한다(아래 '재사용 알림'). 설계 가안이던 `(user_id, type, match_id)` 유니크는 쓰지 않는다. 재예약 때 그 유니크가 위반되어 예약 트랜잭션이 통째로 롤백되기 때문이다.
- **잠금 순서**: 티켓 id↑ -> 요청 id↑ -> 매칭 -> users(S, FK 검사) -> notification. **notification 행을 잠근 뒤에는 티켓·요청·매칭을 잠그지 않는다.** 알림 쓰기는 항상 트랜잭션의 마지막이다.
- **읽음·삭제 규칙**: 잠금 없는 SELECT로 id를 모은 뒤 500개씩 `WHERE id IN (...)`으로만 갱신·삭제한다. 범위 UPDATE/DELETE는 쓰지 않는다(갭·넥스트키 잠금이 동시에 들어오는 알림 INSERT와 교착하는 것을 피하기 위해서다). 읽음 처리는 수집 이후 들어온 새 알림을 건드리지 않는다.
- **닫힌 매칭**: 취소·완료된 매칭의 알림도 개수·목록에서 빼지 않는다(`matchClosed`만 표시). 알림을 자동으로 읽음 처리하지도 않는다(요구사항 §7 Q-6 확정).
- **인덱스**: `idx_notification_user`·`idx_notification_match`는 FK용 단일 컬럼이다. 안 읽은 개수는 `unread_user_id` 생성 컬럼 인덱스로 센다. FK 인덱스에 `read_at` 같은 갱신 컬럼을 넣지 않는다(위 '동시성' 규칙).

### 재사용 알림 (V15, 2026-10-13)

- `RESERVATION_PROPOSED`·`CHAT_MESSAGE`는 `dedup_key`를 `{type}:{matchId}:{recipientId}`로 채우고 (수신자, 매칭)당 행 1개를 재사용한다. 쓸 때는 **PK 행을 먼저 갱신**(`read_at = NULL`, `created_at = now`)하고, 행이 없을 때(처음이거나 읽은 알림 삭제 뒤)만 `INSERT … ON DUPLICATE KEY UPDATE`로 만든다. 순수 upsert(유니크 인덱스를 먼저 잠금)는 '읽은 알림 삭제'(PK를 먼저 잠금)와 실제 MySQL에서 교착했으므로 순서를 통일했다.
- 되살린 알림은 `created_at`이 바뀌므로 목록·말풍선 정렬은 `ORDER BY created_at DESC, id DESC`다(`NotificationQueryRepository`). 사용자당 알림이 (수신자, 매칭)당 1행이라 정렬용 `(user_id, created_at)` 인덱스는 만들지 않았다(EXPLAIN 근거 없음).
- **채팅 알림 읽음**: `ChatService.markRead`가 포인터를 올리면 `NotificationService.markChatRead`가 그 (수신자, 매칭)의 `CHAT_MESSAGE` 행을 유니크 키로 찾아 PK로 한 행씩 읽음 처리한다(안 읽은 것만). 이 호출은 매칭·users 잠금 밖의 독립 트랜잭션(READ_COMMITTED)에서 한다. 한 문장의 `NOT EXISTS` 가드로 읽음 포인터 뒤에 상대 메시지가 남아 있으면 읽음 처리하지 않는다(새 메시지의 알림 되살리기와 겹쳐도 새 알림이 읽음으로 덮이지 않게 한다).
- 닫힌 매칭의 알림은 개수·목록에 그대로 남는다(위 '닫힌 매칭' 규칙).

### 테스트

- `NotificationControllerSliceTest` 7건: 전 엔드포인트 로그인 요구, 목록 응답 형태(개인정보 없음), 파라미터 전달과 잘못된 값 400, unread-count의 byType 키 상시 포함(V15 이후 6종), 읽음 204와 남의 알림 404.
- `NotificationApiMysqlTest` 10건(실제 MySQL, `MATJARI_IT_*` 필요, 없으면 건너뜀): 목록의 수신자 기준 조인과 개인정보 없음, id 내림차순·unreadOnly·페이지·size 100 보정, 닫힌 매칭 알림 포함, 읽음 멱등과 404, 전체 읽음의 type 필터·수집 이후 알림 보존·대량 배치, 읽은 알림 삭제(안 읽은 것과 남의 것 보존), 교환 완료 매칭 알림의 교환 전 자리 유지, 그리고 **교착 스트레스**(한 수신자에게 제안·예약·취소가 동시에 몰리는 동안 수신자의 읽음·삭제와 본인 티켓 등록 경합을 돌려도 1213 교착이 없고 알림 개수가 맞는다).

### 미구현 (알림)

- 토스트: 만들지 않았다(벨 뱃지 즉시 갱신과 `/notifications`의 '새 알림' 버튼으로 대체). 실시간 푸시(STOMP 신호)는 구현됐다('알림 실시간 푸시' 절).
- 채팅 종료·7일 경과·교환 완료·예약 제안 철회 알림: 생성하지 않는다(`NotificationType` 확장으로 추가할 수 있다).
- 알림 보존 기간·자동 정리 정책: 없다(사용자의 '읽은 알림 삭제'만 있다).

## 알림 실시간 푸시 (2026-10-11, `feature/notifications-realtime`)

새 알림이 생기면 수신자에게 STOMP로 **신호만** 보내고, 화면은 신호를 받으면 REST로 다시 읽는다. DB 변경은 없다(2026-10-11 당시 기록: 그때 다음 마이그레이션은 V12 차단 `user_block`이었고, 이후 V12~V17이 구현됐다).

### 규칙
- **전파 시점**: `NotificationService.notify`가 알림 INSERT 뒤 `TransactionSynchronization.afterCommit`에 등록한 콜백에서 `NotificationPushPublisher`(구현 `NotificationPushPublisherImpl`, `convertAndSendToUser`)로 보낸다. 요구사항 계획의 `@TransactionalEventListener(AFTER_COMMIT)`와는 다른 방식이다. 롤백이면 보내지 않는다. 전파가 실패해도 예외는 삼키고 로그만 남기며 저장·응답에는 영향이 없다(폴링이 보완). 알림 쓰기가 트랜잭션 맨 끝이라는 잠금 규칙은 그대로다.
- **푸시 본문**: `{type:"NOTIFICATION_CREATED", notificationId, notificationType, matchId}`뿐이다. 알림 문구·상대 닉네임·좌석은 싣지 않는다. 수신자의 모든 세션(탭)에 보낸다.
- **구독 목적지**: `/user/queue/chat`과 `/user/queue/notifications` 두 개만 허용하고 **목적지당 구독 1개**다. 같은 목적지의 두 번째 SUBSCRIBE나 이미 쓰인 구독 id 재사용은 거부하고 세션을 1008로 닫는다. 세션당 최대 2개다. 그 밖의 목적지(`/queue/..`, `/topic/..`, 변형)와 클라이언트 SEND는 전부 거부한다.
- **연결 제한**: 사용자당 동시 연결 5개, Origin 설정 규칙은 그대로다.

### 구현
- 백엔드: `NotificationPushPublisher`(인터페이스)·`NotificationPushPublisherImpl`, `NotificationService.notify` 수정, `StompAuthChannelInterceptor`의 구독 허용 목적지를 두 개로 넓히고 목적지당 중복을 거부한다.
- 프론트: `api/realtimeHub.ts`가 로그인한 앱 전체에서 탭당 STOMP 연결 1개를 공유한다. 벨 훅 `useUnreadNotifications`와 채팅 훅 `useChatSocket`이 각자 참조를 잡고(acquire), 마지막 참조가 풀리면 연결을 닫는다. 토큰 재발급 시 재연결하고, 지수 백오프(1~30초)는 허브와 `realtime.ts`가 처리한다. 이미 연결된 상태에서 채팅 화면이 열리면 구독 직후 `onConnected`가 한 번 불려 빈 구간을 채운다.
- 화면: 신호를 받으면 벨 개수를 즉시 다시 읽는다. 벨 말풍선이 열려 있으면 목록을 조용히 다시 읽는다. `/notifications` 페이지는 목록을 바로 바꾸지 않고 "새 알림이 있어요. 눌러서 불러오기" 버튼(`role="status"`)만 띄운다. 토스트는 만들지 않았다.
- 폴링: 연결이 살아 있으면 30초 폴링을 하지 않고 5분(30초×10틱)마다 안전 확인만 한다. 연결이 끊겨 있으면 이전처럼 화면이 보일 때만 30초 폴링하고, focus·visibilitychange·경로 이동·읽음/삭제 직후에 즉시 다시 읽는다. (재)연결 직후에도 안 읽은 개수를 즉시 다시 읽는다.
- 새 채팅 메시지는 `CHAT_MESSAGE` 알림 행(재사용 1행)으로 벨에 표시되고, 푸시는 같은 알림 신호 1건(`/user/queue/notifications`, afterCommit)이다. 헤더 메시지 아이콘은 없다(채팅 푸시 규칙은 불변).

### 테스트
- 백엔드 기본 + 임시 MySQL IT 포함 617건 통과(실패 0, skip 0). 새 테스트: `NotificationServicePushTest`(커밋 뒤 1회, 롤백 시 미전송, 전파 실패 삼킴), `StompAuthChannelInterceptorTest`(알림 큐 구독, 두 큐 동시 구독, 목적지당 중복·변형 거부), `StompChatMysqlIT`(제안받은 사용자의 모든 탭만 수신, 제안한 사람·제3자는 미수신).
- 프론트 `tsc`·`build` 통과. ESLint 설정과 프론트 테스트 러너는 없다.

### 알려진 한계
- 브라우저와 실제 `@stomp/stompjs` 수동 점검은 아직 하지 않았다(헤더 폭, 벨 말풍선, 알림 흐름 포함).
- 다른 탭에서 읽음 처리한 결과는 다른 탭에 푸시하지 않는다(focus·visibilitychange·5분 확인으로 보정).
- 토큰 만료 뒤 최대 30초 동안 푸시가 계속될 수 있다. 미인증 소켓은 최대 약 40초 유지될 수 있다. 연결·속도 제한은 단일 인스턴스 메모리다(기존 한계 그대로).
- 토스트는 만들지 않았다. 필요해지면 후속으로 다시 정한다.

### 다음 작업
- 관리자 페이지 → 교환 이력 조회(API·마이페이지 화면) → 차단·신고 순서였고, 차단·신고는 2026-10-12 V13으로 구현 완료했다. 관리자 페이지는 2026-10-12 완료. (링크 자동 입력은 2026-10-12 구현 완료.)
- 7일 경과 알림은 2026-10-13 사용자 결정으로 보류했다.

## 수동 검증 시나리오 (임시 MySQL, 재현용)

자동 통합 테스트(Testcontainers)는 두지 않고, 아래 절차로 실제 MySQL에서 확인한다. 개발 DB(3306)와 `backend/.env`를 쓰지 않도록 `bootRun`이 아니라 `bootJar` + `java -jar`로 환경변수를 직접 지정한다.

```bash
cd MatJari/backend && ./gradlew bootJar
docker run -d --name matjari-tmp-v4 -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=matjari -p 13306:3306 mysql:8.0 --character-set-server=utf8mb4
SERVER_PORT=18080 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/matjari SPRING_DATASOURCE_USERNAME=root   SPRING_DATASOURCE_PASSWORD=tmp JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop   java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar      # 종료는 이 프로세스 PID만
# 끝나면: docker rm -f matjari-tmp-v4
```

준비(curl): 가입·로그인으로 토큰 `$T`를 얻고, 공연(회차 2개 이상, 미래 일시)과 티켓을 만든 뒤 아래를 순서대로 확인한다. 아래 SQL은 `docker exec matjari-tmp-v4 mysql -uroot -ptmp matjari -e "..."`로 실행한다.

1. **V4 적용·validate**: 기동 로그에 `Successfully applied 4 migrations ... v4`, `SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema='matjari' AND constraint_name LIKE 'ck_exchange%';` 가 5건 (V6/V7 적용 뒤에는 목록이 바뀐다: 요청의 amount/extra_type CHECK 대신 범위·좌석·매칭·요청 status/deleted CHECK).
2. **CHECK 제약**: `INSERT INTO exchange_want_range(request_id,zone_label,zone_key,row_from,row_to,col_from,col_to,extra_type,extra_amount,sort_order) VALUES (1,'A','A','1','1','1','1','POS',-5,0);` -> ERROR 3819 `ck_exchange_want_range_amount` (V6 이후. V4 시점에는 요청 단위 `ck_exchange_request_amount`).
3. **청크 경계(500행씩 INSERT)**: 티켓마다 `POST /api/exchange/requests`로 1행 x 500번(500석), 500+1(501석), 2열 x 500번(1000석), 5열 x 999번 + 1열 x 5번(5000석)을 등록하고 `SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?;` 가 응답의 `wantSeatCount`와 같은지 확인.
4. **합집합 상한**: 같은 범위(5열 x 999번)를 2번 넣으면 201, `wantSeatCount` 4995. 5001석이 되는 입력은 422 `{count: 5001, limit: 5000}`, 999 x 999는 즉시(약 10ms) 422.
5. **하위 행 삭제**(V7 이후 소프트 삭제: 서비스가 지우고 요청은 `DELETED`로 남는다): `DELETE /api/exchange/requests/{id}` 204 뒤 `SELECT (SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_range WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_session WHERE request_id=?);` = 0.
6. **uk 위반 409**: 같은 티켓에 POST를 두 스레드로 동시에 보내면 201 1건 + 409 `REQUEST_ALREADY_EXISTS` 1건, `SELECT COUNT(*) FROM exchange_request WHERE ticket_id=?;` = 1.
7. **closeByTicketId**: 요청이 있는 티켓을 `DELETE /api/tickets/{id}`(204) 한 뒤 `SELECT status FROM exchange_request WHERE id=?;` = `CLOSED`, 그 요청 PATCH는 422 `TICKET_NOT_ACTIVE`.
8. **동시 PATCH 직렬화**: 같은 요청에 서로 다른 입력 2개를 동시에 PATCH(반복)한 뒤 `exchange_want_range` 1행, `exchange_want_seat`의 구역·개수, `exchange_want_session`, `extra_type/extra_amount`가 둘 중 한쪽 입력과 정확히 일치.
9. **H1/L1**: 희망 회차를 내 회차 하나로 하고 내 좌석을 포함하면 422, 다른 회차를 함께 넣으면 201. 행 `-3` 또는 `−3`은 400.

(마감된 회차의 422/400은 회차 등록이 지난 일시를 거부해 HTTP로 만들 수 없어 단위 테스트로만 검증한다.)

## 매칭 후보 조회 검증 (2026-10-08, 임시 MySQL 8.0 컨테이너, 끝난 뒤 삭제)

**SQL 테스트(실제 MySQL).** `ExchangeCandidateQueryTest`(37건: 한쪽만 일치 2, 추가금 4x4 16, 금액 불사용, 회차 판정·여러 회차·다른 공연, 좌석 키 정확도, 마감 경계(오늘 00:00 포함 / 어제 23:59 제외), 같은 사용자·내 요청, CLOSED/INACTIVE, 한 상대가 여러 회차·좌석에 동시에 일치해도 1행·count 1, 정렬(priority -> 최신, 동률이면 id DESC), 페이징, 쿼리 수=SELECT 1회, 줄인 COUNT = 목록 총계, 읽기 전용 트랜잭션에서 목록·COUNT가 같은 CONNECTION_ID)는 환경변수가 있을 때만 돈다(없으면 `[SKIPPED ...]` 메시지와 함께 건너뜀; `MATJARI_IT_REQUIRED=true` 또는 `CI`가 있으면 실패). 안전장치: JDBC URL을 파싱해 호스트가 localhost/127.0.0.1이고 DB 이름(쿼리스트링 제외)이 `_it`로 끝나는지, 연결 직후 `SELECT DATABASE()`도 `_it`로 끝나는지 확인한 뒤에만 Flyway clean/TRUNCATE를 한다(환경변수가 있는데 걸리면 skip이 아니라 실패). 개발 DB(`matjari`, 3306)는 건드리지 않는다.

```bash
docker run -d --name matjari-tmp-cand -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=matjari_it -p 13307:3306 mysql:8.0 --character-set-server=utf8mb4
MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:13307/matjari_it MATJARI_IT_USER=root MATJARI_IT_PASSWORD=tmp ./gradlew test --tests '*ExchangeCandidateQueryTest'
docker rm -f matjari-tmp-cand
```

**EXPLAIN** (요청 1,000건 / 희망 좌석 83만 행): 조인 11개 테이블 모두 const / ref / eq_ref이며 `type=ALL`(전체 스캔)이 없다. 내 희망 회차는 `exchange_want_session` PK, 내 희망 좌석은 `exchange_want_seat` PK 앞부분(`ref`), 상대 티켓은 `uk_ticket_active_seat` 점조회, 상대 요청은 `uk_exchange_request_ticket`, 상대 희망 회차·좌석은 PK 전체(`Using index`). 정렬은 결과 집합에서의 filesort뿐이다. 힌트 없이는 요청 1,000건에서도 `b`(상대 요청)가 `type=ALL`로 시작하는 계획이 나왔다(그래서 STRAIGHT_JOIN).

**성능** (jar 실행, 로컬 HTTP end-to-end, 45회 중 첫 5회 제외; 같은 조건에서 가벼운 `/api/users/me`가 약 14ms). 희망 좌석은 요청당 30~2,400석(평균 약 900), 회차 3개, 구역 2 x 30열 x 40번.

| 규모 | 내 요청 | 후보 수 | size | 중앙값 | p95 |
|---|---|---|---|---|---|
| 100건 | A (희망 100석) | 0 | 20 | 28ms | 45ms |
| 100건 | B (희망 2,400석) | 39 | 20 / 100 | 43ms / 45ms | 68ms / 72ms |
| 1,000건 | A (희망 100석) | 11 | 20 / 100 | 57ms / 42ms | 402ms(1건 튐) / 91ms |
| 1,000건 | B (희망 2,400석) | 299 | 20 / 100 | 68ms / 69ms | 114ms / 94ms |

DB 시간(EXPLAIN ANALYZE, 1,000건): A 약 1ms, B 약 27ms. 한계: 시드는 균일 분포 합성 데이터이고 동시 요청 부하는 재지 않았다.

### 대량 수동 검증 (희망 5,000석, 후보 4,000건) — 2026-10-08 실행

스크립트: `scripts/candidates-bulk-seed.sql`(시드), `scripts/candidates-bench.py`(응답 시간). 임시 MySQL 컨테이너와 `bootJar` + `java -jar`만 쓴다(개발 DB·`.env`·`bootRun` 금지). 끝나면 컨테이너를 삭제한다.

```bash
cd MatJari/backend && ./gradlew bootJar
docker run -d --name matjari-tmp-bulk -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=matjari -p 13307:3306 mysql:8.0 --character-set-server=utf8mb4
SERVER_PORT=18081 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13307/matjari SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=tmp \
  JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar   # 종료는 이 프로세스 PID만
# 사용자 1명 가입 후 로그인해 토큰을 파일(tokbulk)에 저장 (id=1 이 "나"가 된다)
curl -s -X POST localhost:18081/api/auth/signup -H 'Content-Type: application/json' -d '{"email":"bulk@t.com","password":"password123","nickname":"bulk"}'
curl -s -X POST localhost:18081/api/auth/login  -H 'Content-Type: application/json' -d '{"email":"bulk@t.com","password":"password123"}'   # accessToken -> tokbulk
docker exec -i matjari-tmp-bulk mysql -uroot -ptmp matjari < scripts/candidates-bulk-seed.sql      # 약 23초
python scripts/candidates-bench.py tokbulk 1 20          # size 20, 첫 페이지
python scripts/candidates-bench.py tokbulk 1 100         # size 100
python scripts/candidates-bench.py tokbulk 1 100 39      # 마지막 페이지(OFFSET 3,900)
# EXPLAIN: 후보 SQL(ExchangeCandidateRepository.candidateSql()/countSql())의 ? 를 값으로 바꿔 EXPLAIN ANALYZE 로 실행
docker rm -f matjari-tmp-bulk
```

시드: 요청 4,001건, 희망 좌석 200만 행. 내 요청은 희망 5,000석(상한) x 희망 회차 3개, 상대 4,000명이 모두 후보다(추가금 X/ANY/POS/NEG 균등, 내 유형 ANY).

| 측정 | 결과 |
|---|---|
| 총계(totalElements) | 4,000 (totalPages 40, size 100 기준) |
| HTTP size 20, page 0 | 중앙값 135ms, p95 205ms (최대 877ms 1회, 첫 호출 235ms) |
| HTTP size 100, page 0 | 중앙값 148ms, p95 201ms |
| HTTP size 100, page 39 | 중앙값 158ms, p95 245ms |
| 기준 `/api/users/me` | 중앙값 약 17ms |
| DB 시간(EXPLAIN ANALYZE) | 목록 LIMIT 20: 약 100ms, 줄인 COUNT: 약 56ms |
| EXPLAIN | `type=ALL` 없음. a·ta·psa const, wsa ref, psb·tb·b·wsb·wb·ub eq_ref, wa ref(Using index) |

해석: 최악 규모(5,000석 x 3회차 = 15,000번 점조회, 후보 4,000건)에서도 서버 시간 약 130ms 수준이라 keyset 전환 기준(중앙값 300ms)에 한참 못 미친다. OFFSET이 커져도 판정 조인 비용이 대부분이라 page 39와 page 0 차이가 작다. 한계: 합성 데이터, 단일 클라이언트, 동시 부하 미측정.

### V6/V7 이후 재측정 (2026-10-09, 임시 MySQL 8.0.46, 요청 4,001건 / 희망 좌석 2,005,000행 / 후보 4,000건)

시드 `scripts/candidates-bulk-seed.sql`을 V6/V7 컬럼(좌석에 추가금)에 맞게 고쳐 같은 규모로 다시 쟀다(`bootJar` + `java -jar`, 임시 컨테이너, 끝난 뒤 삭제).

| 측정 | 결과 |
|---|---|
| EXPLAIN | `type=ALL` 없음. a·ta·psa const, wsa ref(PK), psb·tb·b·wsb·wb·ub eq_ref, wa ref(PK 앞부분). **b는 `uk_exchange_request_live_ticket`(key_len 10, ref `const,tb.id`) 점조회**, 서브쿼리 2개(`mo`, `l`)도 eq_ref. wa·wb가 `extra_type`을 읽어 `Using index`(커버링) 표기는 wa에서 사라졌고 wb는 `Using where` |
| HTTP size 20, page 0 | 중앙값 98ms, p95 116ms (최대 124ms) |
| HTTP size 100, page 0 | 중앙값 97ms, p95 126ms |
| HTTP size 100, page 39 (OFFSET 3,900) | 중앙값 107ms, p95 164ms |
| 기준 `/api/users/me` | 중앙값 약 10ms |
| DB 시간(EXPLAIN ANALYZE) | 목록 LIMIT 20: 약 125~130ms(웜), 줄인 COUNT: 약 57~96ms |

해석: V5 때(중앙값 135ms / p95 205ms)와 비슷하거나 조금 낫다(측정 머신 차이가 섞여 있어 '악화 없음' 정도로만 본다). 조인 행 수는 늘지 않았다(좌석당 행 1개 정책). 한계: 합성 데이터(요청 단위 추가금과 같은 값을 좌석 행에 반복), 단일 클라이언트, 동시 부하 미측정.

## 매칭 동시성 검증 (2026-10-08, 임시 MySQL 8.0 컨테이너, 끝난 뒤 삭제)

**실제 MySQL 통합 테스트** — `ExchangeMatchMysqlTest`(14건, Spring 컨텍스트 + 실제 서비스·트랜잭션)와 `ExchangeCandidateQueryTest`의 V5 부분(제외 조건 8건, 쌍 재검증 4건, V5 제약 3건)은 `MATJARI_IT_*` 환경변수가 있을 때만 돈다(`ExchangeCandidateQueryTest`와 같은 안전장치: localhost, DB 이름 `_it`, `SELECT DATABASE()` 재확인, 실행 때마다 Flyway clean). 경쟁 시나리오(2026-10-10 V8 반영): 같은 쌍 양방향 동시 제안 8개 x 15라운드, 같은 티켓을 건 두 매칭의 동시 예약 x 15(RESERVED 정확히 1), 두 사람이 같은 매칭을 동시 예약 x 15(한 번만 처리, 둘 다 200 RESERVED, 예약자 정확히 1명), 예약·취소·거절 경쟁 x 15, RESERVED에서 예약 취소·재예약·채팅 종료·예약 취소가 동시에 x 15, 티켓 내리기 vs 예약 x 40(내리는 쪽을 번갈아 id가 큰/작은 티켓으로), 요청 수정 vs 제안 x 15(둘 중 하나만 성공), 요청 수정 vs 예약 x 40, 제안 vs 요청 삭제 x 15, 제안 vs 상대 티켓 내리기 x 15, 티켓 등록·취소·예약·제안 동시 x 15, V5·V8 제약 직접 INSERT/UPDATE 위반(ck_exchange_match_* 포함). 단일 흐름: 한 명 예약 -> RESERVED+잠금 2행 -> 예약 중 cancel/reject 409 -> 상대가 예약 취소 -> CHATTING 복귀 -> 재예약 -> 취소, 교환 수락 표시가 있어도 예약 취소 허용·초기화, 예약 중에도 다른 채팅 유지와 새 예약(409)·새 제안(TICKET_LOCKED/NOT_A_CANDIDATE) 차단. 티켓 내리기 vs 수락은 라운드 번호로 정한 고정 지연으로 양쪽 승부가 모두 나오게 하고(내림 10 / 예약 30) 둘 다 발생함을 단언한다. 교착·락 대기 초과(`DataAccessException`)는 실패로 본다.

```bash
docker run -d --name matjari-tmp-match -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=matjari_it -p 13310:3306 mysql:8.0 --character-set-server=utf8mb4
MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:13310/matjari_it MATJARI_IT_USER=root MATJARI_IT_PASSWORD=tmp ./gradlew cleanTest test
```

**V6/V7 동시성 시나리오 추가 (`ExchangeMatchMysqlTest`, 같은 안전장치)**: 요청 수정 vs 제안 x 15(수정은 항상 성공, 제안이 먼저면 매칭은 시스템 취소, 수정이 먼저면 422 `NOT_A_CANDIDATE`; 두 승부 모두 발생을 단언), 요청 수정 vs 예약 x 40(수정 성공 <=> 매칭 CANCELED·잠금 0, 수정 409 `ACTIVE_MATCH_EXISTS` <=> 매칭 RESERVED·잠금 2), 삭제 vs 새 요청 생성 2개 동시 x 15(미삭제 요청 <= 1, 교착 없음), 제안 vs 상대 요청 삭제 x 15(삭제는 항상 성공, 열린 매칭 0). V8 반영 후(2026-10-10) 이 클래스를 3회 연속 돌려 모두 통과했고 MySQL 로그에 deadlock 0건이다. 결과 로그 예: `[update-vs-propose] proposedThenCanceled=12 updatedFirst=3`, `[update-vs-reserve] updateWins=10 reservedWins=30`, `[deactivate-vs-reserve] deactivated=6 reserved=34`, `[propose-vs-delete] proposedThenCanceled=12 deletedFirst=3`.

**실제 HTTP 동시 요청** — `scripts/match-http-concurrency.py`. `bootJar` + `java -jar`(개발 DB·`.env`·`bootRun` 금지)로 같은 임시 컨테이너의 다른 DB(`matjari_http`)에 앱을 띄우고(`TICKET_MAX_ACTIVE_PER_USER=1000`) 실행한다.

```bash
docker exec matjari-tmp-match mysql -uroot -ptmp -e "CREATE DATABASE matjari_http CHARACTER SET utf8mb4"
SERVER_PORT=18082 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13310/matjari_http SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=tmp \
  JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop TICKET_MAX_ACTIVE_PER_USER=1000 java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar   # 종료는 이 프로세스 PID만
python scripts/match-http-concurrency.py 18082 50 matjari-tmp-match matjari_http
docker rm -f matjari-tmp-match
```

결과(V8 반영 후 2026-10-10, 50라운드): 같은 쌍 동시 제안 8개 x 50 -> 201 정확히 50건, 409 `MATCH_ALREADY_OPEN` 350건. 같은 티켓을 건 두 매칭의 동시 예약 4개 x 50 -> 매 라운드 RESERVED 정확히 1개·잠금 2행(200 100건, 409 100건). 예약/취소/거절 경쟁 4개 x 50, RESERVED에서 예약 취소·재예약·채팅 종료·예약 취소 경쟁 4개 x 50, 티켓 내리기 vs 예약 3개 x 50(내리기 204 7건 / 409 43건)에서 5xx 0건, 서버 로그에 Deadlock/ERROR 0건, DB 불변식 위반(RESERVED 아닌 매칭의 잠금, 잠금이 2행 아닌 RESERVED, INACTIVE 티켓의 잠금, 같은 쌍 열린 매칭 중복, 두 RESERVED 매칭이 공유하는 티켓) 모두 0건(V8 추가 불변식: RESERVED <=> `reserved_by_id`·`reserved_at` NOT NULL도 0건 위반). 스크립트도 `reserve`/`unreserve`와 범위별 추가금 요청 형식으로 갱신했다.

## 교환 완료 (2026-10-11, V9, `feature/exchange-complete`)

- 흐름: 예약 제안 -> 상대 수락으로 예약(RESERVED, V15 이후) -> 양쪽이 '교환 수락'(`complete`) -> 두 번째 수락이 교환 완료 트랜잭션을 실행해 COMPLETED. 첫 수락은 `a/b_completed_at`만 기록하고 RESERVED를 유지한다. 한쪽만 수락하고 방치돼도 자동 완료·취소는 없다(7일 경과 알림은 후속).
- **트랜잭션 순서 (flush 주의)**: 잠금(티켓 id↑ -> 요청 id↑ -> 매칭) -> 기존 두 티켓 `markExchanged()` -> **`ticketRepository.flush()`** -> 새 티켓 INSERT -> `exchange_history` 2행 -> 두 기존 요청 CLOSED -> 잠금 2행 삭제 -> 매칭 COMPLETED. flush를 빼면 Hibernate가 INSERT를 UPDATE보다 먼저 실행해 아직 ACTIVE인 기존 티켓과 `uk_ticket_active_seat`가 충돌한다. 새 티켓 생성은 `Ticket.exchangedFrom`, 이력은 `ExchangeHistory.of`로 만든다.
- 응답: 매칭 응답의 `mySeat`/`counterpartSeat`와 ticket id는 교환 전 자리·티켓이다. 교환 후 자리는 새 티켓과 `exchange_history`에서 본다.
- 테스트: `ExchangeCompleteMysqlTest` 8건(실제 MySQL, 실제 서비스·트랜잭션), `MigrationV9MysqlTest` 3건. 같은 매칭 양쪽 동시 수락 20라운드(COMPLETED는 정확히 한 번), 같은 티켓을 건 두 매칭 경합 20라운드, 교착 0건.
- 알려진 한계(코드 리뷰 기준): ① 받을 좌석을 그 순간 직접 등록하면 `users` 잠금과 엇갈려 드물게 교착이 날 수 있으나 이를 처리하는 코드는 없다. ② 완료 후 새 티켓으로 재교환 요청을 만드는 회귀 IT가 없다. ③ 제안받은 쪽(b측) 기준의 다른 매칭 대칭 IT가 없다.
- 교환 이력 조회 API(`GET /api/exchange/history`)와 마이페이지 화면은 2026-10-12 `feature/exchange-history`에서 구현했다(DB 변경 없음, 본인 행만 id 내림차순, size 기본 20 최대 100, `ExchangeHistoryService`·`ExchangeHistoryController`, 슬라이스·서비스 테스트 5건).

## 다음 단계
1. 교환 도메인 구현 계속: (완료) V4 희망 범위·희망 좌석·희망 회차, 매칭 후보 조회, V5 매칭 생성·예약·거절·취소·예약 잠금, V6 추가금 범위 단위, V7 요청 소프트 삭제, V8 단일 예약·예약 취소, V15 예약 제안·예약 수락·채팅 알림(2026-10-13), V9 교환 완료(COMPLETED, 기존 티켓 EXCHANGED + 새 티켓)·교환 이력 기록, V10 알림 3종(조회·읽음·삭제 API 포함, 2026-10-11), V11 채팅(REST 전송·조회·읽음 + STOMP 푸시, 2026-10-11), 알림 실시간 푸시(STOMP 신호, 2026-10-11, `feature/notifications-realtime`), 관리자 페이지 V12(공연 수정 제안·내 티켓 인증, 2026-10-12, `feature/admin`) / 남음: 7일 경과 알림(보류, 2026-10-13 사용자 결정). 차단·신고(V13, 2026-10-12)와 교환 이력 조회 API·화면(2026-10-12 `feature/exchange-history`)은 구현 완료 (설계안 `산출물/08_ERD/exchange-schema-design.md`)
2. 티켓 자동 비활성(회차 당일 끝 경과, 스케줄러)과 '내 티켓 인증'
3. (완료) 후보 제시 -> 매칭 생성 -> 예약 제안·수락/예약 취소 -> 양쪽 '교환 수락'으로 교환 완료(V9, 2026-10-11)
4. (완료, 2026-10-12) 사용자 차단·신고·관리자 신고 처리·이용 정지(V13). 후기 기능은 없음; 채팅은 2026-10-11 구현 완료
5. 배포 시 SecurityConfig의 CORS allowed-origin을 실제 프론트 도메인으로 교체
6. 브라우저 수동 점검(차단·신고·정지 화면 포함), 7일 경과 알림(보류, 2026-10-13 사용자 결정)
7. (완료, 2026-10-12, 병합됨) 소셜 로그인(V14).
8. (완료, 2026-10-13, 병합됨) 예약 제안·예약 수락·채팅 알림(V15), 벨 위치(데스크톱은 '내 매칭' 오른쪽). 예약 버튼은 채팅 화면에만 있다.
9. (완료, 2026-10-13, `feature/withdraw`, V17, 병합됨) 회원탈퇴·개인정보처리방침·푸터. 다음 요청(미착수): AI 상세정보 읽기로 회차 시각 채우기(구조만 열림). 7일 경과 알림은 보류(2026-10-13). 회원탈퇴 후속: 정지 중 제한 로그인, 소셜 전용 계정 재인증, 인증 사진 정리 작업.
10. (완료, 2026-10-13, `feature/profile-edit`, V16) 마이페이지 닉네임 수정·소셜 계정 연결·해제.
11. (완료, 2026-10-13, `feature/session-prefill`, DB 변경 없음, 병합됨) 회차 날짜 미리 채우기(`sessionCandidates`, 공연 기간 100일 이하의 모든 날짜 — 2026-10-14에 2일 이하에서 확대). 시각은 직접 입력이다.
12. (완료, 2026-10-13, `feature/remove-trust-score`, V18, 미커밋) 폐기된 신뢰도 점수 컬럼 제거(`users.trust_score`, 응답 `trustScore`).
13. (완료, 2026-10-13, `feature/email-verification`, V19) 가입 전 이메일 인증번호(`email_verification`, `POST /api/auth/signup/email-code`, 가입 요청의 `emailCode`). 메일은 `MATJARI_MAIL_MODE=log`(개발)·`smtp`(운영, `SPRING_MAIL_*`)이고 운영에서 log 모드면 기동을 거부한다. 다음 마이그레이션은 V20.
