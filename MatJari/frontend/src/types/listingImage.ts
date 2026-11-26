// 티켓·교환 희망 조건 사진 (서버는 id만 내려 준다. 내용은 GET /api/listing-images/{id}로 인증 요청해 받는다)
export interface ListingImageRef {
  id: number;
}

/** 티켓·희망 조건마다 올릴 수 있는 최대 장수 */
export const LISTING_IMAGE_MAX = 3;
