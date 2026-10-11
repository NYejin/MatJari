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
