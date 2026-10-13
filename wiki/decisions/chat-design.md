---
title: 채팅 설계 근거 (REST 전송 + STOMP 수신)
type: decision
tags: [채팅, 설계, stomp, 결정근거]
sources: [../research/stomp-jwt-auth.md, ../gotchas/lock-order-and-index-pitfalls.md, ../glossary.md, ../../CLAUDE.md, ../../산출물/07_작업일지/2026-10-11.md]
updated: 2026-10-13
confidence: medium
status: draft
---

# 채팅 설계 근거 (REST 전송 + STOMP 수신)

채팅은 2026-10-11 `feature/chat` 브랜치에서 구현됐다(병합됨). 이 문서는 선택지와 이유만 정리하고 결정 자체를 새로 만들지 않는다. 구현 사실은 [작업일지](../../산출물/07_작업일지/2026-10-11.md), 프로젝트 확정 결정은 [CLAUDE.md](../../CLAUDE.md), 함정은 [함정 페이지 10절](../gotchas/lock-order-and-index-pitfalls.md), STOMP 보안은 [STOMP 조사](../research/stomp-jwt-auth.md), 용어는 [용어집](../glossary.md) 참고.

## 사용자가 정한 결정 4개

작업일지 기준으로 사용자가 직접 정한 것이다.

1. **보내기는 REST POST, 받기는 STOMP 사용자 큐(`/user/queue/chat`)만 쓴다.** 클라이언트의 STOMP SEND는 전부 거부한다.
2. **읽음은 마지막 읽은 메시지 포인터로 한다.** 매칭 카드에 안 읽은 개수 뱃지, 내가 보낸 마지막 메시지 아래에 상대가 읽었으면 '읽음'을 표시한다.
3. **시스템 메시지는 저장하지 않고 채팅 화면 상단 상태 배너로 보여준다.**
4. **종료된 매칭(CANCELED·COMPLETED)은 둘 다 읽기 전용이다.** 참여자는 열람할 수 있고 쓰기는 409 `MATCH_STATE_CONFLICT`다. CHATTING·RESERVED는 읽기·쓰기가 되고, 교환된 좌석이나 다른 사용자와 예약 중인 좌석의 매칭도 포함한다.

## 선택지와 이유

### 전송 경로: REST 전송 vs STOMP 전송

| 안 | 장점 | 단점 |
|---|---|---|
| REST POST로 전송 (선택) | 매칭 행 잠금 트랜잭션, 검증, 속도 제한 429, 멱등 재전송을 기존 REST·서비스 계층에 그대로 얹을 수 있다. 실패가 HTTP 상태코드로 분명하다 | 전송마다 HTTP 요청이 든다 |
| STOMP SEND로 전송 | 연결 하나로 양방향 | 인가를 메시지 단위 `AuthorizationManager`로 해야 하고, 오류 전달·멱등·속도 제한을 STOMP 위에서 따로 만들어야 한다 |

선택 이유(작업일지·구현 사실에서 확인되는 범위): 전송 로직의 핵심이 매칭 `FOR UPDATE` 트랜잭션([함정 10절](../gotchas/lock-order-and-index-pitfalls.md))이라 기존 REST 서비스 구조에 맞았고, STOMP 쪽은 SUBSCRIBE만 열어 인가 면을 줄였다. 이 비교의 장단점 서술 일부는 일반론이며 구현 중 두 안을 모두 시험해 본 것은 아니다.

### 읽음: 포인터 vs 메시지별 `read_at`

| 안 | 특징 |
|---|---|
| 읽음 포인터 `chat_read_state.last_read_message_id` (선택) | (매칭, 사용자)당 1행. 단조 증가, 갱신이 단순하고 안 읽은 개수는 `id >` 비교로 센다. 한 메시지만 골라 읽음 처리는 못 한다 |
| 메시지별 `read_at` | 메시지를 UPDATE해야 해서 `chat_message`를 append-only로 둘 수 없고, 읽음 한 번에 여러 행을 갱신한다 |

`chat_message`는 append-only(수정·삭제 불가)로 두었기 때문에 포인터가 맞았다. 포인터 갱신의 교착 함정은 [함정 10절](../gotchas/lock-order-and-index-pitfalls.md) 참고.

### 시스템 메시지: 저장 vs 상단 배너

저장하면 `chat_message`에 시스템 행(발신자 없음)이 생겨 sender FK와 안 읽은 개수 규칙을 따로 다뤄야 한다. 상단 상태 배너는 매칭 상태(예약 중·교환된 좌석·종료)에서 파생되므로 저장이 필요 없다. 사용자가 배너로 정했다.

### 종료 후 읽기 전용

종료 커밋과 전송이 같은 매칭 행 잠금으로 직렬화되므로 종료 뒤에는 메시지가 0건이다. 이미 저장된 메시지의 재전송(같은 `clientMessageId`)은 종료 뒤에도 200으로 응답하고 전파하지 않는다.

## 문서 근거로 정해진 나머지

요구사항 문서 근거로 확정된 항목이며 사용자 결정 4개와 구분한다. 본문 1000자(코드 포인트), 무기한 보관·삭제·수정 불가, 이미지·첨부 없음(텍스트만), 새 메시지는 `exchange_match.updated_at`을 바꾸지 않음, 차단은 채팅 당시 범위 밖이었고 이후 구현됐다([차단·신고 설계](block-report-design.md)). 근거 문서 위치는 이번 입력에서 확인하지 못했다.

> 정정(2026-10-13): 당시의 "새 메시지 알림 행 없음, 헤더 메시지 아이콘·벨 표시 없음"은 사용자 결정으로 **뒤집혔다.** 이제 새 메시지는 `CHAT_MESSAGE` 알림으로 (수신자, 매칭)당 1행을 재사용해 헤더 벨에 뜬다(별도 메시지 아이콘은 여전히 없다). 근거는 [예약 제안/수락과 채팅 알림 설계](reserve-propose-design.md).

## 연결 범위 변경 (실시간 알림 푸시 이후)

채팅 구현 당시에는 연결을 채팅 화면이 열려 있는 동안만 맺었다. 실시간 알림 푸시(`feature/notifications-realtime`)부터는 로그인한 앱 전체가 탭당 연결 1개를 공유하고(참조 카운트, 헤더 벨 훅과 채팅 훅이 각자 acquire), 구독 허용 목적지는 `/user/queue/chat`과 `/user/queue/notifications` 두 개이며 **목적지당 구독 1개**다. 이미 연결된 상태에서 채팅 화면을 열면 구독 직후 `onConnected`로 빈 구간을 채운다. 채팅 푸시(`/user/queue/chat`) 자체는 그대로지만, 새 메시지의 알림 행 없음 규칙은 이후 뒤집혀 `CHAT_MESSAGE` 알림이 생긴다([예약 제안 설계](reserve-propose-design.md)). 자세한 내용은 [알림 설계 근거](notification-design.md)와 [함정 10절](../gotchas/lock-order-and-index-pitfalls.md) 참고.

## 알려진 한계와 후속

- 차단·제재는 구현됐다([차단·신고 설계](block-report-design.md)). 이용 정지는 커밋 뒤 대상의 STOMP 연결을 닫고, 차단은 열린 매칭 취소로 쓰기를 막는다.
- 응답·푸시에 상대 `senderId`(users.id)가 노출된다. 차단·신고 구현에서도 `mine`으로 바꾸지 않았다.
- 속도 제한·연결 제한은 단일 인스턴스 메모리다. 다중 인스턴스 때는 재설계가 필요하다.
- 실제 브라우저·`@stomp/stompjs` 연동 점검은 하지 않았다.
