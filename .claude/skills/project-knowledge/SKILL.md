---
name: project-knowledge
description: 맞자리의 현재 유효한 서비스 개요·확정 교환 규칙·기능 목록과 구현 상태·데이터 모델(Flyway V1~V21)·API/화면 요약. 기능 구현이나 설계 질문에 답하기 전에 먼저 참고한다. 결정이 바뀌면 이 문서의 해당 항목을 직접 고친다(이력은 HISTORY.md, 산출물/07_작업일지, wiki/).
---

# 프로젝트 지식 요약

현재 유효한 사실만 담는다. 변경 이력은 `HISTORY.md`·`산출물/07_작업일지`·`STATS.md`·`wiki/`에 있다.
충돌 시 우선순위는 루트 `CLAUDE.md` > 이 문서. 코드와 어긋나면 코드가 사실이다.
산출물 원본(03 계획서·05 WBS docx/xlsx)은 저장소에 없고, 04는 `산출물/04_요구사항정의서/`의 md 두 개(후속요구사항, FR-02 정정 정책), 08은 `산출물/08_ERD/erd.dot`·`exchange-schema-design.md`만 있다.

## 1. 서비스 개요

같은 공연 티켓 보유자끼리 좌석을 맞교환하거나, 등급 차이만큼 추가금을 주고받으며 교환하는 개인 포트폴리오 웹 서비스.

- **핵심 흐름**: 본인 좌석 1개 + 교환 희망 좌석 범위(예: 3열 3~5번, 4열 3~5번) + 추가금을 **텍스트(구역·열·번)** 로 입력 -> 범위를 개별 좌석으로 펼쳐 저장 -> 서로의 조건이 맞는 상대(내 좌석이 상대 희망에 있고 상대 좌석이 내 희망에 있음)를 **후보로 제시** -> 사용자가 후보를 골라 채팅 -> 한 명이 예약을 제안하고 상대가 수락해 예약 -> 각자 '교환 수락' -> 교환 완료.
- 점수화·랭킹·추천 알고리즘은 없다. 후보 선택은 사용자가 한다.
- 교환 범위는 **공연 단위**: 같은 공연이면 회차가 달라도 교환할 수 있고, 매칭은 사용자가 희망한 회차끼리만 한다.
- 타겟: 모바일 웹(반응형). 제외 범위: 실결제(PG)·티켓팅 사이트 공식 API·좌석 실시간 재고 연동·네이티브 앱.
- 좌석표 이미지 인식·좌석표 기반 범위 선택은 **동결**(코드는 삭제, git 태그 `archive/seatmap-track-20261007`에 보관). 새 매칭 코드는 좌석표에 의존하지 않는다. 좌석 식별 키는 (구역, 열, 번)이라 나중에 좌석표를 같은 키 선택 화면으로 얹을 수 있다.

## 2. 기술 스택

