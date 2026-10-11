# SeatSwap Frontend (React + TypeScript + Vite)

## 폴더 구조
- api         — axios 클라이언트 (client.ts에서 JWT 인터셉터 일괄 처리)
- components  — 공용 컴포넌트 (ProtectedRoute, TextField 등)
- pages       — 라우트 단위 화면 (App.tsx 라우팅 참고)
- types       — 백엔드 DTO에 대응하는 타입
- hooks       — useAuth 등 커스텀 훅

## 현재 상태
- 구현됨: 로그인(`/login`)·회원가입(`/signup`), 공연 목록(`/`), 공연 등록(`/performances/new`), 공연 상세(`/performances/:id`), 마이페이지(`/me`). 로그인이 필요한 화면은 ProtectedRoute로 보호하고, api/client.ts에서 401 재발급 인터셉터를 처리한다.
- 교환 화면(구현 완료; 좌석표는 쓰지 않고 구역·열·번 텍스트 입력, 구역 자동완성 없음):
  - `/tickets/new` 티켓 등록(FR-03) · `/tickets` 내 티켓(내리기, 교환 조건 진입)
  - `/tickets/:ticketId/exchange` 교환 희망 조건 폼 — 범위 카드(구역·열·번 범위 + **카드마다 추가금 4유형 라디오**, 카드 복제, 겹치는 범위의 추가금이 다르면 클라이언트 경고·서버 422 `WANT_EXTRA_CONFLICT`), 요청 단위 희망 회차 우선순위, 총 N석 합집합 미리보기와 5,000석·50범위 경고
  - `/exchange/requests/:requestId/candidates` 후보 목록·제안하기(신뢰도 없음, 참고용 금액 구간)에 '내 조건/상대 조건' 표시
  - `/exchange/matches` 내 매칭(보낸/받은 탭). RESERVED 배지는 '예약 중', CHATTING은 '진행 중'. CHATTING 카드: '예약하기'(한 명이 누르면 즉시 예약, 두 티켓 잠금)·받은 쪽 '거절'·'채팅 종료'. RESERVED 카드: '내가/○○님이 예약했어요'·'예약 취소'(2단계 확인, 취소하면 매칭 상태로 복귀. 교환 수락 표시가 있으면 "교환 수락 표시도 초기화돼요" 안내)·**'교환 수락'**(2단계 확인: "티켓팅 사이트에서 양도를 마쳤나요? 두 사람 모두 수락하면 교환이 완료되고 취소할 수 없어요."). 내가 먼저 수락하면 "교환 수락함 · 상대 수락을 기다려요", 상대가 먼저 수락했으면 "○○님이 교환 수락했어요". 예약 중에는 '거절'·'채팅 종료'를 숨기지 않고 비활성(aria-disabled, "먼저 예약을 취소해주세요" 안내). CHATTING 카드에서 같은 자리로 다른 매칭이 예약 중이면 "(다른 사용자와 예약 중인 좌석)" 문구만 띄운다. 교환된 좌석이면 '예약하기'가 aria-disabled이고 "이미 교환된 좌석이에요"를 양쪽에 표시한다(거절·채팅 종료는 가능). COMPLETED 카드는 "교환 전 내 자리 → 받은 자리"와 내 티켓 링크를 보인다. '동의 현황' 2줄과 '이 사람과 교환할게요' 버튼은 제거됐다. 응답 필드: `reservedBy`("ME"|"COUNTERPART"|null)·`reservedAt`·`myAccepted`/`counterpartAccepted`(교환 수락 표시)·`myTicketExchanged`·`counterpartTicketExchanged`·`myTicketReservedElsewhere`·`counterpartTicketReservedElsewhere`. 후보 목록은 예약으로 잠긴 티켓을 계속 뺀다(후보 카드 '예약 중' 뱃지는 폐기). 삭제된 요청의 매칭은 회색 '(삭제)', 내 조건이 삭제된 경우 '(내 조건 삭제됨)' 표시 (범위별 추가금·요청 소프트 삭제, 백엔드 V6·V7). 모든 매칭 카드(종료된 매칭 포함)에 '채팅' 링크와 안 읽은 메시지 뱃지(`unreadChatCount`)를 둔다
  - 헤더에 '내 티켓'·'내 매칭', 마이페이지 링크, 공연 상세에 '이 공연 티켓 등록' 버튼
  - 정한 항목: 열 끝·번 끝을 비우면 시작값으로 채워 전송, 상한은 하드코딩(서버 count/limit 우선), 마감 회차는 선택 불가
  - 알려진 한계: 브라우저 수동 점검·실서버 연동은 아직 하지 않았다.
