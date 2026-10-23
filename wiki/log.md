# 위키 작업 기록

이 파일은 추가만 한다. 기존 항목은 고치거나 지우지 않고, 날짜 오름차순으로 끝에만 새 항목을 붙인다. 항목 형식은 `## [YYYY-MM-DD] <작업> | <제목>`이며 작업 종류는 ingest, query, lint, init 중 하나다. 제목 아래에 한 줄 설명을 붙일 수 있다.

## [2026-10-10] init | llm-wiki 구조와 린트 스크립트 생성
llm-wiki 스킬, researcher·wiki-curator 에이전트, `scripts/lint-wiki.mjs`를 만들었다. 페이지는 아직 없다.

## [2026-10-10] ingest | 교환 완료 시 티켓 처리: 안 2의 근거
`wiki/decisions/exchange-complete-new-ticket.md`를 추가했다. 선택지 3가지와 안 2를 고른 이유를 정리했다.

## [2026-10-10] ingest | 잠금 순서와 인덱스·유니크 키 함정
`wiki/gotchas/lock-order-and-index-pitfalls.md`를 추가했다. 교환 도메인의 잠금 순서 규약과 인덱스·유니크 키 함정을 정리했다.

## [2026-10-10] ingest | 용어집
`wiki/glossary.md`를 추가했다. 매칭 상태, 교환 요청, 추가금, 티켓과 마감 용어를 정리했다.

## [2026-10-10] ingest | 실시간 채팅(STOMP) JWT 인증·인가 조사
`wiki/research/stomp-jwt-auth.md`를 추가했다. 출처는 researcher 웹 조사 보고서이며 confidence는 medium이다.

## [2026-10-11] lint | 낡은 서술 정리와 Q-15 확정 반영
교환 완료 결정 페이지의 Q-15를 열린 질문에서 확정으로 고치고, 용어집·함정·STOMP 조사의 낡은 서술(EXCHANGED/CHECK 확장 시점, 채팅 미구현)을 코드 기준으로 정정했다.

## [2026-10-11] ingest | 교환 완료 구현 반영
교환 완료(V9, `complete` API, 커밋 `52cfb5a`) 구현 사실을 반영했다. 결정 페이지의 구현 상태 절과 낡은 문장을 고치고, 용어집의 교환 수락·교환 완료·교환됨 행에서 미구현 표기를 뺐다. 함정 페이지에 `ck_ticket_status` 확장, `exchange_history` 유니크 키 설계, Hibernate flush 순서 절을 추가했고, STOMP 조사에는 채팅 테이블이 V10 이후라는 점을 적었다.

## [2026-10-11] ingest | 알림 3종 구현 반영
알림 3종(V10, 커밋 `48f5858`·`acc156e`·`3a7c61c`·`b16fb60`·`45203b2`) 구현 사실을 반영했다. 결정 페이지 `notification-design.md`를 추가하고, 함정 페이지에 알림 쓰기 규칙·dedup_key 함정(8절)과 교환 완료 갭 잠금 교착 가능성(9절, 1절 전제 보완)을 적었다. 용어집에 알림 절을, STOMP 조사의 마이그레이션 번호를 V11 이후로 고쳤다.

## [2026-10-11] ingest | 채팅 구현 반영
채팅(V11, 브랜치 `feature/chat`, 커밋 `48d5e17`·`6f566f7`·`3bb70b5`·`9f56d59`·`8b662e5`·`fc20c82`) 구현 사실을 반영했다. 결정 페이지 `chat-design.md`를 추가하고, 함정 페이지에 채팅 절(10절)을, 용어집에 채팅 절을 더했다. STOMP 조사의 미확인 항목을 확인 결과(CSRF, `/ws/**`, Origin 403, 1008, RECEIPT 없음)로 갱신하고 채팅 미구현 서술을 정정했다. 하트비트·16KB 거부는 미확인으로 유지했다.

## [2026-10-11] ingest | 실시간 알림 푸시 반영
실시간 알림 푸시(브랜치 `feature/notifications-realtime`, 커밋 `45def5e`·`386bede`, 미병합, V12 없음) 구현 사실을 반영했다. 알림 설계 페이지에 실시간 푸시 절(신호만 싣는 payload, afterCommit 전파, 폴링 완화)을 추가하고 "실시간은 다음 브랜치" 서술을 고쳤다. 채팅 설계의 연결 범위를 앱 공용 연결로 바꿨고, STOMP 조사와 함정 10절의 "세션당 구독 1개"를 "목적지당 1개"로 정정했으며 용어집에 알림 사용자 큐·앱 공용 연결을 더했다.

## [2026-10-12] ingest | 링크 기반 공연정보 자동 입력 반영
링크 자동 입력(브랜치 `feature/link-autofill`, 미병합, V12 없음) 구현 사실을 반영했다. 결정 페이지 `link-autofill-design.md`와 함정 페이지 `link-fetch-pitfalls.md`를 추가하고, 목차와 용어집에 링크 자동 입력 절을 더했다. 실측은 2026-10-12 한 시점 결과이며 confidence는 medium이다.

