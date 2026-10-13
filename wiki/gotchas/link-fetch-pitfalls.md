---
title: 링크 가져오기 함정 (robots.txt 오류 페이지, HttpClient IP 고정, 포스터 자리표시·핫링크)
type: gotcha
tags: [링크입력, robots, httpclient, ssrf, 포스터, 핫링크]
sources: [../decisions/link-autofill-design.md, ../decisions/poster-display-design.md, https://ticket.yes24.com/robots.txt, https://ticket.melon.com/robots.txt]
updated: 2026-10-13
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

## 3. 포스터 주소: 자리표시 이미지와 핫링크 차단

- **자리표시 이미지**: YES24는 포스터가 없는 공연에도 `og:image`로 `_noimage.gif` 자리표시 이미지를 준다. 이 주소는 허용 호스트(`tkfile.yes24.com`)와 같아서 호스트 검사만으로는 걸러지지 않는다. 그래서 경로에 `noimage`가 든 주소는 포스터 없음으로 처리한다(`LinkInfoParsers.posterUrl`). 재현 조건: 포스터 없는 YES24 공연의 미리보기에서 `posterUrl`이 자리표시 주소로 나오면 실패다.
- **핫링크 차단**: 포스터는 브라우저가 원 사이트에서 직접 불러오므로, 원 사이트가 Referer로 막으면 로드가 실패한다. 대응은 로드 실패 시 포스터 영역 전체를 숨기는 것이다. `referrerpolicy="no-referrer"`를 쓰고, Referer 위조나 서버 대리 다운로드 같은 우회는 하지 않는다(판단 근거는 [포스터 설계 근거](../decisions/poster-display-design.md)).
- **CDN 변경**: 허용 호스트 목록은 2026-10-13 실측으로 고정돼 있다. 사이트가 이미지 CDN을 바꾸면 포스터가 조용히 안 뜬다. 화면은 숨김으로 처리되어 깨지지 않는다.
- 근거: 2026-10-13 실측(원 페이지 HTML의 og:image와 이미지 주소, 브라우저 Playwright 확인). 실측 시점의 값이라 confidence는 medium이다.