- 좌석표 화면은 2026-10-07 방향 전환으로 삭제했다 (좌석표 코드는 태그 `archive/seatmap-track-20261007`에 보관).
- 알림(구현 완료, 2026-10-11, `feature/notifications`, 백엔드 V10): 헤더 벨(`components/NotificationBell.tsx`)에 안 읽은 개수 뱃지를 달고, 누르면 바로 아래 말풍선이 떠서 최신 5건 요약·'모두 읽음'·'알림 전체 보기' 버튼을 보인다. `/notifications`(`pages/NotificationsPage.tsx`, 로그인 보호)는 더 보기 페이징, 모두 읽음, 읽은 알림 삭제(2단계 확인)를 한다. API 클라이언트는 `api/notifications.ts`, 타입은 `types/notification.ts`. 안 읽은 개수는 `hooks/useUnreadNotifications.ts`가 조회한다. 실시간 연결이 끊겨 있으면 **화면이 보일 때만 30초마다 폴링**하고, 연결이 살아 있으면 5분마다 안전 확인만 한다. focus·visibilitychange, 경로 이동, 같은 탭 재진입, (재)연결 직후에는 즉시 다시 조회한다. 알림 항목을 누르면 읽음 처리 후 `/exchange/matches?role=RECEIVED&matchId=N`(새 제안) 또는 `/exchange/matches?matchId=N`으로 이동하고, 매칭 화면(`pages/MatchesPage.tsx`)은 URL 쿼리(`role`, `matchId`)로 탭 선택과 해당 카드 스크롤·강조를 한다. 실시간 푸시(STOMP 신호)는 `feature/notifications-realtime`(2026-10-11)에서 구현됐고, 토스트는 만들지 않았다. 연결 중에는 벨 개수를 신호로 즉시 갱신하고, `/notifications` 목록은 바꾸지 않은 채 '새 알림이 있어요. 눌러서 불러오기' 버튼만 띄운다.
- 채팅(구현 완료, 2026-10-11, `feature/chat`, 백엔드 V11): 경로 `/exchange/matches/:matchId/chat`(`pages/ChatPage.tsx`, 로그인 보호, 매칭 참여자만). 보내기는 REST `POST .../messages`, 받기는 STOMP `/user/queue/chat` 구독만 한다(클라이언트 SEND 없음). STOMP 연결(`@stomp/stompjs`)은 `api/realtime.ts`가 맡고 `hooks/useChatSocket.ts`로 화면에 붙인다. 로그인한 앱 전체가 탭당 연결 1개를 공유한다(`api/realtimeHub.ts`, 벨 훅과 채팅 훅이 참조 카운트로 붙고 마지막 참조가 풀리면 끊는다). WS 주소는 `VITE_WS_URL`이 없으면 API 주소에서 `/ws`로 계산한다. 토큰은 CONNECT 네이티브 헤더로만 보낸다. 끊기면 1초~30초 지수 백오프로 재연결하고, 연결될 때마다 `after` 커서로 빈 구간을 채운다. 토큰이 재발급되면 새 토큰으로 다시 연결한다. 읽음은 마지막으로 읽은 메시지 포인터(`PUT .../messages/read`)를 화면이 보이고 맨 아래에 있을 때 디바운스로 보내며, 내가 보낸 마지막 메시지 아래 상대가 읽었으면 '읽음'을 표시한다. 상단 상태 배너가 매칭 상태(예약 중·교환 완료·취소 등)를 보여주고(시스템 메시지는 저장하지 않음), 종료(CANCELED·COMPLETED)된 매칭은 읽기 전용이라 입력이 비활성화된다. 본문은 텍스트만 1000자(코드 포인트 카운터), IME 조합 중 Enter는 전송하지 않는다. 전송은 `clientMessageId`로 재전송에 멱등이다. 접근성: 연결 상태와 새 메시지는 role=status로 낭독하고, 메시지마다 '나:' 라벨을 붙이며, 입력 중에는 포커스를 빼앗지 않는다. 헤더 메시지 아이콘과 채팅 알림은 두지 않는다(매칭 카드 뱃지만). 알려진 한계: 브라우저 수동 점검과 실서버 STOMP 연동 점검은 아직 하지 않았고, 프론트 테스트 러너는 없다(tsc·build 통과).
- 관리자·공연 수정 제안·내 티켓 인증(구현 완료, 2026-10-12, `feature/admin`, 백엔드 V12): 헤더 '관리자' 링크는 ADMIN일 때만 보이고, 그때 이름 최대 폭을 40px로 줄여 md(768px) 경계에서 넘치지 않게 했다. 관리자 가드 `AdminRoute`는 `/api/users/me`의 `profile.role`로 판단하며 로딩 중에는 '권한을 확인하는 중'을 띄운다(최종 방어는 서버 `/api/admin/**`의 ADMIN 검사). 관리자 화면은 `/admin`(대시보드), `/admin/performance-edit-proposals`(제안 값과 현재 값을 비교해 승인·거절 사유 입력), `/admin/seat-claims`(사진·요청자·현재 그 좌석을 가진 티켓 요약, 승인·거절)다. 일반 화면은 공연 상세 '정보가 틀렸어요'의 수정 제안 폼(제목·공연장 중 하나 이상과 사유), `/my/edit-proposals`(내 수정 제안), 티켓 등록에서 같은 좌석이 이미 등록돼 있을 때 나오는 '내 티켓 인증' 링크 → `/seat-claims/new`(회차·구역·열·번 쿼리, 예매내역 사진 1장 jpeg/png/webp 5 MiB 이하, 예매번호·이름을 가리라는 안내), `/my/seat-claims`(내 인증 요청, 처리된 요청은 사진을 보여주지 않음)다. MyPage에 두 목록 링크가 있다. 브라우저 점검은 아직 하지 않았고 tsc·빌드는 통과.
- 헤더 폭: 벨 추가로 md(768px) 경계에서 넘치지 않도록 이름 최대 폭을 `md:max-w-[168px]`에서 `md:max-w-[104px]`로 줄였다(`components/Header.tsx`). 계산으로 확인했고 브라우저 실측은 하지 않았다.

