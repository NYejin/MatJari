package com.matjari.dto.response;

/**
 * 티켓·교환 희망 조건에 붙은 사진 한 장의 참조(V33). id 로 {@code GET /api/listing-images/{id}} 를 불러온다(열람 권한은 그 요청에서 다시 판정한다).
 * 파일 이름·경로·올린 사람 정보는 담지 않는다.
 */
public record ListingImageRef(Long id) {
}