## [2026-10-12] ingest | 관리자 페이지(공연 수정 제안·좌석 소유 인증) 반영
관리자 기능(V12, 브랜치 `feature/admin`, 커밋 `e608c5a`·`96a5623`·`6c04c08`·`04fecf7`, 미병합) 구현 사실을 반영했다. 결정 페이지 `admin-design.md`를 추가하고, 함정 페이지에 11절(좌석 키 보조 인덱스 FOR UPDATE 교착, PENDING 전용 생성 컬럼 유니크)을, 용어집에 관리자 절을 더했다. 범위 해석("다")은 추정이라 confidence는 medium이다.

## [2026-10-12] ingest | 차단·신고·이용 정지 반영
차단·신고·관리자 신고 처리·이용 정지(V13, 브랜치 `feature/block-report`, 미병합) 구현 사실을 반영했다. 결정 페이지 `block-report-design.md`를 추가하고, 함정 페이지에 12절(차단 NOT EXISTS 분리, READ_COMMITTED 제재 중복 직렬화, users 잠금 금지)을, 용어집에 차단·신고 절을 더했다. 채팅 설계·STOMP 조사·관리자 설계의 "차단은 범위 밖/훅 필요" 서술을 갱신했다. 테스트 수치는 구현 세션 기록이며 confidence는 medium이다.

## [2026-10-12] ingest | 소셜 로그인 반영
소셜 로그인(V14, 브랜치 `feature/social-login`, 미병합) 구현 사실을 반영했다. 결정 페이지 `social-login-design.md`와 함정 페이지 `frontend-react-pitfalls.md`(StrictMode 중단 요청 가드, `__proto__` 키 조회)를 추가했다. 실제 제공자 키로는 검증하지 못해 confidence는 medium이다.

## [2026-10-13] ingest | 예약 제안/수락과 채팅 알림 반영
예약 제안/수락·채팅 알림·벨 위치(V15, 브랜치 `feature/reserve-propose`, 미병합) 구현 사실을 반영했다. 결정 페이지 `reserve-propose-design.md`를 추가하고, 함정 페이지에 13절(upsert 대 삭제 교착, 벌크 JPQL의 제안 컬럼 우회)을 더했다. 채팅·알림 설계와 용어집의 낡은 서술(채팅 알림 행 없음, 한 명이 예약, 알림 3종)을 정정했다. 테스트 수치는 구현 세션 기록이며 confidence는 medium이다.

## [2026-10-13] ingest | 프로필 수정·소셜 계정 연결 반영
프로필 수정(닉네임)과 소셜 계정 연결·해제(V16, `feature/profile-edit`, 미병합)를 반영했다. 결정 페이지 `profile-edit-design.md`를 추가하고, 소셜 로그인 설계의 '연결 UI 없음'·'헤더 가짜 이메일' 서술을 고쳤다. 잠금·인덱스 함정 14절(로그인 CSRF, 해제 잠금 순서, 제약 이름 구분)과 용어집의 소셜 연결 절을 더했다. 프런트 빌드와 브라우저 점검 기록이 없어 confidence는 medium이다.

## [2026-10-13] ingest | 회원탈퇴·개인정보처리방침 반영
회원탈퇴(V17, `feature/withdraw`, 미병합)를 반영했다. 결정 페이지 `withdraw-privacy-design.md`를 추가해 익명화 범위와 이유, 대안 비교, 탈퇴 잠금 순서(users 먼저 폐기), 보관 기간 조사 요약, 한계 8개와 추가 관찰을 적었다. 함정 페이지 15절, 용어집 '회원 탈퇴' 절, 색인을 갱신했다.

## [2026-10-13] ingest | 공연 포스터 표시 반영
공연 포스터 표시(DB 변경 없음, 브랜치 `feature/poster`, 미병합) 구현 사실을 반영했다. 결정 페이지 `poster-display-design.md`를 추가하고, 함정 페이지 `link-fetch-pitfalls.md`에 3절(noimage 자리표시, 핫링크 차단, CDN 변경)을 더했다. 목차에 결정 페이지를 등록했다. 허용 호스트는 2026-10-13 한 시점의 실측이고, 약관 확인이 빠져 있어 confidence는 medium이다.

## [2026-10-13] ingest | 회차 날짜 미리 채우기 반영
회차 날짜 미리 채우기(DB 변경 없음, 브랜치 `feature/session-prefill`, 미병합) 구현 사실을 반영했다. 결정 페이지 `link-autofill-design.md`에 6절(2일 이하만 날짜를 채우는 이유, 택하지 않은 대안, AI 상세정보 출처를 붙일 자리와 그때 정할 것, 한계)을 더했다. 시각은 Ajax라 여전히 직접 입력이고, 판단 근거는 구현 상수와 사용자 요청을 따랐다. 브라우저 점검은 Playwright 수준이라 confidence는 medium이다.