- 공연 등록 화면은 3단계(①티켓팅 링크 ②공연 정보: 제목·공연장 이름(텍스트)·회차 ③확인·등록)다. 1단계에서 링크를 넣고 중복 확인(`lookup`)을 하면, 미등록 링크일 때 `GET /api/performances/preview`를 이어서 불러 링크 페이지에서 읽은 제목·공연장을 2단계 입력칸에 미리 채운다(제목·공연장이 모두 비어 있을 때만 채우고, 같은 링크는 다시 부르지 않는다). 2단계에는 '링크에서 읽은 정보예요. 맞는지 확인하고, 다르면 고쳐 주세요.' 안내를 보인다. 링크에서 읽은 공연 기간이 있으면 회차 입력 근처에 기간만 안내하고('회차 일시는 링크에서 읽을 수 없어 직접 입력해 주세요.'), 회차는 자동으로 채우지 않는다. 15초가 넘으면 읽기를 멈추고 그대로 2단계로 가며, 읽기 실패나 미지원 사이트도 등록을 막지 않는다. 자동 등록은 하지 않고 직접 입력은 항상 가능하다(2026-10-12, `feature/link-autofill`. 브라우저 점검은 아직 하지 않았고 tsc·build는 통과). 공연장 검색·VenuePicker는 삭제됐고 공연은 등록 후 수정·삭제할 수 없고(상세 화면 읽기 전용) 변경은 관리자 수정 제안 승인으로만 하며(2026-10-12 구현, 위 '관리자·공연 수정 제안' 항목) 승인 전에는 반영되지 않는다. (로고 변경은 완료)

## 다음 단계
0. 관리자 페이지 브라우저 수동 점검 (구현 완료 2026-10-12, `feature/admin`)
1. 교환 화면 브라우저 수동 점검·실서버 연동
2. 마이페이지 교환 이력 화면(`exchange_history` 조회 API 필요, 다음 브랜치). 채팅(V11)과 알림(실시간 푸시 포함, `feature/notifications-realtime`)은 2026-10-11 구현 완료(토스트는 만들지 않았다. 채팅·알림 실시간 모두 브라우저 점검은 미실시). (교환 완료·'이미 교환된 좌석이에요' 안내는 2026-10-11 구현, 후보 카드 '예약 중' 뱃지는 폐기)
3. 차단·신고 화면 (차단은 V13 예정, 차단 시 채팅 불가)
