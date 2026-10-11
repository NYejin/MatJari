---
title: 실시간 채팅(STOMP) JWT 인증·인가 조사
type: research
tags: [chat, websocket, stomp, jwt, security]
sources: [https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html, https://docs.spring.io/spring-security/reference/6.5/servlet/integrations/websocket.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/overview.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html, https://docs.spring.io/spring-framework/reference/web/websocket/server.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/configuration-performance.html, https://docs.spring.io/spring-framework/reference/web/websocket/fallback.html, https://docs.spring.io/spring/reference/6.2/web/websocket/stomp/authorization.html, https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html, https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket, https://docs.spring.io/spring-boot/3.3/appendix/dependency-versions/coordinates.html]
updated: 2026-10-11
confidence: medium
status: draft
---

# 실시간 채팅(STOMP) JWT 인증·인가 조사

> 이 문서는 조사 요약이며 채팅 구현 결정이 아니다. 구현 결정 근거는 [채팅 설계 근거](../decisions/chat-design.md), 구현 후 확인 결과는 아래 '구현 후 확인 결과' 절에 있다. 프로젝트 맥락과 확정 결정은 [CLAUDE.md](../../CLAUDE.md), 백엔드 현황은 [백엔드 README](../../SeatSwap/backend/README.md)를 본다. 여기서는 결정을 복사하지 않는다.

채팅은 교환 흐름에서 매칭(`CHATTING`) 이후 단계이며 **2026-10-11에 `feature/chat` 브랜치에서 구현됐다**(미병합, 커밋 `48d5e17`·`6f566f7`·`3bb70b5`·`9f56d59`·`8b662e5`·`fc20c82`). 이 조사 시점(2026-10-10~11 초)에는 `WebSocketConfig`가 TODO뿐이었고 `chat_message`도 없었으나, 지금은 V11(`chat_message`·`chat_read_state`)이 있고 STOMP 인증·푸시가 구현돼 있다. 구현 결정 근거는 [채팅 설계 근거](../decisions/chat-design.md), 잠금·인덱스 함정은 [함정 페이지 10절](../gotchas/lock-order-and-index-pitfalls.md), 알림 설계는 [알림 설계](../decisions/notification-design.md), 용어는 [용어집](../glossary.md) 참고. 아래 '결론'~'권장 구성'은 조사 당시의 문서 요약이며, 실제 구현과 다른 부분은 '구현 후 확인 결과'가 우선한다.

## 결론

핸드셰이크는 열어 두고, JWT는 STOMP CONNECT 프레임의 네이티브 헤더로 받아 `clientInboundChannel`의 `ChannelInterceptor`에서 검증한 뒤 Principal을 설정하는 방식이 Spring 문서의 패턴이다. 구독·전송 인가는 `@EnableWebSocketSecurity`와 `AuthorizationManager<Message<?>>`로 하고, 방 참여자 검사는 목적지 패턴과 Principal을 대조하는 커스텀 `AuthorizationManager`로 구현하는 것이 자연스럽다(공식 예제는 없고 추론). 토큰 만료는 Spring 문서가 다루지 않아 OWASP 권고에 따라 세션 단위로 관리하고, 만료 시 연결을 닫아 재연결을 유도한다. 확인한 Spring 문서 대부분이 최신(7.x) 버전이고 Spring Boot 3.3 라인(Spring Framework 6.1.21, Spring Security 6.3.10)에 고정한 페이지는 404라서, 6.3 전용 동작은 일부 확인하지 못했다.

## 항목별 요약

### 1. CONNECT 인증

- Spring Framework 토큰 인증 페이지는 `configureClientInboundChannel`에 `ChannelInterceptor`를 등록하고, CONNECT에서 인증해 `accessor.setUser(user)`를 설정하는 패턴을 보여준다. 예제의 헤더 읽기는 placeholder라 Authorization을 읽는 구체 API는 문서에 없다.
- 이 인증 인터셉터는 Spring Security의 메시지 인가 인터셉터보다 먼저 실행되어야 한다. 문서는 `@Order(HIGHEST_PRECEDENCE + 99)`인 별도 `WebSocketMessageBrokerConfigurer`를 권장한다.
- 브라우저는 표준 인증 헤더나 쿠키만 쓸 수 있고 커스텀 헤더는 못 보낸다(문서 명시). SockJS도 전송 요청에 헤더를 못 붙인다. 그래서 토큰은 STOMP CONNECT 헤더로 보낸다.
- 쿼리스트링 토큰은 가능하지만 서버 로그에 URL과 함께 남을 수 있다(문서 명시). OWASP도 경고하며 첫 메시지 인증을 권장한다. 인증 전에는 보호 데이터를 보내지 말라는 것도 OWASP 권고다.
- Spring Security는 인바운드 메시지에서 `SecurityContextHolder`를 `simpUser` 헤더 기준으로 채운다.
- `clientOutboundChannel`은 성능상 보안 대상이 아니므로 SUBSCRIBE 단계에서 막아야 한다(문서 권고).

### 2. 토큰 만료

- Spring 문서는 만료 검사 시점과 재인증을 다루지 않는다. 확인된 것은 인증이 CONNECT에서 일어나고 Principal이 세션 동안 유지된다는 점이다.
- OWASP 권고: 장기 연결에서 세션을 주기적으로 재검증(예: 30분), 만료 시 close code 1008로 닫기, 로그아웃 시 해당 사용자 연결 즉시 끊기, 토큰 교체(rotate).
- 재연결(REST로 재발급한 뒤 새 토큰으로 CONNECT)은 일반적인 설계 패턴이며 출처는 확인하지 못했다.

### 3. 구독·전송 인가

- Spring Security 6.5/7.x 문서: `@EnableWebSocketSecurity`와 `AuthorizationManager<Message<?>>` 빈을 쓰고, 매처는 `simpDestMatchers`, `simpSubscribeDestMatchers`, `simpTypeMatchers`, `nullDestMatcher`, 마지막에 `anyMessage().denyAll()`을 권장한다.
- 문서는 SpEL보다 구체적인 `AuthorizationManager` 클래스를 권장한다(독립 테스트 가능).
- 방 참여자 검사는 문서에 직접 예제가 없다. 목적지 패턴에서 id를 꺼내 Principal이 참여자인지 DB로 확인하는 커스텀 구현이 필요하다는 것은 추론이다.
- `/user/queue/...`는 `UserDestinationMessageHandler`가 세션별 목적지로 변환한다. `convertAndSendToUser`는 사용자의 모든 세션에, `@SendToUser(broadcast=false)`는 발신 세션에만 보낸다. 서버가 여러 대면 `userDestinationBroadcast`가 필요하다.

### 4. CSRF·Origin·크기 제한

- 문서상 CONNECT에는 기본적으로 유효한 CSRF 토큰이 필요하다(STOMP 헤더로 전달). Spring Security 6.5/7.x는 `@EnableWebSocketSecurity` 사용 시 CSRF를 설정할 수 없다고 명시하며, 우회하려면 `SecurityContextChannelInterceptor`와 `AuthorizationChannelInterceptor`를 직접 등록해야 한다.
- 문서는 stateless JWT에서 CSRF를 끄는 근거를 제시하지 않는다. JWT를 헤더로 직접 보내면 OWASP가 설명하는 CSWSH 위험이 줄 것이라는 것은 추론이다.
- Origin: 기본은 같은 origin만 허용한다. `setAllowedOrigins`는 `http://` 또는 `https://`로 시작해야 하고 `*`는 전체 허용이다. 목록을 지정하면 IFrame 전송이 꺼져 IE6~9를 지원하지 못한다. 브라우저 외 클라이언트는 Origin을 바꿀 수 있다(문서 경고). OWASP는 와일드카드 금지와 명시 allowlist를 권고한다.
- 크기·버퍼 관련 설정 항목: `setMessageSizeLimit`(문서 예제 128KB), `sendTimeLimit`(15초), `sendBufferSizeLimit`(512KB), `maxTextMessageBufferSize`(8192), 클라이언트는 16KB 단위로 분할, 스레드 풀 기본값은 프로세서 수의 2배. OWASP는 메시지 64KB 이하, 분당 100건 속도 제한, 사용자별 연결 수 제한, idle timeout/heartbeat를 권고한다.
- Spring 내장 속도 제한 지원은 확인하지 못했다. 직접 구현해야 할 것으로 추론한다.

### 5. SockJS와 브로커

- Spring 문서는 SockJS를 제한적인 프록시에서 WebSocket이 막힐 때의 폴백으로 설명한다. 2026년 시점의 권장은 문서에 없다.
- SockJS 주의점: 동일 출처 정책(SOP)을 우회하므로 명시적 보호가 필요하고, 커스텀 헤더를 쓸 수 없으며, iframe 전송에는 `X-Frame-Options SAMEORIGIN`이 필요하다. heartbeat 기본값은 25초다.
- 순수 WebSocket은 stompjs의 `brokerURL`로 쓰고 SockJS는 선택이다. `enableSimpleBroker`의 `/topic`, `/queue`는 관례다.
- 외부 브로커 릴레이는 다중 인스턴스 배포 때 필요하다고 보지만 문서에 기준은 없다(추론).

## 우리 프로젝트에 적용할 권장 구성 (제안, 결정 아님)

1. `/ws` 핸드셰이크는 인증 없이 열고 JWT는 CONNECT의 Authorization 네이티브 헤더로만 받는다(쿼리스트링 금지). 인증 인터셉터는 `HIGHEST_PRECEDENCE + 99`, 실패하면 CONNECT를 거부한다.
2. `@EnableWebSocketSecurity`에 `AuthorizationManager`를 두고 `/user/queue/**` 구독, `/app/**` 전송, `/topic/match.{id}` 구독을 매칭 참여자 검사로 막으며, 마지막에 `anyMessage().denyAll()`을 둔다.
3. 만료는 세션 속성에 `exp`를 저장해 만료 시 1008로 닫고, 클라이언트는 REST 재발급 후 재연결한다. 메시지 속도·크기(약 64KB)·사용자별 연결 수 제한은 직접 구현한다.
4. Origin은 `https://` 명시 allowlist만 쓰고 `*`는 쓰지 않는다. CSRF는 Spring Security 6.3.10 동작을 확인한 뒤 결정한다. 확인 전에는 JWT 헤더 방식이라는 근거만으로 끄는 것을 확정하지 말고, same-origin 관련 설정을 테스트로 검증한다.
5. 초기에는 순수 WebSocket과 단일 인스턴스 simple broker로 시작하고, 다중 인스턴스가 되면 외부 브로커 릴레이와 `userDestinationBroadcast`를 검토한다.

## 구현 후 확인 결과 (2026-10-11, 코드·통합 테스트 기준)

구현은 `/ws` 순수 WebSocket 엔드포인트(SockJS 없음), `spring-security-messaging` 미사용, 커스텀 `StompAuthChannelInterceptor`다. 확인한 항목:

- **CSRF**: 앱이 `csrf.disable()`이고 messaging 보안 모듈을 쓰지 않아 STOMP CSRF 토큰 요건이 없다. 위 4절의 "CSRF 설정 불가" 문서 내용은 `@EnableWebSocketSecurity`를 쓸 때의 이야기이고 이 구현에는 해당하지 않는다. CSRF를 끈 판단 자체의 안전성(CSWSH)은 Origin 검증(아래)과 CONNECT 헤더 JWT에 의존한다.
- **`/ws/**` 매칭**: HTTP 보안 설정의 `/ws/**` permitAll로 `/ws` 핸드셰이크가 통과한다(통합 테스트로 확인). 인증은 CONNECT 프레임에서만 한다.
- **Origin**: 허용 목록 밖 Origin의 핸드셰이크는 403이다. 허용 목록은 설정 `seatswap.websocket.allowed-origins`이고 `*`가 들어 있으면 시작 시 거부한다.
- **만료 세션 1008**: 만료된 세션을 스위퍼가 close code 1008로 닫는 것을 수신으로 확인했다. 인증 안 한 소켓도 10초 뒤 1008이다. 인터셉터가 거부했을 때의 close는 Spring이 1002로 닫는다.
- **RECEIPT 없음**: SUBSCRIBE에 receipt 헤더를 붙여도 RECEIPT 프레임이 오지 않는 것을 확인했다. 프론트는 RECEIPT를 기다리지 않는다.
- **목적지당 구독 1개**: 구독이 무제한이면 자원 고갈 여지가 있어(리뷰 중간 지적) 처음에는 세션당 1개로 제한했다. 실시간 알림 푸시로 허용 목적지가 `/user/queue/chat`, `/user/queue/notifications` 두 개가 되면서 목적지당 1개(세션당 최대 2개)로 바뀌었다. 같은 목적지의 두 번째 SUBSCRIBE나 이미 쓰인 구독 id 재사용은 거부하고 세션을 1008로 닫는다. 이유는 [함정 페이지 10절](../gotchas/lock-order-and-index-pitfalls.md), 푸시 내용은 [알림 설계](../decisions/notification-design.md) 참고.

위 확인은 백엔드 STOMP 통합 테스트 10건과 코드 읽기 기준이다. 실제 브라우저와 `@stomp/stompjs` 연동 점검은 하지 않았다. 아직 확인하지 못한 것은 아래와 같다.

### 후속 메모

- 토큰 만료·사용자 변경 후 푸시가 최대 약 30초 유지될 수 있다. 차단·제재를 구현할 때는 해당 사용자의 세션을 강제로 종료하는 훅이 필요하다.
- push payload와 REST 응답에 상대 `senderId`(users.id)가 노출된다. 차단·신고 브랜치에서 `mine` 같은 불리언으로 대체할지 검토한다.

## 미확인

구현 후에도 확인하지 못한 것이다.

- 하트비트 미응답 시 종료 동작(설정은 10초/10초이지만 종료를 시험하지 않음)
- 16KB 초과 프레임 거부(메시지 크기 제한 16KB 설정을 시험하지 않음)
- 실제 브라우저·`@stomp/stompjs` 연동
- 토큰 만료를 메시지 단위로 재검사하는 내장 기능
- `setAllowedOriginPatterns`의 공식 설명
- 다중 인스턴스에서의 sticky session 요구와 외부 브로커 세부(현재 단일 인스턴스 메모리 전제)
- SockJS와 순수 WebSocket의 2026년 공식 권장
- 방 참여자 검사를 `AuthorizationManager`와 `ChannelInterceptor` 중 어디에 둘지의 공식 권장(구현은 REST 전송 + 사용자 큐 구독만 허용이라 목적지 기반 참여자 검사가 필요 없었다)
- Spring Security 6.3.10에서 `@EnableWebSocketSecurity` 사용 시의 CSRF 동작(이 구현은 해당 모듈을 쓰지 않음)

## 출처

접근일은 모두 2026-10-10이다.

| URL | 확인한 내용 |
|---|---|
| https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html | 메시지 인가, SecurityContext 전파, CSRF (7.1.x) |
| https://docs.spring.io/spring-security/reference/6.5/servlet/integrations/websocket.html | `@EnableWebSocketSecurity`, 매처, CSRF 설정 불가 (6.5) |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html | CONNECT 인증 패턴, 헤더 제약 |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html | 어노테이션 기반 메시지 처리 (`@SendToUser` 등) |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html | 사용자 목적지, 다중 서버 브로드캐스트 |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/overview.html | STOMP 개요 |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html | STOMP 활성화, 브로커 설정 |
| https://docs.spring.io/spring-framework/reference/web/websocket/server.html | 허용 origin, 버퍼 설정 (7.0.9) |
| https://docs.spring.io/spring-framework/reference/web/websocket/stomp/configuration-performance.html | 스레드 풀, 시간·크기 제한 |
| https://docs.spring.io/spring-framework/reference/web/websocket/fallback.html | SockJS 폴백, 주의점 |
| https://docs.spring.io/spring/reference/6.2/web/websocket/stomp/authorization.html | 인가 (6.2 일부, 7.0 페이지는 404) |
| https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html | OWASP WebSocket 보안 권고 (인증, 만료, Origin, 제한) |
| https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket | 브라우저 생성자는 url·protocols만 받음 |
| https://docs.spring.io/spring-boot/3.3/appendix/dependency-versions/coordinates.html | Boot 3.3.13이 Spring Framework 6.1.21, Security 6.3.10에 대응 |

## 신뢰도

전체 confidence는 medium이다. 구현 후 확인 결과 절은 코드와 통합 테스트로 확인한 사실이며, 조사 당시 요약 부분(결론~권장 구성)은 아래 판단 그대로다. 항목 1·3·4의 핵심 구성은 Spring 공식 문서 여러 페이지에서 일관되지만 대부분 7.x 페이지라 medium-high 수준이다. 항목 2는 OWASP 일반 권고에 의존해 low-medium, 항목 5는 추론이 섞여 medium이다. 프로젝트가 쓰는 Spring 6.3 라인 전용 문서를 확인하지 못했으므로 구현 전에 해당 버전 동작을 테스트로 확인해야 한다.
