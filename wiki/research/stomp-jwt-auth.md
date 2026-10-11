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

> 이 문서는 조사 요약이며 채팅 구현 결정이 아니다. 구현 때 설계를 확정한다. 프로젝트 맥락과 확정 결정은 [CLAUDE.md](../../CLAUDE.md), 백엔드 현황은 [백엔드 README](../../SeatSwap/backend/README.md)를 본다. 여기서는 결정을 복사하지 않는다.

채팅은 교환 흐름에서 매칭(`CHATTING`) 이후 단계이며 아직 구현되지 않았다. 코드 확인(2026-10-11): `build.gradle`에 websocket 스타터 의존성만 있고 `WebSocketConfig`는 `@Configuration` 클래스에 TODO 주석뿐이다(엔드포인트·브로커·인터셉터 없음). 채팅 메시지 테이블(`chat_message` 등)은 아직 없고, 만든다면 V10(알림 `notification`)까지 적용된 뒤이므로 V11 이후 마이그레이션이다. 실시간 알림(STOMP)은 다음 브랜치 `feature/notifications-realtime`에서 다루며, 알림 설계는 [알림 설계](../decisions/notification-design.md) 참고. 용어는 [용어집의 매칭 상태](../glossary.md) 참고.

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

## 미확인

- Spring Security 6.3.10 기준 WebSocket CSRF 동작, 6.3에서의 `sameOriginDisabled` 차이
- 토큰 만료를 메시지 단위로 재검사하는 내장 기능
- CONNECT 헤더에서 Authorization을 읽는 공식 API·예제
- `setAllowedOriginPatterns`의 공식 설명
- 크기·버퍼 기본값(Spring 6.1 기준)
- 메시지 속도 제한 내장 지원
- 다중 인스턴스에서의 sticky session 요구와 외부 브로커 세부
- time-to-first-message 설정
- SockJS와 순수 WebSocket의 2026년 공식 권장
- 방 참여자 검사를 `AuthorizationManager`와 `ChannelInterceptor` 중 어디에 둘지의 공식 권장
- 제3자 블로그의 `sameOriginDisabled` 설명(검증하지 않음)

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

전체 confidence는 medium이다. 항목 1·3·4의 핵심 구성은 Spring 공식 문서 여러 페이지에서 일관되지만 대부분 7.x 페이지라 medium-high 수준이다. 항목 2는 OWASP 일반 권고에 의존해 low-medium, 항목 5는 추론이 섞여 medium이다. 프로젝트가 쓰는 Spring 6.3 라인 전용 문서를 확인하지 못했으므로 구현 전에 해당 버전 동작을 테스트로 확인해야 한다.