## [2026-10-13] lint | 문서·코드 정합성 점검
백엔드·프런트·산출물 문서 간 낡은 표기를 점검해 위키 페이지의 병합 상태(미병합→병합됨), 7일 경과 알림 보류 결정(notification-design·block-report-design), 탈퇴 정지 규칙과 잠금 순서의 실제 순서(withdraw-privacy-design), 관리자 알림 유형 서술의 시점(admin-design)을 고쳤다. 과거 ingest 기록 본문은 그대로 두었다. 점검 결과: `node scripts/lint-wiki.mjs` 오류 0건, 경고 0건.

## [2026-10-13] ingest | 네이버 이메일 미사용 결정 반영
- 네이버 프로필의 이메일은 검증 여부를 알 수 없어(공식 문서 미확인, 조사 신뢰도 낮음) 쓰지 않기로 했다. 항상 자리표시 이메일로 가입하고 EMAIL_EXISTS 판정도 하지 않는다. 관련: [decisions/social-login-design.md](decisions/social-login-design.md)

## [2026-10-13] ingest | 신뢰도 점수 제거 반영
신뢰도 점수 컬럼 제거(V18, `feature/remove-trust-score`, 병합됨)를 반영했다. `block-report-design.md`의 남은 것 목록에서 `trust_score` 정리를 끝난 일로 바꾸고 갱신일을 고쳤다. 신뢰도 점수는 매칭에 쓰지 않는다는 기존 결정과 충돌하지 않는다. 색인(index.md)은 바꿀 설명이 없어 그대로 두었다.

## [2026-10-13] ingest | 후속 4건 구현 반영
정지 중 제한 로그인(탈퇴 전용 토큰)·소셜 전용 탈퇴 재인증·좌석 인증 사진 정리 작업·후보 keyset 조각 조회를 반영했다. `withdraw-privacy-design.md`의 한계 1~3과 다음 할 일을 구현 사실에 맞게 고치고, `admin-design.md`의 사진 정리와 `lock-order-and-index-pitfalls.md`의 keyset 전환 메모는 각 기능 브랜치가 갱신했다.

## [2026-10-13] ingest | 가입 이메일 인증번호 반영
가입 전 이메일 인증번호(V19 `email_verification`, `feature/email-verification`)를 반영했다. 결정 페이지 `email-verification-design.md`를 새로 만들었다(필요한 이유, 코드 동봉 방식, 시도 횟수를 별도 트랜잭션으로 먼저 올리는 순서, 발송 제한, 운영에서 log 모드면 기동 거부, 택하지 않은 대안, 한계 6개). 확정 규칙은 CLAUDE.md '가입 이메일 인증번호' 절이다. 실제 SMTP는 미검증이라 confidence는 medium이다.

## [2026-10-13] ingest | 서비스 이름 변경(SeatSwap→맞자리/MatJari)
해외에 같은 이름의 서비스가 있어 이름을 바꿨다. 위키 본문과 소스 경로(`SeatSwap/…`→`MatJari/…`, `com.seatswap`→`com.matjari`)는 새 이름으로 고쳤고, 이 로그의 위 항목들은 당시 표기(SeatSwap)를 그대로 둔다. 결정 근거와 영향 범위는 HISTORY.md와 작업일지 2026-10-13의 '서비스 이름 변경' 절.

## [2026-10-14] ingest | 공연 수정 제안 회차 수정과 날짜 후보 확대 반영
공연 수정 제안에 회차 수정(V20 `session_changes`, `feature/session-edit-proposal`)을 넣고 날짜 후보를 연속 기간 100일 이하 전체로 넓혔다. `admin-design.md`에 '회차 수정 제안' 절(JSON 컬럼 선택, 삭제 제한, 맞바꾸기 거부, 승인 충돌 처리)을, `link-autofill-design.md` 6.1에 후보 범위를 2일에서 100일로 넓힌 이유를 추가했다. 확정 규칙은 CLAUDE.md '공연 수정 제안'·'링크 기반 공연정보 자동 입력' 절이다.

## [2026-10-18] ingest | AI 회차·구역 읽기 결정 정리
AI 회차·구역 읽기(V21 `performance_zone`, `feature/ai-extract-backend`)의 근거를 결정 페이지 `ai-detail-extract-design.md`로 정리했다(처리 순서, 모델 선택과 무료 티어·구독 로그인 비채택 이유, 법·약관 조사 요약, 구역 규칙, 시험 결과, 한계·후속). 용어집에 'AI 읽기·구역' 절을 더하고 색인에 등록했다. 약관·법 해석과 정확도는 미확인·작은 표본이라 confidence는 medium이다.

## [2026-10-23] ingest | 사이트별 읽는 법 반영과 인터파크·티켓링크 문구 정리
인터파크(NOL)·티켓링크 renderer·본문 맥락 회차(PR #115)에 맞춰 `link-autofill-design.md`·`ai-detail-extract-design.md`·`poster-display-design.md`·`link-fetch-pitfalls.md`의 '인터파크 제외·티켓링크 이미지 불가' 문구를 고쳤다. `ticketing-site-reading.md` YES24 절에 모바일 페이지·상세 내용 주소 점검 결과와 남은 선택지(KOPIS 공공 API 등)를 더했다.
