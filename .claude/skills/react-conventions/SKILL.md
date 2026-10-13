---
name: react-conventions
description: MatJari/frontend(React + TypeScript) 코드(화면, 컴포넌트, API 연동) 작성 시 반드시 참고할 폴더 구조와 컨벤션. 특히 좌석맵 오버레이 UI 관련 규칙을 포함한다.
---

# React 프론트엔드 컨벤션

## 위치
`MatJari/frontend/src/`

## 폴더 구조
```
src/
├── components   # 공용 컴포넌트
├── pages        # 라우트 단위 화면
├── api          # axios 클라이언트 (인터셉터로 JWT 처리)
├── types        # 공용 타입
└── hooks        # useAuth 등 커스텀 훅
```

## 좌석맵 오버레이 규칙
> 좌석표 트랙 동결(2026-10-07): 좌석표 화면·오버레이 코드는 삭제되어 git 태그 `archive/seatmap-track-20261007`에 보관되어 있다. 재개 시 참고용 규칙이며 현재 코드에는 없다.
- 백엔드가 내려주는 좌표는 원본 이미지 픽셀 기준이다. 화면에 표시할 때 이미지의 실제
  렌더링 크기에 맞춰 좌표를 스케일링해야 한다 (반응형 대응 필수).
- 좌석 사각형은 SVG `<rect>` 오버레이로 그리고, 판매 상태에 따른 색상 구분은 하지 않는다
  (이 서비스는 이미 판매된 좌석만 다룸 — 선택됨/선택안됨만 구분).
- 좌석을 탭하면 항상 "오류 신고" 버튼을 함께 노출해, 인식이 잘못됐을 때 바로 신고할 수 있게 한다.
- 이미지는 불러오지 않고 **좌표만** 그린다(원본 이미지는 저장하지 않음). 원본 픽셀 좌표를 그대로 SVG `viewBox`(이미지 크기)에 넣고 SVG 크기만 반응형으로 맞추면 별도 스케일링이 필요 없다. 줌과 키보드 조작을 지원한다.
- 좌석 표기는 'N열 M번'(필드명 row/col은 유지). 좌석에 `section`(구역·층)이 있고 구역이 둘 이상이면 '구역 N · M열 K번'으로 쓰며, 열 번호는 구역별로 다시 시작하므로 (section, row, col)로 좌석을 구분한다.
- [보류·삭제됨, 태그 `archive/seatmap-track-20261007`] 아래 정정 신고·번호 수정 설명은 삭제된 좌석표 기능의 보류 명세다. `/api/seatmaps/{id}/corrections` 엔드포인트는 현재 백엔드에 없으므로 호출하지 않는다. DRAFT 좌석표는 '임시(확인용)' 안내를 함께 보여준다(교환 매칭은 좌표가 아니라 본인 좌석 정보·희망 범위로 함). 오류 신고 버튼은 OFFICIAL 좌석표에서만 활성이다(정정 신고 `POST /api/seatmaps/{id}/corrections`, 열·번을 순서대로 호출하고 409는 건너뜀, note 입력 없음). DRAFT는 신고 대신 '번호 수정' 모드로 직접 고친다(대기 목록·미리보기·사유 입력 후 한 번에 저장, 변경 좌석은 점선+사선 빗금). 변경 이탈 경고는 `beforeunload`+`confirm`(BrowserRouter라 `useBlocker` 불가). 사용자 role은 `/users/me`로 UI만 분기하고 서버가 최종 판정한다.
- 임시 기능(TEMP-DRAFT-DELETE)은 'TODO: 임시 기능' 주석을 달고 제거하기 쉽게 한곳에 모은다.

