package com.matjari.dto.response;

/** 연결 확정 응답. provider 는 경로값(google/kakao/naver). */
public record SocialConfirmResponse(String provider) {
}
