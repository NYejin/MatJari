---
title: 프런트 함정 (StrictMode 중단 요청 가드, 객체 키 __proto__)
type: gotcha
tags: [react, strictmode, 프런트, 보안, 소셜로그인]
sources: [../decisions/social-login-design.md, ../decisions/block-report-design.md]
updated: 2026-10-12
confidence: medium
status: draft
---

# 프런트 함정

React 프런트에서 두 번 겪은 함정이다. 코드를 읽으면 알 수 있는 구현은 쓰지 않고 재현 조건과 원칙만 둔다.

## 1. StrictMode와 중단된 요청이 풀지 못하는 가드

**증상**: 개발 모드에서만 화면이 계속 로딩에 머문다. 이전 사례는 신고 상세 화면(`AdminReportDetailPage`)의 채팅 근거였다(수정 커밋 `a455df1`, 관련 기능은 [차단·신고 설계](../decisions/block-report-design.md)).

**재현 조건**: 효과(effect)가 "이미 요청 중이면 건너뛴다"는 ref 가드로 중복 호출을 막는다. 개발 모드의 StrictMode는 마운트 -> 정리 -> 재마운트를 하는데, 정리 단계에서 요청을 abort하면 첫 요청이 중단되고 그 요청의 완료 처리(가드 해제·상태 갱신)가 실행되지 않거나 무시된다. 재마운트된 효과는 가드가 아직 잠겨 있어 요청을 보내지 않으므로 아무도 결과를 받지 못한다.

**원칙**:
- 정리 단계에서 가드를 함께 풀거나, 가드를 "요청 중인가"가 아니라 요청 단위(모듈 수준 in-flight Promise 공유)로 둔다.
- 중단(abort)된 요청은 실패로 취급해 가드를 반드시 해제한다.
- 1회용 자원(예: 일회용 로그인 코드)을 쓰는 호출은 중복 호출 자체가 실패이므로, 요청을 취소하지 말고 하나의 in-flight를 재마운트가 이어받게 한다.

**소셜 콜백 설계**: `/auth/callback`은 프래그먼트의 일회용 코드로 교환 API를 호출한다([소셜 로그인 설계](../decisions/social-login-design.md)). 코드가 1회용이라 StrictMode에서 두 번 호출하면 두 번째가 실패하므로, 교환을 한 번만 보내되 중단된 요청이 가드를 잠가 두지 않게 설계했다. 위 신고 상세 사례를 반복하지 않기 위한 것이다. Playwright로 브라우저에서 교환이 1회 일어남을 확인했다(구현 세션 기록).

## 2. 객체 키 조회의 `__proto__` 함정

**증상**: 외부에서 온 문자열(예: 로그인 화면 URL의 `?socialError=` 값)로 `messages[code]` 같은 객체 조회를 하면, 값이 `__proto__`·`constructor`·`toString` 등일 때 프로토타입의 속성이 반환된다. 문자열이 아닌 값을 기대하는 코드가 크래시한다. 소셜 로그인 검증에서 중간 등급으로 지적됐고 수정했다.

**원칙**:
- 외부 입력을 키로 객체를 조회하기 전에 `Object.prototype.hasOwnProperty.call(obj, key)`로 자기 속성인지 확인한다.
- 대안으로 `Map`, 또는 `Object.create(null)` 객체를 쓴다.
- 허용 코드 목록이 정해져 있으면 목록 포함 여부로 먼저 거른다.

관련 설계 맥락은 [소셜 로그인 설계 5절](../decisions/social-login-design.md).