## 일반 규칙
- API 호출은 `api/` 아래 도메인별 파일로 분리 (`api/tickets.ts`, `api/exchange.ts` 등)
- `any` 타입 금지 — 백엔드 DTO에 대응하는 타입을 `types/`에 정의
- 라우팅은 `react-router-dom`, 인증 필요한 라우트는 공용 `<ProtectedRoute>`로 감싼다
- 모바일 터치 우선 설계 (버튼 최소 44px 터치 영역) — 플랫폼은 네이티브 앱이 아닌
  모바일 웹(반응형, 필요 시 PWA)으로 결정됨

## 계획된 화면 명세 (보류 — 당장 구현하지 않음, 구현 시 참고)

### 메인 페이지(HomePage.tsx) Phase 1
백엔드 API 호출 없이 정적 구성. 구현 시 아래 규칙을 따른다.

- **스타일**: Header.tsx의 `cls` 객체 패턴, `index.css`의 primary/secondary/accent 토큰만 사용 (새 색상·새 스타일 방식 금지).
  모바일 우선 + `md:` 확장, 버튼·링크 터치 영역 `min-h-11` 이상, 콘텐츠 최대 폭은 Header와 같은 `max-w-[1080px]`.
- **섹션 순서**
  1. Hero: 한 줄 가치 제안(예: "원하는 자리로, 안전하게 바꿔요", 문구는 다듬어도 됨), 보조 문장(이미 티켓을 가진 사람끼리
     좌석을 맞교환하거나 차액을 주고받는 서비스), 버튼 2개(주/보조), 좌석 격자 일러스트
     (이미지 파일 없이 Tailwind로 작은 정사각형 격자, 일부 칸만 강조색 = `accent` 토큰)
  2. 이용 방법 3단계: 티켓·좌석 등록 → 교환 요청/신청 → 채팅으로 조율하고 교환 완료
  3. 안전 장치: 이미 유효한 티켓을 가진 사용자끼리의 이동, 거래 후 후기·신뢰도 점수 시스템
  4. Footer: 서비스명과 간단한 안내 문구
- **로그인 상태 분기**: localStorage를 직접 읽지 말고 `useAuth()`의 상태를 쓴다.
  - 비로그인: 주 버튼 "회원가입"(/signup), 보조 버튼 "로그인"(/login)
  - 로그인: 주 버튼 "내 티켓 등록하기"(/tickets/new), 보조 버튼 "교환 요청 둘러보기"(/exchange)
  - 인증 확인 중(`isInitializing`)에는 버튼 영역만 자리를 유지해 화면이 튀지 않게 한다 (Header의 authPending 방식).
- **구조**: 섹션별 컴포넌트를 `components/home/` 아래로 분리하고 HomePage는 조립만 한다.
- **검증**: `npx tsc --noEmit`, `npx vite build` 통과 후 결과와 변경 파일 목록 보고.
- **현재 HomePage와의 차이 (구현 시 먼저 정리할 것)**: 지금 HomePage(`/`)는 로그인하면 공연 목록(API 호출)을 보여주고
  비로그인이면 소개와 로그인·회원가입 버튼을 보여준다. Phase 1 명세는 "API 호출 없음"이므로,
  랜딩 섹션 아래에 공연 목록을 유지할지, 목록을 별도 경로로 옮길지 결정이 필요하다.

## CSS 전략
- 전역 CSS나 CSS Module 대신 **Tailwind CSS v4 유틸리티 클래스**로 스타일링한다.
  이유: 페이지 수가 늘어나도 스타일 반복/클래스명 충돌이 없도록 하기 위한 결정.
- 설정: `@tailwindcss/vite` 플러그인(`vite.config.ts`) + `src/index.css`의 `@import "tailwindcss";`.
  `postcss.config.js`, `tailwind.config.js`는 두지 않는다.
- `src/index.css`에는 Tailwind import와 `@theme` 토큰(글꼴 `--font-sans`, 브레이크포인트, 색상)만 둔다.
  전역 규칙(요소 선택자 등)은 추가하지 않는다 — 기본 리셋은 Tailwind preflight가 담당.
