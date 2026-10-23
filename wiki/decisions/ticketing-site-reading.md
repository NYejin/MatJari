---
title: 티켓팅 사이트별 공연정보 읽는 법
type: decision
tags: [링크입력, AI, 크롤링, renderer, 인터파크, 티켓링크, YES24, 멜론]
sources: [CLAUDE.md, link-autofill-design.md, ai-detail-extract-design.md, https://nol.yanolja.com/robots.txt, https://tickets.interpark.com/robots.txt, https://www.ticketlink.co.kr/robots.txt]
updated: 2026-10-23
confidence: medium
status: draft
---

# 티켓팅 사이트별 공연정보 읽는 법

링크 하나로 공연 제목·공연장·기간·포스터(미리보기)와 회차·좌석 구역(AI [공연정보 불러오기])을 채우기 위해, 사이트마다 **어디에 무엇이 있고 어떻게 가져오는지**를 2026-10-22 실측으로 정리한다. 원칙은 그대로다: 사용자 URL 은 요청하지 않고 `SourceKeyResolver` 키(사이트+숫자 id)로 만든 **고정 주소**만 요청한다, 봇임을 밝히는 `MatJariBot` User-Agent 를 쓴다, robots.txt 가 막은 경로와 로그인·캡차·봇 차단은 우회하지 않는다.

## 한눈에

| 사이트 | 고정 주소(요청 대상) | 페이지에 바로 있는 것 | 상세 글자·이미지 | 회차 | 좌석 구역 |
|---|---|---|---|---|---|
| 멜론 | `ticket.melon.com/performance/index.htm?prodId={id}` | 제목·공연장·기간(본문 HTML), 포스터 | 서버가 보낸 HTML 에 상세 이미지(`cdnticket.melon.co.kr/.../upload/product/`) | AI: 본문 글자+상세 이미지 맥락 | AI 좌석표 인식 |
| 인터파크(NOL) | `nol.yanolja.com/ticket/products/{id}` | 서버가 글자를 다 채워 보냄: 제목(JSON-LD `Product`)·장소·기간·운영(공연) 시간·가격, 포스터(`ticketimage.interpark.com`) | HTML 안 상세 이미지(`http://ticketimage.interpark.com/Play/image/etc/...`, https 로 올려 받음) | AI: 본문 글자+상세 이미지 | AI 좌석표 인식 |
| 티켓링크 | `www.ticketlink.co.kr/product/{id}` | 빈 껍데기: 제목·공연장·기간(JSON-LD `Event`)·포스터뿐 | **화면이 뜬 뒤 채워진다** → renderer(헤드리스 Chromium)로 끝까지 띄운 뒤 화면 글자(innerText)와 이미지(`image.toast.com/aaaaab/ticketlink/...`) | AI: 그린 화면 글자(예매 달력·회차 선택 포함)+상세 이미지 | AI 좌석표 인식(상세 이미지에 좌석표가 있을 때) |
| YES24 | `ticket.yes24.com/Perf/{id}`(302면 `/Special/{id}`) | 제목·공연장·기간(JSON-LD `Event`)·포스터 | 상세 영역이 화면이 뜬 뒤 채워지고, **자동 브라우저는 봇 차단(STCLab)이 막는다** → 지금은 링크 HTML 글자만 | AI: 링크 HTML 글자(공연 시간이 적혀 있으면 읽음) | 사용자가 좌석표 이미지를 올리면 인식 |

## 사이트별 메모

### 멜론
- 상세 이미지가 서버 HTML 안에 있어 가장 쉽다. 이미지 주소 뒤에 변환 꼬리(`.jpg/melon/quality/90/optimize`)가 붙으므로 같은 파일 비교는 확장자까지로 한다(`VerifiedZones.imageKey`).
- 사람이 검수한 정답 38장(정답검수 탭)과 상품 번호·좌석표 이미지가 같으면 AI 없이 검수 정답을 쓴다.

### 인터파크 → NOL(야놀자)
- 옛 도메인 `tickets.interpark.com`·`ticket.interpark.com`·`mobileticket.interpark.com` 의 robots.txt 는 지정 검색봇 외 **모든 봇을 막는다** → 요청하지 않는다.
- 2026-10 현재 `tickets.interpark.com/goods/{id}` 는 `nol.yanolja.com/ticket/products/{id}` 로 301 이고 상품 번호가 같다. `nol.yanolja.com` 의 robots.txt 는 `/ticket/` 을 막지 않는다(막는 것은 /search/·/member/·/mypage/·/order/ 등). 그래서 인터파크 링크는 키 `interpark:{id}` → NOL 상품 주소로 읽는다.
- 페이지는 서버 렌더링이라 HTML 만으로 충분하다(헤드리스 브라우저 불필요). 줄 단위 글자에서 "장소"·"기간" 이름표 다음 줄이 값이다. "장소"는 메뉴 탭에도 있으니 "장소 → 값 → 기간" 순서인 줄을 먼저 찾는다. 오픈런은 "2025.05.01 ~ 오픈런"이라 끝 날짜를 비운다.
- 확인한 상품: 26014836(로꼬 콘서트, 회차 12/19 17:00·구역 9개), 23016780·24006928·24008626·25005387·25008376·25009291(제목·공연장·기간).

### 티켓링크
- 상품 HTML 은 약 20KB 의 빈 껍데기다. 상세·회차는 스크립트가 채운다.
- renderer 는 **정식 Chromium 새 headless 모드**(MatJari/renderer, Debian 패키지)를 쓴다. 구형 headless(chromedp/headless-shell)로 열면 상품 페이지가 뜬 뒤 `/home` 으로 넘어갔다(언어·시간대를 맞춰도 같음). 브라우저 지문을 속이는 우회는 하지 않고, 이 PC 크롬과 같은 정식 브라우저로 바꾸자 그대로 열렸다.
- 기다리는 조건: 화면 글자에 "회차"·"상품 상세"가 생기거나 이미지가 7장 넘게 뜨면, 1.5초 더 기다려 다시 읽는다. 넘기는 이미지는 세로로 긴 것(높이 ≥ 2000, 높이 ≥ 폭×2)만. 지난 공연은 `/home` 으로 넘어가므로 그때는 쓰지 않는다.
- 확인한 상품: 66145(스위니토드, 회차 100개: 화~금 19:30·토 14:00/19:00·일 13:00/18:00, 월 휴연).

### YES24
- `/Perf/{id}` 와 `/Special/{id}` 는 같은 번호 체계이고 상품마다 한쪽만 200, 다른 쪽은 302 다(302면 고정 주소 `/Special/{id}` 로 한 번 더).
- 상세 영역은 `/New/Perf/Detail/Ajax/axPerfContentsWeight.aspx?IdPerf={id}` 를 페이지 스크립트가 불러 채운다. 헤드리스 브라우저로 열면 봇 차단 서비스(STCLab)가 Access Denied 로 막는다(2026-10-22 시험, 처음 한 번은 열렸다가 곧 차단). 봇이라고 밝힌 단순 HTML 요청은 허용된다.
- 2026-10-23 추가 점검(봇임을 밝힌 단순 GET):
  - 모바일 상품 페이지 `m.ticket.yes24.com/Perf/{id}` 는 서버 렌더링이라 '알립니다'·공연 시간 글자는 있지만 상세 이미지는 없다. 모바일 상세 페이지 `Perf/Detail/PerfContents.aspx?IdPerf={id}` 도 스크립트가 `Ajax/AxPerfContents.aspx` 로 채운다.
  - PC 상세 내용 주소 `axPerfContentsWeight.aspx?IdPerf={id}` 는 단순 요청에도 200 JSON(`PerfNotice`·`PerfContent`(상세 이미지 `tkfile.yes24.com/Upload2/Board/...`)·`PerfOrganization`)을 준다. 1KB 남짓이고 로그인·토큰이 없다.
- 남은 선택지(사용자 결정 대기): ① 공연예술통합전산망(KOPIS) 공공 API 로 같은 공연을 찾아 상세 이미지 목록(`styurls`)·공연 시간 안내(`dtguidance`)를 받기(무료 서비스 키 필요, 예매처 링크 `relates` 로 YES24 번호 대조) ② 위 상세 내용 주소를 고정 주소로 단순 요청(규칙 "내부 API 호출 안 함"을 바꿔야 함) ③ 사용자가 상세 이미지 주소를 붙여 넣기(`tkfile.yes24.com` 고정) ④ 좌석표 이미지 올리기(구현됨).

## AI 에 넘기는 것 (2026-10-22 바꿈)
- **본문 글자 + 상세 이미지를 함께** 보낸다. 본문 앞에 `[참고] 오늘(KST)·링크에 적힌 공연 기간` 을 붙여 연도를 판단하게 한다. 본문은 공백을 줄여 최대 8,000자.
- 회차는 정규식이 아니라 **맥락**으로 만든다: 기간 + 요일별 시각("평일 8시 / 토 3시·7시") + 날짜 목록·회차표·휴연일·예매 화면의 회차 선택 안내를 함께 보고 날짜마다 시각을 적는다. 최대 100개(등록 한 번의 회차 상한)라 출력 한도는 16,000 토큰.
- AI 회차를 먼저 쓰고, 정규식(`SessionTextParser`) 결과는 AI 가 회차를 못 줄 때만 쓴다.
- 상세 이미지가 없어도 본문 글자가 있으면 글자만으로 회차를 읽는다(이때 구역은 쓰지 않는다).

## 비용 감각(실측)
- 멜론 KSPO DOME 1회(이미지 조각 약 10장+구역 판독): 약 $0.006~0.008.
- 티켓링크 스위니토드 1회(이미지 20조각, 입력 4만·출력 7천 토큰): 약 $0.008.
- 글자만 읽기(YES24): 1센트 미만.