| 구분 | 기술 |
|---|---|
| Frontend | React + TypeScript + Vite + Tailwind CSS v4 (Tailwind 유틸리티만 사용, 브레이크포인트 640/768/1024px 고정, 브랜드색 primary #8A2BE2 / secondary #BEA886(글자색 금지) / accent #E8A33D) |
| Backend | Spring Boot 3.3.13(Security 6.3.10) + JPA + Flyway, JWT 인증. WebSocket(STOMP, `/ws`)은 채팅 푸시에 구현됨(커스텀 CONNECT 인증 인터셉터, 사용자 큐만 구독) |
| Frontend 추가 | `@stomp/stompjs` ^7.3(채팅 화면 전용), `VITE_WS_URL`(없으면 API 주소에서 계산) |
| DB | MySQL 8.0 (Flyway V1~V21) |
| 배포 | `MatJari/docker-compose.yml`: mysql · backend · frontend 3개. 시간대 KST(Clock Asia/Seoul, TZ=Asia/Seoul) |

- 이미지 인식 서비스(FastAPI/OpenCV/Tesseract)는 동결로 삭제됐다.
- 후속: Spring Boot 4.x 메이저 업그레이드(배포 전). `mysql-connector-j 8.4.0` 수동 지정 유지.

## 3. 확정 규칙

### 3.1 좌석·회차·티켓
- 구역·열·번은 사용자가 텍스트로 입력한다. 공연장 단위 구역 테이블·구역 자동완성·직사각형 범위 선택·펼침 상한은 두지 않는다(좌석표 기능과 함께 후속). 지정석·1매 제한 없음(스탠딩 등도 텍스트 입력). 연석(2매 이상)과 3자 이상 순환 교환은 구현하지 않되 모델이 막지 않게 한다.
- 열·번 정규화: NFKC, 공백 제거, 대문자, 앞 0 제거, 끝의 '열'/'번' 제거. 숫자·문자 모두 허용(숫자 1~999). 제어·제로폭 문자 거부, 부호 붙은 정수(`-3`, `+3`, U+2212) 400 거부. 숫자는 `3~5` 범위로 입력해 펼치고, 문자 열(`A열`)은 하나씩 추가. 이 범위 규칙은 열·번에만 해당하며 **구역은 별도 필수 입력**.
- 티켓: 같은 회차·구역·열·번의 **활성 티켓은 1개**(409 `SEAT_ALREADY_REGISTERED`, 내 것이면 `MY_TICKET_ALREADY_REGISTERED`), 사용자당 활성 20개 상한(422 `TICKET_LIMIT_REACHED`). 이미 등록된 좌석이면 안내와 함께 '내 티켓 인증' 링크(`/seat-claims/new`)를 둔다: 예매내역 사진을 올리면 관리자가 승인(현재 그 좌석의 활성 티켓을 내림)/거절하고 사진은 처리 후 삭제한다(구현됨, 3.6절). 티켓 내리기는 소프트 삭제(INACTIVE), 본인만, 멱등 204, 남의 티켓 404. 교환 완료로 `EXCHANGED`가 된 티켓은 내리기 409 `TICKET_EXCHANGED`(예약 시도도 같은 코드). 교환으로 받은 새 티켓은 일반 ACTIVE 티켓이다.
- **지난 회차**: 회차 당일 끝(다음날 0시 KST)까지 티켓 등록·매칭 허용, 이후 자동 비활성(등록은 400 `sessionId`). 공연 시작 후에도 매칭·예약·교환 수락이 계속 가능(현장 교환). 티켓 내리기는 예약 중이 아니면 언제든 가능. 이미 시작한 채팅의 예약·취소에는 회차 마감 검사가 없다.
- 본인 좌석을 증명할 수단이 없으므로 채팅에서 예매내역을 서로 확인하도록 안내한다. 추가금은 서비스 밖에서 오가며 결제를 중개하지 않는다.

### 3.2 교환 희망 조건(요청)
- 티켓당 요청 1개(미삭제 기준). 구성: 희망 회차 + 우선순위(요청 단위), 희망 범위 여러 개(범위마다 구역·열 from~to·번 from~to와 **추가금 유형·금액**).
- 서버 안전 상한 5,000석(합집합 기준)·50범위(`exchange.want.*`), 초과 422 `WANT_SEAT_LIMIT_EXCEEDED`/`WANT_RANGE_LIMIT_EXCEEDED`(count/limit 포함). 범위 겹침은 합집합 허용, 겹친 범위의 추가금 유형·금액이 다르면 422 `WANT_EXTRA_CONFLICT`(응답 `conflicts:[[i,j]]`; 완전히 같은 겹침만 허용).
- 자기 좌석 포함은 희망 회차가 내 티켓 회차 하나뿐일 때만 422 `WANT_INCLUDES_OWN_SEAT`(다른 회차가 하나라도 있으면 같은 위치도 허용).
- 내 티켓 회차가 마감이면 422 `SESSION_CLOSED`, 희망 회차가 마감이면 400 `wantSessions[i].sessionId`. 티켓을 내리면 요청이 CLOSED, 이후 수정은 422 `TICKET_NOT_ACTIVE`.
- 삭제는 **소프트 삭제**(DELETED, 멱등 204): 삭제 후 같은 티켓에 새 요청 가능, 희망 범위·좌석·회차 행은 삭제, 삭제된 요청은 후보·수정·`/requests/me`에서 제외(수정·후보 조회는 409 `REQUEST_DELETED`).
- 요청 수정·삭제 시 CHATTING 매칭은 시스템 취소(`canceled_by` NULL) 후 진행, RESERVED 매칭이 있으면 409 `ACTIVE_MATCH_EXISTS`.

### 3.3 추가금
- 유형 4종, **희망 범위 단위**: X(추가금 X, 지불 안 함) / ANY(상관없음) / POS(>0, 받아야만 교환) / NEG(<0, 낼 의향). **금액은 계산에 쓰지 않고 유형만 본다.** 금액 부호: **+는 내가 받을 금액, −는 내가 낼 수 있는 금액**. 금액은 후보 목록에 참고용으로 표시한다. 상한 없음.
- 불성립: POS–POS, POS–X, X–POS. 나머지(X–X, NEG–NEG 포함)는 성립.

### 3.4 후보 조회
`GET /api/exchange/requests/{id}/candidates?page&size`(기본 20, 최대 100). 같은 공연, 상대 티켓 회차 ∈ 내 희망 회차 AND 내 티켓 회차 ∈ 상대 희망 회차, 상대 좌석 ∈ 내 희망 좌석 AND 내 좌석 ∈ 상대 희망 좌석, 추가금 호환, 상대 요청 OPEN·티켓 ACTIVE, 마감 회차 제외, 내 요청·같은 사용자 제외.
- 제외: 예약 잠금 티켓(예약이 취소되면 복귀), 같은 쌍의 열린 매칭. 내 티켓이 잠겨 있으면 후보 조회 422 `TICKET_LOCKED`.
- 정렬: 내 희망 회차 priority -> 상대 요청 최신순(점수·랭킹·신뢰도 없음, '같은 회차 우선'은 적용하지 않음).
- 응답: 상대 좌석·회차·닉네임, `myExtra*`(내 범위에서 상대 좌석이 속한 범위의 추가금), `extraType/extraAmount`(상대 범위에서 내 좌석이 속한 범위의 추가금), `settlementHint`(POS–NEG이고 금액 범위가 겹칠 때 {min,max}, 참고값). 신뢰도 필드 없음.
- SQL은 `STRAIGHT_JOIN` 힌트로 옵티마이저의 풀스캔을 막는다. 후보 화면은 keyset(커서) 조각 `GET .../candidates/scroll`(전체 개수·offset 없음; `page&size` 엔드포인트는 호환용)으로 읽는다.
- **차단·제재 제외**: 차단(양방향)과 이용 정지 사용자의 티켓은 후보에서 제외한다(V13).
- 후보 카드의 '예약 중' 뱃지는 **폐기**한다. 후보에서는 예약 잠긴 티켓을 뺀다. 예약 중 정보는 예약한 두 사람의 내 매칭 카드에서만 보이고, 예약 전부터 같은 자리로 채팅 중이던 다른 사용자의 카드에는 '(다른 사용자와 예약 중인 좌석)' 문구만 뜬다.

### 3.5 매칭 상태와 예약·교환 수락
- 상태: CHATTING(후보를 골라 채팅 시작) -> RESERVED(예약, 화면 라벨 '예약 중') -> COMPLETED(화면 라벨 '교환 완료'), 또는 CANCELED. 열린 매칭 = CHATTING 또는 RESERVED.
- 한 요청(티켓)에 CHATTING 매칭을 **여러 개 동시에** 둘 수 있고, 같은 쌍의 열린 매칭은 1개(409 `MATCH_ALREADY_OPEN`). 제안 시 후보 재검증 실패는 422 `NOT_A_CANDIDATE`.
- **예약(예약 제안 → 상대 수락, V15)**: CHATTING 매칭에서 한 명이 `reserve`(예약 제안)하면 제안 컬럼(`reserve_proposed_by_id`·`reserve_proposed_at`)만 채워지고 상태는 CHATTING 그대로다(응답 `reservationProposal`: NONE·MINE·THEIRS). 상대가 `reserve/accept`로 수락해야 RESERVED가 되고 두 티켓이 잠긴다(`exchange_ticket_lock`). `reserved_by_id`는 제안자, `reserved_at`은 수락 시각이다. 상대는 `reserve/decline`(제안 삭제, CHATTING 유지), 제안자는 `reserve/withdraw`(제안 철회)로 제안만 지운다. 다른 매칭에서 잠겼으면 제안·수락 모두 409 `TICKET_ALREADY_RESERVED`다. 이미 RESERVED면 수락한 쪽은 멱등 200, 제안자가 누르면 409 `RESERVATION_PROPOSAL_OWN`. 상대가 이미 제안했는데 제안하면 409 `RESERVATION_PROPOSAL_PENDING`. 예약 취소(`unreserve`, 참여자 누구나)로 CHATTING 복귀(매칭·채팅 유지, 잠금 해제, 같은 쌍도 바로 재제안 가능, `reserved_*`·완료 표시 초기화). 예약-취소 남용 방지 장치는 없다(차단·신고로 다룸). 예약 제안은 채팅 화면에서만 하며 내 매칭 카드에는 예약 버튼이 없다. 예약된 티켓의 새 제안·새 예약 제안·수락만 막히고 이미 열려 있던 다른 채팅은 유지된다. 한 명이 즉시 RESERVED로 만드는 방식은 폐기됐다(9절).
- **예약 중 채팅 종료(cancel)와 받은 쪽 거절(reject)은 막는다**(409 `MATCH_STATE_CONFLICT` '먼저 예약을 취소해주세요.') — 끝내려면 먼저 예약을 취소한다. reject는 제안받은 b측만, cancel은 참여자 누구나. 불허 전이는 409 `MATCH_STATE_CONFLICT`.
- 취소: 상태만 CANCELED로 바뀌며(`canceled_by_id`, 시스템 취소는 NULL) **재매칭 불가가 아니다.** 재매칭 불가는 상대를 차단하거나 신고했을 때만. 양쪽 완료 전에는 누구든 취소 가능. 시스템 취소는 CHATTING만 대상. 잠긴 티켓을 내리면 409 `TICKET_RESERVED`, 잠기지 않았으면 요청 CLOSED + 그 티켓의 CHATTING 매칭 시스템 취소.
- 버튼 이름: 화면 버튼은 **'교환 수락'**(2단계 확인), 양쪽이 모두 눌러야 COMPLETED이고 상태·뱃지·이력 라벨은 '교환 완료'. 내부 값·API(`complete`)·컬럼(`a/b_completed_at`)은 그대로. 예약 중에만 '교환 수락' 버튼이 생기며, 한쪽이 눌렀어도 양쪽 완료 전이면 예약 취소가 가능하고 수락 표시는 초기화된다. 한쪽만 완료하고 방치해도 자동 완료·취소는 없다(7일 경과 알림은 2026-10-13 보류). 첫 수락은 `*_completed_at`만 기록하고 RESERVED를 유지한다. `complete`는 CHATTING·CANCELED·COMPLETED에서 409 `MATCH_STATE_CONFLICT`, 내가 이미 수락한 RESERVED에서는 멱등 200.
- **교환 완료 시 티켓 처리**: 두 사람이 모두 수락하는 순간 한 트랜잭션에서 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT한다(소유자 그대로, 회차·구역·열·번은 상대의 기존 티켓 값). 완료된 매칭은 취소 불가, EXCHANGED 티켓은 내리기 불가. 매칭 행의 좌석은 항상 **교환 전 자리**이고 교환 후 자리는 새 티켓·`exchange_history`(old_ticket_id/new_ticket_id)에서 본다. 교환 이력은 `exchange_history`에 저장되며(V9), 마이페이지 '교환 이력' 화면(`/exchange/history`)과 `GET /api/exchange/history`로 `(기존 자리) -> (바꾼 자리)`를 최신순으로 보여준다(읽기 전용, 2026-10-12 구현).
- **교환 완료 후 다른 채팅**: 기존 티켓의 교환 요청은 CLOSED로 닫는다. 그 티켓에 걸려 있던 다른 채팅 매칭은 **자동 취소하지 않는다.** 그 카드에서 교환 수락·교환 완료 버튼과 채팅 화면의 예약 제안·수락 버튼을 비활성화하고 "이미 교환된 좌석이에요" 메시지를 **본인과 상대방 모두에게** 보여준다(판단 기준은 티켓 상태 EXCHANGED).
### 3.6 신고·차단·후기·관리자
- 후기 기능은 만들지 않는다. 신뢰도 점수는 매칭에 쓰지 않는다(우선순위는 사용자가 정한 것). 신뢰도 점수 컬럼은 V18에서 제거했다.
- **사용자 차단·신고·이용 정지(구현됨, V13)**: 채팅 화면에서 차단(양방향 효과: 후보 제외, 둘 사이 열린 매칭 시스템 취소·잠금 해제, 상대에게 알리지 않음, 해제 `/my/blocks`)·신고(사유 5종, 매칭당 OPEN 1건, 사용자당 OPEN 10건, 자동 제재 없음, `/my/reports`). 관리자는 `/admin/reports`에서 채팅 근거를 보고 처리/기각하고 이용 정지(7·30일/무기한)를 걸 수 있으며 `/admin/sanctions`(해제)·`/admin/blocks`(현황)로 관리한다. 정지 계정은 모든 인증 API·로그인이 403 `ACCOUNT_SUSPENDED`.
- **관리자 운영 대시보드(2026-10-21)**: `/admin` 첫 화면, `GET /api/admin/dashboard`(수치·14일 추이·대기열·최근 목록·알림). 관리자 화면은 정보 밀도 우선(`AdminLayout`·`adminUi`, 목록은 표).
- **관리자 페이지(구현됨, V12)**: 관리자는 DB에서 `users.role=ADMIN`을 직접 부여하고 `/api/admin/**`는 ADMIN만, 프론트 `/admin`은 `AdminRoute`. ①공연 수정 제안(`performance_edit_proposal`): 제목·공연장만, 관리자 승인으로만 공연 수정, 사용자당 PENDING 3건, 알림 없음(`/my/edit-proposals`). ②좌석 소유 인증(`seat_claim`): 사진(jpeg/png/webp ≤5 MiB, `matjari.upload.dir`) 업로드→관리자 승인 시 기존 활성 티켓 INACTIVE(요청 CLOSED, CHATTING 매칭 취소, 예약 잠금이면 409), 처리 후 사진 삭제, 사용자당 PENDING 5건(`/my/seat-claims`). 상세는 wiki/decisions/admin-design.md.

### 3.7 공연 등록
- **최신(2026-10-22~23, 아래 옛 문구보다 우선)**: 지원 사이트는 YES24·티켓링크·멜론·인터파크(NOL 상품 주소 `nol.yanolja.com/ticket/products/{id}`, 옛 인터파크 도메인은 robots 금지라 요청 안 함). 관리자는 티켓 없이 공연만 등록하고 AI 월 횟수 제한이 없다. 회차는 AI가 상세 본문 글자+상세 이미지의 맥락으로 먼저 읽고 정규식은 보조다. 상세 이미지 출처: 멜론·인터파크는 HTML, 티켓링크는 renderer 컨테이너(정식 Chromium headless, CDP), YES24는 봇 차단이라 글자만. 구역 지도가 없으면 좌석표 이미지 올리기(월 3회 공유), 사람이 검수한 정답(멜론 38장)과 상품·이미지가 같으면 AI 없이 그대로 쓴다. 크레딧이 떨어지면(`AiCredit`) 등록 화면은 직접 입력만. 처음 보는 지도 35장 IoU≥0.8 79.1%. 기준 문서는 CLAUDE.md 'AI 회차·구역 읽기' 절과 `wiki/decisions/ticketing-site-reading.md`.
- 로그인 사용자 누구나 등록. 필드: 티켓팅 링크(sourceUrl), 제목, 공연장 이름(텍스트 필수 1~100자, 공백 정리), 회차(시각은 직접 입력, 10분 단위, 과거 불가. 2일 이하 링크 공연은 날짜 칸을 미리 채움). 중복 판정은 링크 정규화 값 `source_key`(utf8mb4_bin) unique: 인터파크·멜론·YES24·티켓링크는 `{site}:{productId}`, 그 외는 호스트+경로+정렬 쿼리+프래그먼트. 중복 409 + performanceId.
- **공연은 등록 후 아무도 수정·삭제하지 못한다**(제목·공연장·회차 추가/수정/삭제 모두 불가). 변경은 관리자 페이지의 '수정 제안'(제목·공연장만, 승인으로만 반영)으로만(정책 메모 `산출물/04_요구사항정의서/FR-02_공연정보_정정정책_후속과제.md`).
- 공연 목록 검색은 제목만. 공연장 별도 테이블·검색·정식 등록(VERIFIED)·필터는 없다. 공연은 티켓 등록과 함께만 등록한다(2026-10-21): 등록 화면은 4단계(①링크 중복 확인 ②공연 정보 ③내 티켓 ④확인·등록), 공연과 티켓은 한 트랜잭션에 따로 저장되고 공연에는 티켓이 연결·표시되지 않는다.
- **링크 기반 자동 입력(구현됨)**: `GET /api/performances/preview`로 링크에서 제목·공연장·공연 기간을 읽어(회차 시각은 세 사이트 모두 Ajax라 읽지 못함, 직접 입력) 다음 단계에 미리 채우고 '이 정보가 맞나요?'를 묻고, 아니면 직접 수정 후 등록. 공개 페이지(JSON-LD·og)만 읽고 자동 등록은 없으며 직접 입력은 항상 가능. 대상은 YES24·티켓링크(JSON-LD)·멜론(본문 HTML), 인터파크는 robots.txt로 제외. SSRF 방어: 사용자 URL을 요청하지 않고 `SourceKeyResolver` 키(`site:숫자id`)로 고정 템플릿 URL만 만들며, 리다이렉트 불가·공인 IP·https·2 MiB·10초·사용자당 분당 10회·10분 캐시. 구현은 `service/linkinfo/`. 로그인·캡차 우회와 내부 API 조사는 하지 않는다. **회차 날짜 미리 채우기(구현됨, DB 변경 없음, 2026-10-13)**: 미리보기 응답 `sessionCandidates`(`[{date, time:null}]`, 항상 배열)는 공연 기간 시작·종료가 모두 있고 2일 이하일 때만 날짜를 담는다. 등록 2단계는 첫 날짜를 날짜 칸에 채우고 '날짜 바로 채우기'로 바꾸며, 회차 추가 때 다음 후보 날짜를 채운다. 시각은 직접 고르고 회차는 자동 등록하지 않는다. 구현은 `PerformancePreviewResponse.candidatesFromPeriod`, `SessionDateTimePicker.datePreset`. 상세는 백엔드 README '회차 날짜 후보' 절.

- **공연 포스터 표시(구현, DB 변경 없음)**: 서버는 이미지를 저장·프록시하지 않고 링크 페이지의 og:image(없으면 JSON-LD `Event.image`)에서 **주소 문자열만** 읽는다. 브라우저가 원 사이트에서 직접 불러온다(인라인 링크). 허용 호스트는 사이트별 고정(YES24 `tkfile.yes24.com`, 티켓링크 `image.toast.com`, 멜론 `cdnticket.melon.co.kr`), https만. 로드 실패(핫링크 차단 포함)나 주소 없음이면 포스터 영역을 숨기고 대체 링크는 두지 않는다. 출처는 텍스트로만 표기한다. 공연 상세는 `GET /api/performances/{id}/poster`, 등록 2단계는 미리보기 `posterUrl`을 쓴다. 스위치 `matjari.poster.enabled`(기본 true). 권리 확인은 공개 운영 전 해야 한다(요구사항 §7 Q-26). 상세: `wiki/decisions/poster-display-design.md`.

### 3.8 인증
- 이메일 가입·로그인, JWT access/refresh (`POST /api/auth/signup|login|refresh`). 인증 사용자 식별은 토큰 **userId** 기준(삭제된 사용자·파싱 오류만 401).
- **소셜 로그인(구현, V14 `social_account`)**: 카카오·구글·네이버 3종, 백엔드가 Authorization Code를 직접 처리(구글·카카오 PKCE, 네이버는 state 쿠키). 같은 소셜 계정이면 그 사용자로 로그인하고, 없으면 가입한다. 제공자가 준 **검증된** 이메일이 기존 `users`에 있으면 **자동 연결하지 않고** `EMAIL_EXISTS`로 막는다. 이메일이 없거나 미검증이면 `{provider}.{providerUserId}@social.matjari.invalid` 자리표시 이메일과 무작위 비밀번호 해시로 가입하므로 비밀번호 로그인은 안 된다. 콜백은 60초짜리 일회용 로그인 코드를 `/auth/callback#code=`(프래그먼트)로 넘기고, 프런트가 `POST /api/auth/social/exchange`로 JWT를 받는다. 실패는 `/login?socialError=` 코드로 안내한다. 정지·차단 규칙은 일반 사용자와 같다. 상세는 CLAUDE.md '소셜 로그인' 절과 백엔드 README '소셜 로그인 (V14)' 절.
- **닉네임 수정·소셜 계정 연결·해제(구현, V16)**: 마이페이지에서 닉네임을 고친다(가입과 같은 규칙, 중복 허용, `PATCH /api/users/me`). 소셜 계정은 로그인한 본인이 마이페이지 '소셜 계정 연결'에서 명시적으로 연결·해제한다. 연결은 콜백이 저장하지 않고 **로그인 세션이 확정**한다(연결 확정 코드 60초 1회, 확정 사용자와 코드 사용자가 같을 때만 저장). 사용자당 제공자 1개(UNIQUE(user_id, provider)). 해제는 마지막 로그인 수단이면 409 `LAST_LOGIN_METHOD`(`password_login_enabled`=false이고 연결이 하나뿐). 같은 이메일이어도 자동으로 합치지 않는다. 자리표시 이메일은 응답에서 `email=null`, `emailPlaceholder=true`. 비밀번호·이메일은 바꾸지 않는다. 상세는 백엔드 README '프로필 수정·소셜 계정 연결 (V16)' 절.
- 가입 규칙(DTO·`AuthInputNormalizer`·프론트 `authValidation.ts` 세 곳에 중복, 변경 시 모두 수정): 이메일 trim+소문자 100자 이하, 비밀번호 8~64자이며 UTF-8 72바이트 이하(bcrypt 한계, **로그인도 72바이트 초과를 사전 차단 유지**), 닉네임 trim 후 2~20자·보이지 않는 문자 불가·중복 허용. 이메일 중복은 400 `{"email": ...}`.
- 401=미인증(프론트가 single-flight로 refresh 후 1회 재시도), 403=권한 없음(재발급 안 함). 오류 포맷은 400/401/403/404/409 통일(`{message}` 또는 `{field: msg}`).

### 3.9 알림 (구현, V10 `notification`)
- 유형 6종(V15): `PROPOSAL_RECEIVED`(새 제안, 수신자=제안받은 쪽), `RESERVATION_PROPOSED`(상대의 예약 제안, 수신자=제안받은 쪽), `RESERVATION_REJECTED`(예약 제안 거절, 수신자=제안자), `MATCH_RESERVED`(상대가 예약을 수락, 수신자=제안자), `RESERVATION_CANCELED`(상대가 예약 취소), `CHAT_MESSAGE`(새 채팅 메시지, 수신자=상대). 수신자는 상대편이다. 철회는 알림이 없다.
- 쓰기: propose·예약 제안·수락·거절·unreserve 트랜잭션과 채팅 전송의 **맨 끝**에서 `NotificationService.notify`(MANDATORY)로 쓰며, 매칭 상태가 실제로 바뀐 경우에만 한다(채팅은 저장 성공 뒤, 멱등 재전송 제외). 한 트랜잭션에서 수신자당 1건. 채팅 읽음 포인터가 올라가면 그 (수신자, 매칭)의 `CHAT_MESSAGE`를 읽음 처리한다(`markChatRead`, 매칭·users 잠금 밖의 독립 트랜잭션).
- 중복 방지: `dedup_key` 단일 유니크. 새 제안만 `PROPOSAL_RECEIVED:{matchId}`. 예약 제안·채팅은 `RESERVATION_PROPOSED:{matchId}:{recipientId}`·`CHAT_MESSAGE:{matchId}:{recipientId}`로 (수신자, 매칭)당 1행을 재사용한다. 쓸 때는 PK 행을 먼저 갱신(`read_at`=NULL, `created_at`=now)하고 없을 때만 `INSERT … ON DUPLICATE KEY UPDATE`로 만든다(순수 upsert는 '읽은 알림 삭제'와 교착). 수락·거절·예약 취소는 반복마다 1건이라 NULL. 재예약 때 롤백되는 `(user, type, match)` 유니크는 쓰지 않는다.
- 닫힌 매칭(CANCELED·COMPLETED) 알림도 안 읽은 개수·목록에 남고 회색 '종료된 매칭' 라벨만 붙는다. 제거 경로는 사용자의 '읽은 알림 삭제'(읽은 것만)뿐이다.
- 조회 API는 본인 것만 보이며 남의 알림은 404다. 목록 정렬은 현재 id 내림차순이라 되살린 알림은 원래 자리에 남는다(알려진 한계). 읽음·삭제는 잠금 없는 SELECT로 id를 모은 뒤 500개씩 `WHERE id IN`으로 처리한다(범위 UPDATE/DELETE 금지).
- 화면: 헤더 벨(안 읽은 개수, 데스크톱은 '내 매칭' 오른쪽·햄버거 폭은 햄버거 왼쪽) → 말풍선(최신 5건, 모두 읽음, 알림 전체 보기) → `/notifications`. 알림 클릭은 읽음 처리 후 이동한다: 채팅 메시지·예약 제안은 `/exchange/matches/{id}/chat`, 나머지는 `/exchange/matches?role=…&matchId=…`로 카드를 강조한다. 새 알림은 STOMP 푸시(`/user/queue/notifications`, 신호만)로 즉시 반영하고, 연결이 끊겼을 때만 화면이 보일 때 30초 폴링한다.
- 보류(2026-10-13 사용자 결정): 7일 경과 알림. 상대 매칭 카드·채팅으로 충분하다고 판단했고, 필요해지면 안 누른 쪽에만 하루 한 번 도는 작업으로 매칭당 1회 보낸다.

### 3.10 채팅 (구현, V11 `chat_message`·`chat_read_state`)
- 매칭 한 건마다 참여자 둘이 나누는 텍스트 채팅. 참여자가 아니면 404. 매칭 상태와 무관하게 매칭 단위로 열린다.
- **보내기는 REST(`POST /api/exchange/matches/{id}/messages`)만**, **받기는 STOMP 사용자 큐(`/user/queue/chat`)만** 쓴다. 클라이언트가 보내는 STOMP 프레임은 전부 거부. 같은 `clientMessageId` 재전송은 중복 저장하지 않는다(멱등).
- **종료 후 읽기 전용**: CANCELED·COMPLETED는 열람만 가능하고 쓰기는 409 `MATCH_STATE_CONFLICT`. CHATTING·RESERVED는 읽기·쓰기 모두 가능(교환된 좌석이 걸린 채팅 포함).
- **시스템 메시지는 저장하지 않는다**. 예약·취소·교환 완료 같은 상태 변화는 채팅 화면 상단 상태 배너로만 보여준다.
- **읽음**은 '마지막으로 읽은 메시지' 포인터 하나(앞으로만 이동, 단조 증가). 매칭 카드에는 안 읽은 개수 뱃지, 내가 보낸 마지막 메시지 아래에는 상대가 읽었으면 '읽음'.
- 본문은 텍스트만 1000자(코드 포인트) 이하. 이미지·파일 첨부 없음, 민감정보 가리기 안내. 메시지는 무기한 보관하며 삭제·수정 불가.
- 실시간 연결(STOMP)은 로그인한 앱 전체가 탭당 하나를 공유한다(알림 푸시와 채팅). 새 메시지는 수신자별 `CHAT_MESSAGE` 알림 1행을 재사용해 헤더 벨에 표시한다(헤더 메시지 아이콘은 없다). 새 메시지는 매칭 `updated_at`을 바꾸지 않는다.
- 연결 제한: 사용자당 동시 연결 5개, 구독 목적지는 `/user/queue/chat`·`/user/queue/notifications` 두 개뿐이고 목적지당 구독 1개. 허용 Origin은 `matjari.websocket.allowed-origins` 설정으로만 연다(와일드카드 금지).
- 서버 전송 경로의 속도 제한(사용자별 429 `CHAT_RATE_LIMITED`)과 본문 정규화 세부 규칙은 backend 코드·`ChatService` 주석을 따른다.

### 3.11 회원탈퇴·개인정보처리방침 (구현, V17 `users.withdrawn_at`)
- `DELETE /api/users/me`: 비밀번호 로그인 계정은 비밀번호, 소셜 전용 계정은 확인 문구 '탈퇴합니다'와 연결된 소셜 계정으로 방금 다시 인증한 `reauthCode`(연결된 제공자가 모두 꺼져 있으면 생략)를 보내야 한다. ADMIN은 409 `ADMIN_CANNOT_WITHDRAW`. 이용 정지 중에도 이 API는 허용한다(필터 예외; 비밀번호가 맞는 정지 로그인은 403 본문에 탈퇴 전용 10분 토큰 `withdrawToken`을 준다, 소셜 전용 정지 계정은 문의처).
- 한 트랜잭션: PENDING 좌석 인증 요청 거절 → 열린 매칭(CHATTING·RESERVED) 시스템 취소와 예약 잠금 해제 → OPEN 요청 CLOSED·활성 티켓 INACTIVE(2차 정리 포함) → `social_account` 전부 삭제 → users 익명화(이메일 `withdrawn.{id}@withdrawn.matjari.invalid`, 닉네임 '탈퇴한 회원', 무작위 비밀번호, 비밀번호 로그인 불가, `withdrawn_at` 기록) → 수신자 알림 삭제 → 커밋 뒤 STOMP 연결 종료.
- 진행 중 교환은 예약 중이어도 모두 자동 정리한다(사용자 결정). 채팅·읽음 상태·교환 이력·차단·신고·제재·PENDING 공연 수정 제안은 지우지 않고, 닉네임이 '탈퇴한 회원'으로 보인다.
- 인증 차단: 탈퇴 계정은 기존 액세스 토큰·STOMP CONNECT·로그인·refresh·소셜 교환·소셜 연결 확정이 모두 거절된다. 같은 이메일·같은 소셜 계정으로 새로 가입할 수 있다.
- 잠금 순서: users 행은 티켓·요청·매칭을 잠근 뒤에 잡는다(제안이 티켓 잠금 뒤 매칭을 INSERT하며 users에 FK 공유 잠금을 걸어, users 먼저 잡으면 교착).
- 방침 `/privacy`(푸터 링크, 공개) 보유 기간: 계정 정보는 탈퇴 즉시 삭제·익명화, 인증 사진은 처리 끝나면 삭제, 받은 알림은 탈퇴 때 삭제, 채팅·교환 이력·차단·신고·제재는 익명화해 서비스 운영 기간 동안 보관하고 종료 때 파기(자동 파기 스케줄 없음).
- 상세와 한계 8개: `wiki/decisions/withdraw-privacy-design.md`. 확정 규칙의 기준은 루트 `CLAUDE.md`.

### 3.12 공연 구역 목록·AI 회차·구역 읽기 (V21, 2026-10-18 `feature/ai-extract-backend`)
- 공연 등록의 선택 필드 `zones[{name, shapePolygons}]`(최대 100개)와 `seatmapAspect`(좌석표 가로/세로 비율, 범위 밖이면 버림)를 `performance_zone`·`performance.seatmap_aspect`에 저장한다. 구역 이름은 공백 정리 후 1~30자(아니면 400 `zones`).
- **구역 규칙**: 정규화 키(`name_key`, 서비스 `SeatKeyNormalizer.zoneKey`)가 같으면 **한 구역**으로 합친다(처음 표기 유지, 모양 조각은 이어 붙이고 8개 초과분은 버림). 모양이 규칙에 어긋나면 오류가 아니라 그 구역의 모양만 버린다. **서버는 목록 밖 구역을 거절하지 않는다**(티켓·교환 조건의 `zone`은 텍스트 규칙으로만 검사). 목록은 입력 편의다.
- 조회: `GET /api/performances/{id}/zones` → `{seatmapAspect, zones[{name, shapePolygons}]}`(sortOrder 순, 구역이 없으면 `zones=[]`, 없는 공연 404). 목록이 있는 공연은 티켓 등록·교환 조건에서 구역을 드롭다운으로 고르고, 없으면 텍스트로 입력한다.
- **AI 읽기**: 등록 2단계의 'AI로 회차·구역 읽기'가 `POST /api/performances/ai-extract`를 부른다. 회차 날짜·시각과 구역 이름·모양을 읽으며, 사용자가 확인·수정한 뒤에만 저장된다(자동 등록 없음). 원본 이미지는 저장하지 않는다. `ANTHROPIC_API_KEY`가 없거나 `matjari.ai.enabled=false`면 규칙 결과만 돌려주고 `aiEnabled=false`다. 상세 이미지 AI 호출은 멜론 상세 이미지가 있을 때만 한다. 제한과 설정은 백엔드 README 'AI 공연 정보 추출' 절.

## 4. 기능 목록과 구현 상태

| 기능 | 상태 |
|---|---|
| 회원가입(이메일 인증번호 포함)·로그인·JWT·`GET/PATCH /api/users/me` | 구현 |
| 공연·회차 등록/조회(FR-02) | 구현 (수정·삭제 없음) |
| 티켓 등록·내 티켓·내리기(FR-03) | 구현 |
| 교환 희망 조건 등록·수정·삭제 | 구현 |
| 후보 조회 | 구현 ('예약 중' 뱃지는 폐기) |
| 제안(채팅 시작)·예약 제안·수락·거절·철회·예약 취소·거절·취소 | 구현 (예약 제안·수락은 V15) |
| 내 매칭 조회 | 구현 |
| 교환 수락(complete)·COMPLETED·티켓 EXCHANGED+새 티켓·교환 이력 기록 | 구현 (V9) |
| 완료된 티켓의 다른 채팅 비활성 처리(버튼 비활성+안내) | 구현 |
| 교환 이력 조회 API·마이페이지 '교환 이력' 화면 | 구현 (V9 테이블 조회, DB 변경 없음) |
| 채팅(매칭별 텍스트: REST 전송·조회·읽음, STOMP 사용자 큐 수신, 채팅 화면, 종료 후 읽기 전용) | 구현 (V11) |
| 알림 6종(새 제안·예약 제안·예약 제안 거절·상대 예약 수락·상대 예약 취소·새 채팅 메시지, 벨 말풍선·알림 페이지·읽은 알림 삭제, STOMP 실시간 푸시, 데스크톱 벨 위치) | 구현 (V10, V15, 푸시는 DB 변경 없음) |
| 7일 경과 알림 | 보류(2026-10-13 사용자 결정, 알림 구조는 type 추가로 확장 가능) |
| 사용자 차단·신고·이용 정지·관리자 신고 관리 | 구현 (V13) |
| 소셜 로그인(카카오·구글·네이버, 자동 연결 없음) | 구현 (V14) |
| 닉네임 수정·소셜 계정 연결·해제(마이페이지, 본인이 명시적으로 연결, 로그인 세션 확정) | 구현 (V16) |
| 링크 기반 공연 정보 자동 입력(제목·공연장·공연 기간) | 구현 (DB 변경 없음) |
| 공연 포스터 표시(인라인 링크, 허용 호스트 3종, 로드 실패 시 숨김) | 구현 (DB 변경 없음, 병합됨) |
| 회차 날짜 미리 채우기(공연 기간 100일 이하의 모든 날짜, 시각은 직접 입력·오전/오후 기본 오후·10분 단위 선택) | 구현 (DB 변경 없음, 병합됨) |
| 관리자 페이지·공연 수정 제안·내 티켓 인증 | 구현 (V12) |
| 회원탈퇴(모달·확인, 익명화, 진행 중 교환 자동 정리)·개인정보처리방침 `/privacy`·푸터·가입 화면 방침 안내 | 구현 (V17, 병합됨) |
| 신뢰도 점수 제거(`users.trust_score` 컬럼, 응답 `trustScore`, 마이페이지 표시 삭제) | 구현 (V18, 미커밋) |
| 공연 구역 목록 저장·조회, 티켓·교환 조건 구역 드롭다운 | 구현 (V21) |
| AI 회차·구역 읽기(등록 2단계 'AI로 회차·구역 읽기', 사용자 확인 후 저장) | 구현 (읽기 자체는 DB 변경 없음, 결과 저장은 V21 구역 테이블. 키가 없으면 규칙 결과만, 사용자·전체 하루 호출 상한) |

## 5. 데이터 모델 (Flyway V1~V21, 22개 테이블 + flyway_schema_history)

기준 다이어그램 `산출물/08_ERD/erd.dot`, 설계 근거 `산출물/08_ERD/exchange-schema-design.md`. 규칙은 `erd-conventions` 스킬.

| 테이블 | 요지 |
|---|---|
| `users` | role USER/ADMIN, `password_login_enabled` TINYINT(1) NOT NULL 기본 1(V16, 일반 가입 1·소셜 가입 0, 마지막 로그인 수단 판단), `withdrawn_at` DATETIME(6) NULL(V17, NULL=활성, 값이 있으면 탈퇴 후 익명화) |
| `performance` | `venue_name` VARCHAR(100) NOT NULL, `source_key` unique, 등록자 FK, `seatmap_aspect` DECIMAL(6,3) NULL(V21, 좌석표 가로/세로 비율, 0 초과) |
| `performance_session` | 회차, `starts_at` 분 단위, UK(performance_id, starts_at) |
| `performance_zone` | 공연별 구역 목록(V21). `performance_id` FK, `name` VARCHAR(30), `name_key` VARCHAR(50) bin(티켓 `zone_key`와 같은 정규화), UK(name_key, performance_id)로 같은 이름은 한 구역, `sort_order`, `shape_polygons` JSON NULL(다각형 조각 배열, 0~1000 정규화 좌표, CHECK는 구조만). 티켓·교환 조건과 FK 없음 |
| `ticket` | 회차·소유자 FK, `zone/row/col_label`(표시용)+`_key`(정규화), `status` ACTIVE/INACTIVE/EXCHANGED(V9, 교환 완료된 기존 티켓), 생성 컬럼 `active_flag`, UK `uk_ticket_active_seat`(회차·구역·열·번·active_flag), `idx_ticket_user_status`. `seatmap_id` 없음 |
| `exchange_request` | 티켓당 미삭제 요청 1개(생성 컬럼 `live_flag` + `uk_exchange_request_live_ticket`), status OPEN/CLOSED/DELETED, `deleted_at` |
| `exchange_want_range` | 입력 범위(구역·열 from/to·번 from/to) + 범위별 `extra_type`(X/ANY/POS/NEG)·`extra_amount` |
| `exchange_want_seat` | 펼친 희망 좌석, PK(request+zone/row/col key), 범위의 추가금을 비정규화해 보유 |
| `exchange_want_session` | 희망 회차 + priority |
| `exchange_match` | request_a/b·ticket_a/b·user_a/b FK, status CHATTING/RESERVED/COMPLETED/CANCELED, `reserved_by_id`(FK users, 예약 수락 때 제안자)·`reserved_at`, `reserve_proposed_by_id`(FK users, 예약 제안자, NULL)·`reserve_proposed_at`(V15, 둘 다 NULL이거나 둘 다 NOT NULL, CHATTING이 아니면 NULL), `a/b_completed_at`, `canceled_by_id`(NULL=시스템)·`canceled_at`, 추가금 스냅샷 4컬럼(`a/b_extra_type`, `a/b_extra_amount`), 생성 컬럼 `request_low/high`·`open_flag`로 같은 요청 쌍 열린 매칭 1개 UK, CHECK 다수(`ck_exchange_match_distinct_tickets`·`_distinct_requests`·`_status`·`_a_extra`·`_b_extra`·`_canceled`·`_reserved`·`_reserved_by_party`·`_completed`·V15의 `ck_exchange_match_reserve_proposal_pair`·`_status`·`_proposed_by_party`). `a/b_reserved_at`은 DEPRECATED 레거시 컬럼(삭제하지 않음) |
| `exchange_ticket_lock` | PK ticket_id, match_id, 두 FK ON DELETE CASCADE (RESERVED일 때 매칭당 2행) |
| `exchange_history` | 교환 완료 이력(append-only, V9). `match_id`·`user_id`·`performance_id`·`old_ticket_id`·`new_ticket_id` FK, 공연 제목·공연장과 기존/새 자리(회차 시작 시각·구역·열·번) 스냅샷, `old_ticket_id`·`new_ticket_id` 각각 UNIQUE, CHECK old≠new. 매칭 1건당 2행(사용자별 1행) |
| `notification` | 알림(V10). `user_id`(수신자 FK)·`match_id`(FK, NOT NULL)·`type`(6종, `ck_notification_type`, V15 확장), `read_at`(NULL=안 읽음), `dedup_key` UNIQUE NULL(새 제안 `PROPOSAL_RECEIVED:{matchId}`, 예약 제안·채팅은 `RESERVATION_PROPOSED`·`CHAT_MESSAGE`를 `{type}:{matchId}:{recipientId}`로 (수신자, 매칭)당 1행 재사용, `ck_notification_chat_dedup`이 CHAT_MESSAGE 키 형식을 강제), 생성 컬럼 `unread_user_id`(안 읽은 개수 전용 `idx_notification_unread`, FK 아님). FK 2개는 ON DELETE/UPDATE 미지정 |
| `chat_message` | 채팅 메시지(V11, append-only). `match_id`·`sender_id`(FK), `content` VARCHAR(1000), `client_message_id` VARCHAR(36) ascii_bin, UK(`client_message_id`, `sender_id`) 멱등, 단일 컬럼 KEY(match/sender). 수정·삭제 없음 |
| `chat_read_state` | 읽음 포인터(V11). PK(`match_id`, `user_id`), `last_read_message_id` BIGINT(FK 아님, 단조 증가), `updated_at`. FK 2개(match·user), KEY(user) |
| `social_account` | 소셜 로그인 계정 연결(V14). `user_id` FK users, `provider` GOOGLE/KAKAO/NAVER(CHECK), `provider_user_id`(utf8mb4_bin), UNIQUE(provider, provider_user_id)와 UNIQUE(user_id, provider)(V16, 사용자당 제공자 1개), 단일 컬럼 KEY(user_id). 이메일·프로필은 저장하지 않는다. 행은 연결 때 INSERT, 해제 때 DELETE(갱신 없음). 연결 확정·해제는 users 행을 먼저 잠근다. `users`는 V16에서 `password_login_enabled`만 추가됐다 |

관계: User 1:N Performance/Ticket, Performance 1:N PerformanceSession, PerformanceSession 1:N Ticket, Ticket 1:0..1 미삭제 ExchangeRequest, ExchangeRequest 1:N WantRange/WantSeat/WantSession(자식 3개는 request ON DELETE CASCADE), ExchangeRequest·Ticket·User 1:N ExchangeMatch(a/b측), ExchangeMatch 1:N ExchangeTicketLock(최대 2). 교환 판정은 회차가 속한 **공연이 같은지** 기준.

- V9 `exchange_history`·`ck_ticket_status` 변경은 교환 완료 구현과 함께 추가했다(재실행 가능한 프로시저 패턴). V10 `notification`은 알림 기능과 함께, V11 `chat_message`·`chat_read_state`는 채팅 기능과 함께 추가했다(V11도 재실행 가능한 프로시저 패턴).
- 설계 규칙: FK에 쓰이는 인덱스에 갱신 컬럼(status 등)을 붙이지 않고 **단일 컬럼**으로 둔다(UPDATE가 부모 행에 S 잠금을 걸어 교착). 잠금 순서는 티켓 id↑ -> 요청 id↑ -> 매칭(요청만 잠그는 update/delete는 티켓을 잠그지 않음).
- V2는 venue 삭제·`venue_name` 백필, V3 티켓 좌석 컬럼(ticket 행이 있으면 SIGNAL 가드), V4 교환 희망 4개, V5 매칭·잠금, V6 범위별 추가금, V7 요청 소프트 삭제, V8 단일 예약자, V9 교환 이력·EXCHANGED, V10 알림, V11 채팅, V15 예약 제안·알림 6종. V2·V3·V6~V18은 재실행 가능한 프로시저 패턴. 이미 적용된 마이그레이션 파일을 수정하면 체크섬 불일치로 기동 불가(롤백은 `docker compose down -v`).
- **V12**: `performance_edit_proposal`(공연 수정 제안: new_title/new_venue_name/reason, status PENDING|APPROVED|REJECTED, old_* 스냅샷, 같은 사용자가 같은 공연에 PENDING 1건은 생성 컬럼+UNIQUE, 사용자당 PENDING 3건은 서비스 검사), `seat_claim`(좌석 좌표+사진 메타 image_key/content_type/size/deleted_at, status, resolved_ticket_id는 FK 아님).
- **V13**: `user_block`(blocker_id, blocked_id, UNIQUE 쌍), `abuse_report`(reason 5종, status OPEN/RESOLVED/DISMISSED, OPEN은 신고자·매칭당 1건은 생성 컬럼+UNIQUE, sanction_id는 FK 아님), `user_sanction`(SUSPEND, ends_at NULL=무기한, revoked_at).
- **V14**: `social_account`(소셜 로그인 연결, 재실행 가능한 프로시저 패턴, 파괴적 단계 없음).
- **V15**: `exchange_match.reserve_proposed_by_id`·`reserve_proposed_at`(CHECK 3종, FK는 `reserved_by_id`와 같은 방식), `notification.type` CHECK를 6종으로 확장하고 `ck_notification_chat_dedup`(CHAT_MESSAGE 키 형식 강제) 추가. 테이블 수는 20 그대로, 재실행 가능한 프로시저 패턴.
- **V16**: `users.password_login_enabled`(일반 가입 1, 소셜 가입 0, 백필은 `social_account` 보유자 0)와 `social_account` UNIQUE(user_id, provider)(사용자당 제공자 1개). 재실행 가능한 프로시저 패턴, 파괴적 단계 없음. 다음 마이그레이션은 V17.
- **V17**: `users.withdrawn_at` DATETIME(6) NULL 추가(인덱스·CHECK·백필 없음, 재실행 가능한 프로시저 패턴, 파괴적 단계 없음). 익명화 값은 서비스가 같은 트랜잭션에서 쓴다. 다음 마이그레이션은 V18.
- **V18**: `users.trust_score` 컬럼 제거(폐기된 신뢰도 점수, 가입 때 0.0만 저장되던 값). 재실행 가능한 프로시저이며 컬럼이 있을 때만 DROP한다. 값은 복구되지 않고 되돌리기는 롤포워드만 가능하다. 엔티티 필드와 `/api/users/me`의 `trustScore`도 함께 없앴다. 테이블 수 20 그대로.
- **V20**: `performance_edit_proposal.session_changes`(JSON NULL, 공연 수정 제안의 회차 변경 목록 ADD/CHANGE/REMOVE). 테이블 수는 그대로다.
- **V19**: `email_verification`(가입 전 이메일 인증번호: PK email, code_hash HMAC-SHA256, attempts, sent_at, expires_at). users와 FK 없음, 가입 성공 시 행 삭제(1회용). 재실행 가능한 프로시저 패턴, 파괴적 단계 없음.
- **V21**: `performance_zone` 신설(공연별 구역 목록, 같은 이름은 한 구역, 모양은 JSON 다각형 조각 배열) 과 `performance.seatmap_aspect`(좌석표 비율) 추가. 재실행 가능한 프로시저 패턴, 파괴적 단계 없음. 테이블 수 21 -> 22. 되돌리기는 롤포워드.
- **V23**: `ai_extract_cache`(PK `source_key`, `result` JSON, `created_at`, `expires_at_ms`): AI 읽기 결과 링크별 168시간 보관(FK 없음).
- **V24**: `ai_extract_usage`(id, `user_id` FK, `source_key`, `used_at`, `refunded_at`): AI 불러오기 사용자당 월 3회 기록, 실패 시 refunded_at.
- **V25**: `admin_action_log.action` CHECK에 `PERFORMANCE_DELETE` 추가(숨긴 공연 완전 삭제, 연결 기록 없을 때만).
- **V26**: `ai_call_log`(called_at, stage, model, input/output_tokens, cost_micro_usd): AI 호출별 예상 비용, 대시보드 남은 크레딧 추정.
- **다음**: V27부터. 현재 계획된 스키마 변경은 없다(7일 경과 알림은 보류).

## 6. API 요약

모든 `/api/exchange/**`·`/api/tickets/**`는 로그인 필요. 남의 요청·티켓은 403(없는 id는 404), 남의 매칭 상세는 404.

| 영역 | 엔드포인트 |
|---|---|
| 인증 | `POST /api/auth/signup`·`login`·`refresh`, `GET /api/users/me` -> `{id, email, emailPlaceholder, nickname, role, passwordLoginEnabled}` |
| 프로필·소셜 연결 | `PATCH /api/users/me {nickname}`, `GET /api/users/me/social`(`{accounts, providers, canUnlink}`), `POST /api/users/me/social/{provider}/link`(`{authorizeUrl}`, 60초 1회 티켓), `POST /api/users/me/social/confirm {code}`(60초 1회 확정 코드, 확정 사용자와 코드 사용자가 같을 때만 저장), `DELETE /api/users/me/social/{provider}`(204 멱등, 마지막 로그인 수단이면 409 `LAST_LOGIN_METHOD`). 모두 로그인 필요 |
| 회원탈퇴 | `DELETE /api/users/me {password?, confirmText?, reauthCode?}`(204, 본인 확인, 분당 5회 429 `WITHDRAW_RATE_LIMITED`, ADMIN 409 `ADMIN_CANNOT_WITHDRAW`, 이미 탈퇴한 계정 401). 로그인 필요 |
| 소셜 로그인 | `GET /api/auth/social/providers`(활성 제공자, 인증 불필요), `GET /api/auth/social/{google\|kakao\|naver}/authorize`(302, state 쿠키 `ss_oauth`), `GET .../{provider}/callback`(항상 302: 성공 `/auth/callback#code=`, 실패 `/login?socialError=`), `POST /api/auth/social/exchange {code}`(→ TokenResponse, 일회용 60초, 정지 재검사). IP당 분당 30회(429 `SOCIAL_RATE_LIMITED`) |
| 공연 | `GET /api/performances`(제목 검색·페이지, asOf로 기준 시각 고정)·`/{id}`·`/lookup`(링크 중복 확인), `POST /api/performances`(필수 `ticket{sessionStartsAt, zone, row, col}`: 등록자 티켓을 같은 트랜잭션에서 만든다, 선택 필드 `zones[{name, shapePolygons}]`(최대 100개, 같은 이름은 한 구역)·`seatmapAspect`, V21) |
| 공연 구역 | `GET /api/performances/{id}/zones` → `{seatmapAspect, zones[{name, shapePolygons}]}`(sortOrder 순, 구역 없으면 `zones=[]`, 없는 공연 404, 로그인 필요) |
| AI 회차·구역 읽기 | `POST /api/performances/ai-extract {sourceUrl}` → `{sessions, sessionsSource, zones, seatmapAspect, aiEnabled, aiError, cached, truncatedSlices}`. 로그인 필요. 제한: 사용자당 매월 3회(DB 기록, 초과 429 `AI_MONTHLY_LIMIT_REACHED`, AI 실패 시 반환, 남은 횟수 `GET /api/performances/ai-extract/quota`)·전체 하루 40회(초과 429 `AI_RATE_LIMITED`+Retry-After), 버튼을 눌렀을 때만 호출, 구역 50개 이상 공연장은 같은 공연장 등록 구역 이름을 참고, 동시 2개. 지원 사이트는 YES24·티켓링크·멜론티켓(그 외 400 `UNSUPPORTED_SITE`), 페이지 읽기 실패 503 `PAGE_FETCH_FAILED`, AI·규칙 결과 모두 없으면 503 `AI_UNAVAILABLE`. 성공 결과는 168시간 보관(메모리+DB `ai_extract_cache`, V23) |
| 공연 포스터 | `GET /api/performances/{id}/poster` → `{posterUrl}`(로그인 필요, 없으면 null, 공연 없으면 404). 링크 미리보기 응답에도 `posterUrl` 추가. 실패·제한·스위치 꺼짐은 오류 없이 null |
| 회차 날짜 후보 | `GET /api/performances/preview` 응답의 `sessionCandidates`(`[{date, time:null}]`, 항상 배열, 공연 기간이 100일 이하일 때 첫날부터 마지막 날까지 모든 날짜. `time`은 지금 항상 null이며 AI 상세정보 출처가 채울 자리) |
| 티켓 | `POST /api/tickets {sessionId, zone, row, col}`, `GET /api/tickets/me`, `DELETE /api/tickets/{id}` |
| 교환 요청 | `POST /api/exchange/requests {ticketId, wantSessions[{sessionId,priority}], ranges[{zone,rowFrom,rowTo,colFrom,colTo,extraType,extraAmount}]}`, `GET .../me`, `PATCH`·`DELETE .../{id}`, `GET .../{id}/candidates` |
| 매칭 | `POST /api/exchange/requests/{id}/proposals {targetRequestId}`, `POST /api/exchange/matches/{id}/reserve`(예약 제안)·`reserve/accept`(예약 수락)·`reserve/decline`(예약 제안 거절, 제안받은 쪽)·`reserve/withdraw`(제안 철회, 제안자)·`unreserve`(예약 취소)·`complete`(교환 수락)·`reject`·`cancel`, `GET /api/exchange/matches/me?role=SENT\|RECEIVED\|ALL&status&page&size`, `GET /api/exchange/matches/{id}` |
| 알림 | `GET /api/notifications?page&size&unreadOnly`, `GET /api/notifications/unread-count`(`{total, byType}`), `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all?type=`, `DELETE /api/notifications/read`(읽은 알림만 삭제, `{deleted}`). 모두 로그인 필요, 본인 것만 |
| 채팅 | `POST /api/exchange/matches/{id}/messages {content, clientMessageId}`(201 저장, 같은 id 재전송 200, 종료된 매칭은 409 — 이미 저장된 메시지 재전송은 200), `GET .../messages?before=&size=30` 또는 `?after=&size=100`(`{messages 오름차순, hasMore, myLastReadMessageId, counterpartLastReadMessageId}`), `PUT .../messages/read {lastReadMessageId}`(204, 올라갔을 때만 상대에게 READ 푸시), 모두 로그인·참여자만(비참여자 404). `clientMessageId`는 `^[A-Za-z0-9_-]{8,36}$`. 전송 속도 제한은 429 `CHAT_RATE_LIMITED`(+Retry-After) |
| 채팅 STOMP | 엔드포인트 `/ws`(CONNECT 네이티브 헤더 `Authorization: Bearer`만), 구독 `/user/queue/chat`만. 푸시 payload: `{type:"MESSAGE", matchId, message:{id,senderId,content,clientMessageId,createdAt}}` 또는 `{type:"READ", matchId, lastReadMessageId}` |

- 매칭 응답: `id, status, mySide, role, myRequestId/TicketId, mySeat, counterpartRequestId/TicketId, counterpartSeat, counterpartNickname, myExtraType/Amount, counterpartExtraType/Amount, myRequestDeleted, counterpartRequestDeleted, reservationProposal("NONE"|"MINE"|"THEIRS"), reservedBy("ME"|"COUNTERPART"|null), reservedAt, myAccepted, counterpartAccepted(교환 수락 시각 기준 실제 값), myTicketExchanged, counterpartTicketExchanged, myTicketReservedElsewhere, counterpartTicketReservedElsewhere, unreadChatCount(보낸 사람 제외, 닫힌 매칭도 셈), canceledBy, canceledAt, createdAt, updatedAt`. COMPLETED 응답의 mySeat/counterpartSeat와 ticket id는 교환 전 자리·티켓이다.
- 요청 응답의 열·번 범위는 정규화 값이라 사용자가 입력한 원문 표기는 복원할 수 없다(알려진 한계).
- 예약 제안 오류 코드(409): `RESERVATION_PROPOSAL_PENDING`(상대 제안 대기), `RESERVATION_PROPOSAL_NOT_FOUND`(수락할 제안 없음), `RESERVATION_PROPOSAL_OWN`(제안자가 수락·거절 시도), `RESERVATION_PROPOSAL_NOT_OWN`(상대 제안을 철회 시도). 거절·철회는 제안이 없으면 멱등 200(RESERVED 포함), 닫힌 매칭은 409 `MATCH_STATE_CONFLICT`. `reservationProposal`은 호출자 기준이며 RESERVED·종료는 NONE, `reservedBy`는 예약 제안자(수락으로 RESERVED가 된 쪽)다.
- 교환 이력 `GET /api/exchange/history?page&size`(본인 행만 최신순, 응답: 공연 제목·공연장·기존/바꾼 회차 시각·좌석 스냅샷·matchId·completedAt).

## 7. 프론트 화면 (react-conventions 스킬 참고)

| 경로 | 화면 |
|---|---|
| `/` | 홈(공개): 비로그인 서비스 소개 / 로그인 공연 검색·목록·더 보기 |
| `/login`, `/signup` | 인증 |
| `/auth/callback` | 소셜 로그인 후 중간 화면. 프래그먼트 `#code=`를 읽어 URL을 정리하고 `POST /api/auth/social/exchange`로 토큰을 저장한 뒤 `/`로 이동한다(코드가 없거나 실패하면 `/login?socialError=INVALID_LOGIN_CODE`). 로그인·가입 화면 하단 '또는' 구분선 아래에 활성 제공자 버튼이 있고, 로그인 화면은 `?socialError=`를 한국어 안내로 표시한다 |
| `/privacy` | 개인정보처리방침(공개). 운영자·문의 이메일·시행일은 `VITE_PRIVACY_*`, 미설정이면 '설정 필요' 강조 |
| `/performances/new` | 공연 등록(3단계; 2단계에 'AI로 회차·구역 읽기'와 구역 확인, 구역 목록·좌석표 비율 저장), `/performances/:id` 공연 상세(읽기 전용, 보호 라우트, '이 공연 티켓 등록' 버튼, 포스터는 정보 카드 오른쪽) |
| `/me` | 마이페이지: 닉네임 인라인 수정(저장하면 헤더 즉시 반영), 이메일(자리표시면 '소셜 계정으로 가입해 이메일이 없어요'), '소셜 계정 연결' 카드(연결·해제, 해제 불가면 사유 안내), 내 티켓·내 매칭·교환 이력 링크, 회원탈퇴 버튼은 모달(`WithdrawDialog`)을 연다 |
| `/tickets/new`, `/tickets` | 티켓 등록(공연에 구역 목록이 있으면 구역 드롭다운, 없으면 텍스트) / 내 티켓(내리기·교환 조건 진입) |
| `/tickets/:ticketId/exchange` | 희망 조건 폼: 범위 카드(구역 드롭다운(목록이 있을 때)·열·번 범위, 카드마다 추가금 라디오 4개, 카드 복제, 클라이언트 겹침 경고), 요청 단위 희망 회차 우선순위, 총 N석 합집합 미리보기와 5,000석·50범위 경고, 마감 회차 선택 불가 |
| `/exchange/requests/:requestId/candidates` | 후보 목록·제안하기('내 조건/상대 조건', 참고용 금액 구간, 신뢰도 표시 없음) |
| `/exchange/matches` | 내 매칭(보낸/받은 탭): 'RESERVED=예약 중', CHATTING='진행 중' 라벨(미확정), CHATTING 카드는 제안 여부 문구와 '채팅' 버튼만(예약하기 버튼 없음), '예약 취소'(2단계 확인), RESERVED에서 '교환 수락'(2단계 확인, 수락 후 "상대 수락을 기다려요"), 완료 카드는 교환 전 자리와 받은 자리 표시, 교환된 좌석 카드는 예약·교환 수락 비활성 + "이미 교환된 좌석이에요"(본인·상대 양쪽), 예약 중 '거절/채팅 종료'는 숨기지 않고 사유를 안내하며 비활성, 삭제된 요청은 회색 '(삭제)'/'(내 조건 삭제됨)', 카드에 '채팅' 링크와 안 읽은 뱃지 |
| `/exchange/matches/:matchId/chat` | 채팅 화면: 상단 상태 배너(시스템 메시지 대체), 메시지 목록, 입력창(1000자 카운터, IME 조합 중 전송 금지), '읽음' 표시. 상단 예약 블록(NONE '예약 제안하기', MINE '제안 철회', THEIRS '예약 수락'·'거절', RESERVED '예약 취소', 종료 매칭은 숨김)과 상대의 제안·수락·거절·취소 실시간 반영(알림 신호·폴링). 종료된 매칭은 열람만 되고 입력 비활성. STOMP 연결은 앱 전체가 탭당 하나를 공유(알림 푸시와 같은 연결, 지수 백오프 재연결, 재연결 시 `after` 커서로 빈 구간 채움) |

- 헤더 메뉴(로그인 시): 내 티켓, 내 매칭, 마이페이지, 로그아웃. 768px 미만은 햄버거 메뉴. 로고는 이미지. 로그인 시 헤더에 알림 벨(안 읽은 개수)이 있다. 벨은 데스크톱(768px 이상)에서 '내 매칭' 바로 오른쪽, 햄버거 폭에서는 햄버거 왼쪽에 있다.
- 푸터: '개인정보처리방침' 링크와 ©. 채팅 화면(`/exchange/matches/:matchId/chat`)에서는 숨긴다. 가입 화면에는 방침 링크와 14세 안내 한 줄이 있다.
- `/notifications`: 알림 전체 보기(더 보기 페이징, 모두 읽음, 읽은 알림 삭제 2단계 확인). 알림 클릭 시 읽음 처리 후 채팅 메시지·예약 제안 알림은 `/exchange/matches/N/chat`, 나머지는 `/exchange/matches?role=RECEIVED&matchId=N`(새 제안) 또는 `/exchange/matches?matchId=N`으로 이동하며, 매칭 화면은 URL 쿼리로 탭과 카드를 강조한다.
- 인증 프론트: AuthProvider(토큰 localStorage 저장·복원·탭 간 동기화, access 만료 60초 전 선제 재발급), ProtectedRoute(원래 경로 복귀), 401 인터셉터(`authInterceptor.ts`·`session.ts`·`authClient.ts`).
- 프론트 테스트 러너(vitest)는 도입하지 않기로 했다.

## 8. 알려진 한계·후속 과제

- 브라우저 수동 점검·실서버 연동은 교환 화면 전반에서 아직 충분히 하지 않았다.
- 예약 제안·채팅 알림 한계(2026-10-13): 예약-취소 남용 방지 없음(차단·신고로 다룸), 정렬용 `(user_id, created_at)` 인덱스 없음(알림이 매칭당 1행 수준이라 불필요로 판단, 실측 아님), 읽음 가드와 알림 되살리기가 겹치는 극히 약한 창, 일부 경로 REPEATABLE READ 패턴 유지. 알림 목록 정렬은 `created_at DESC, id DESC`, 채팅 읽음은 포인터 뒤에 상대 메시지가 남아 있으면 처리하지 않는다. 브라우저 확인은 Playwright 수준.
- 7일 경과 알림은 4절의 보류 항목이다(2026-10-13 결정).
- 회차 날짜 미리 채우기 한계(2026-10-13): 시각은 직접 입력(세 사이트 회차 시각은 Ajax), 3일 이상 공연은 날짜 후보 없음(AI 상세정보 출처는 후속, 구조만 열림), 하루 여러 회차인 2일 공연은 날짜당 후보 1개라 같은 날짜를 직접 추가해야 함, 공연 기간과 실제 회차 날짜가 다를 수 있음(화면은 확인 문구로 안내).
- 공연 포스터 표시 한계(2026-10-13): 인라인 링크의 저작권 확정 판례 없음, 포스터 저작물성과 티켓 3사 약관 미확인(공개 운영 전 확인, 요구사항 §7 Q-26, 요청이 오면 `matjari.poster.enabled=false`), 허용 호스트는 실측 기준이라 CDN이 바뀌면 포스터가 안 뜸, YES24는 포스터 없는 공연이 많음, 인터파크 미지원, 핫링크 차단 시 안 보임, 삭제·표시 중단 요청은 방침 문의처로 받는데 방침 문구는 아직 없음. 상세는 `wiki/decisions/poster-display-design.md`.
- 회원탈퇴 한계(2026-10-13): 소셜 전용 정지 계정은 탈퇴 경로가 없어 방침 §8 문의처(비밀번호 계정은 정지 로그인의 `withdrawToken`으로 탈퇴, 소셜 전용 계정의 재인증은 구현됨), 교착 창 2개는 503 후 재시도, 보관 기간의 법적 근거 미확인(공개 운영 전 전문가 검토), 가입 시 동의 기록 없음, 탈퇴 속도 제한은 메모리. 인증 사진은 정리 작업이 주기적으로 지운다. 상세는 `wiki/decisions/withdraw-privacy-design.md`.
- 프로필·소셜 연결 한계(2026-10-13): 연결 확정 코드·티켓·속도 제한이 메모리(단일 인스턴스)다. 같은 이름 쿠키라 탭 A의 연결 시작과 탭 B의 소셜 로그인이 겹치면 A 콜백이 INVALID_STATE가 된다. 티켓이 URL 쿼리에 실리고(1회용·60초), JWT 클레임에 자리표시 이메일 원문이 남는다(화면에는 안 나옴). 비밀번호·이메일 변경과 소셜 가입자의 비밀번호 설정은 없다. 실제 제공자 키로 미검증.
- 소셜 로그인: 네이버 이메일은 검증 여부를 알 수 없어 쓰지 않는다(항상 자리표시 이메일, 2026-10-13).
- 소셜 로그인 한계(2026-10-12): 실제 카카오·구글·네이버 키로 미검증(카카오 PKCE·is_email_verified, 네이버 client_secret 필수 여부 등), 로그인 코드·속도 제한·사용된 state는 메모리(단일 인스턴스), 프록시 뒤 배포 때 `server.forward-headers-strategy` 필요, 일반 가입에 이메일 인증이 없어 타인 이메일을 먼저 쓰면 그 사람의 소셜 로그인이 EMAIL_EXISTS로 막힌다(기존 한계), 기존 계정에 소셜을 붙이는 연결은 마이페이지에서 본인이 명시적으로 할 때만 한다(3.8절).
- 채팅 알려진 한계: 실제 브라우저와 @stomp/stompjs 연동 점검 미실시, 모바일 이중 스크롤 완전 해소 미보장(AppLayout 높이 규칙), 미인증 소켓은 최대 약 40초 유지(운영 프록시 연결 한도로 흡수), 토큰 만료·사용자 변경 후 최대 30초 푸시 유지(이용 정지는 커밋 뒤 대상 STOMP 연결을 닫음), 응답·푸시에 상대 `senderId`(users.id) 노출(차단·신고 브랜치에서 `mine`으로 대체 검토), 속도·연결 제한은 단일 인스턴스 메모리, 하트비트 미응답 종료·16KB 초과 프레임 거부 미확인.
- 공연: 예매처별로 같은 공연이 갈라지는 문제(같은 공연장+비슷한 제목 안내), 회차 추가 상한·스팸 정리는 후속.
- 필터에서 전파된 예외(DB 장애 등)는 Spring 기본 `/error` 포맷으로 나간다(`{message}` 통일 검토). `/me` 한 번에 사용자 조회 2회(필터+서비스). `index.html` viewport에 `viewport-fit=cover`가 없어 safe-area 여백 미적용.
- Testcontainers 미도입: 실제 MySQL 통합 테스트(`ExchangeCandidateQueryTest`, `MigrationV6V7MysqlTest`, `MigrationV8MysqlTest`, `ExchangeCompleteMysqlTest`, `MigrationV9MysqlTest` 등)는 환경변수 `MATJARI_IT_*`가 있을 때만 실행(기본 skip, 개발 DB 보호 장치 포함).
- 산출물 04·05·08 원본(docx/xlsx)에는 이 문서의 최신 결정이 아직 반영되지 않았다. FR-01 아래 '내 정보 조회' 항목 추가 필요.

## 9. 폐기된 안 (되돌리지 말 것)

- 추가금 합이 0 이하(이상)면 성립하는 금액 계산 규칙 -> 유형 4종 판정.
- 취소 후 같은 상대와 재매칭 불가 -> 취소는 재매칭 가능, 차단·신고 때만 불가.
- 한 명이 예약하면 즉시 RESERVED(제안·수락 없음) -> 예약 제안 후 상대가 수락해 RESERVED(`reserve` -> `reserve/accept`, 2026-10-12 사용자 지시). 양쪽이 눌러야 예약되는 `accept` 방식도 폐기.
- 교환 완료 시 기존 티켓의 좌석·회차를 갱신하는 방식(임시 INACTIVE 순서 포함) -> 기존 티켓 EXCHANGED + 새 티켓 INSERT.
- venue 테이블·공연장 검색/정식 등록·구역 자동완성·요청당 300석 펼침 상한·후기/신뢰도 기반 우선순위.