- `.module.css`, `@apply`, 커스텀 CSS 클래스는 쓰지 않는다.
- **클래스명은 항상 완성된 문자열로 쓴다.** `bg-${color}-600`처럼 조각을 이어 붙이면 Tailwind가 소스에서
  클래스를 감지하지 못해 CSS가 생성되지 않는다. 조건부 스타일은 완성된 클래스 문자열 중 하나를 고르는 분기로 쓴다
  (예: `error ? "border-red-600 ..." : "border-gray-300 ..."`).
- 반복되는 클래스 조합은 컴포넌트로 묶거나(예: `AuthTextField`) 파일 상단/공용 상수로 모은다
  (예: `components/authFormClasses.ts`, Header의 `cls`). 조건부 클래스는 템플릿 문자열로 조합한다
  (clsx 등 추가 의존성 없음). 서로 덮어쓰는 클래스(색상, 같은 속성의 포커스 링 등)는 한 요소에 동시에 넣지 말고
  분기로 하나만 넣는다.
- **브레이크포인트는 `@theme`에서 px로 고정**한다: `--breakpoint-sm: 640px`, `--breakpoint-md: 768px`
  (모바일 메뉴 ↔ 데스크톱 전환), `--breakpoint-lg: 1024px`. JS에서 같은 기준이 필요하면(`matchMedia`)
  같은 px 값(`"(min-width: 768px)"`)을 쓰고 주석으로 연결한다.
- **색상 — 브랜드 토큰 (사용자 결정):** 브랜드 색은 `@theme`의 hex 토큰으로만 쓴다.
  - `primary` (메인 #8A2BE2 = `primary-600`, 50~900 스케일): 버튼 배경, 링크·로고·활성 메뉴 글자, 포커스 링
    (`primary-600/20`, `/40`), 연한 배경(`primary-50`). 눌림은 `primary-700`, 연한 배경 위 긴 글자는 `primary-800`.
  - `secondary` (보조 #BEA886 = `secondary-500`, 50/100/200/500/700): **글자색으로 쓰지 않는다** — 흰 배경 대비
    약 2.3:1로 WCAG AA(4.5:1) 미달. 배경·테두리·장식선 용도로만 절제해서 쓴다 (현재: 헤더 하단 경계선, 카드 상단 장식선).
  - `accent` (강조 #E8A33D = `accent-500`, 100/500/700): 흰 배경 글자 금지(2.16:1). 배지·하이라이트 배경 등에 쓰고
    그 위 글자는 `gray-900`. 쓸 곳이 없으면 억지로 넣지 않는다 (현재 사용처 없음).
  - `blue-*`, `purple-*` 등 **팔레트 이름을 브랜드 색 용도로 직접 쓰지 않는다** (브랜드 색이 바뀌어도 토큰만 고치면 되도록).
  - 새 글자/배경 조합을 만들면 WCAG AA 대비(본문 4.5:1)를 계산해 확인한다.
  - 중립·상태 색(`gray-*`, `red-*`)은 `@theme`에서 기존 hex(Tailwind v3 값)로 고정해 쓴다. 새 단계가 필요하면
    같은 방식으로 `@theme`에 hex를 추가한다 (v4 기본 팔레트는 oklch라 같은 이름도 색이 다름).
- 원본 디자인은 line-height를 지정하지 않았다(브라우저 기본 `normal`). 크기 클래스는 `text-sm/[normal]`처럼
  `normal` 키워드를 붙여 쓴다 (`leading-normal`·`/normal`은 1.5라 다르다).
- **지원 브라우저 하한**: Tailwind v4 요구사항에 따라 Safari 16.4+, Chrome 111+, Firefox 128+.
  이보다 오래된 브라우저에서는 스타일이 깨질 수 있다.
- 모바일 규칙 유지: 터치 영역 `min-h-11`(44px) 이상, 입력칸 글자 `text-base`(16px, iOS 확대 방지),
  키보드 포커스는 `focus-visible:` 링으로 표시.
