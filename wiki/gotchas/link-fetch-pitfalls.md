---
title: 링크 가져오기 함정 (robots.txt 오류 페이지, HttpClient IP 고정)
type: gotcha
tags: [링크입력, robots, httpclient, ssrf]
sources: [../decisions/link-autofill-design.md, https://ticket.yes24.com/robots.txt, https://ticket.melon.com/robots.txt]
updated: 2026-10-12
confidence: medium
status: draft
---

# 링크 가져오기 함정

링크 기반 공연정보 자동 입력([설계 근거](../decisions/link-autofill-design.md))을 만들며 확인한 함정이다.

## 1. 티켓팅 사이트 robots.txt가 규칙 대신 오류 페이지를 돌려준다

- 증상: 2026-10-12 실측에서 `ticket.yes24.com`·`ticket.melon.com`의 `/robots.txt`가 규칙 파일이 아니라 오류 페이지를 돌려준다.
- 해석: 규칙이 없는 것으로 본다(robots 해석 관례). 이는 허용 근거로 쓸 수는 있으나 사이트가 나중에 바꿀 수 있다. 접근은 공개 페이지, 낮은 빈도, 식별 가능한 User-Agent로 제한하고 속도 제한·캐시를 둔다.
- 티켓링크는 규칙이 있어 상품 상세는 허용, 예매·내 정보 등 일부 경로는 금지다. 인터파크는 robots.txt 때문에 제외했다.
- 확인 방법: 대상 사이트를 늘릴 때마다 응답 본문이 규칙 형식(`User-agent:` 등)인지 직접 확인한다. 이 실측은 한 시점의 결과다.

## 2. Java HttpClient는 연결 IP를 고정할 수 없다

- 증상: 요청 전에 DNS 결과가 공인 IP인지 검사해도, `HttpClient`가 연결할 때 호스트명을 다시 해석하므로 검증한 IP로 접속한다는 보장이 없다(DNS rebinding 틈).
- 이유: JDK `HttpClient`는 사용할 IP를 지정하는 공식 수단이 없다. IP로 직접 접속하면 TLS SNI·인증서 호스트 검증과 Host 헤더를 따로 맞춰야 한다.
- 선택: 임의 URL을 받는 대신 요청 URL을 고정 템플릿 3종으로 만들어 이 문제를 구조적으로 피했다. 선검증은 방어 한 겹일 뿐이고 pinning은 하지 않았다. 대안 비교는 [설계 근거 4절](../decisions/link-autofill-design.md).
- 대상을 임의 도메인으로 넓힌다면 이 한계가 실제 위험이 되므로 그때 다시 설계해야 한다.
