import { apiClient } from "./client";
import type { ListingImageRef } from "../types/listingImage";

// 티켓('내 자리')·교환 희망 조건('원하는 자리') 사진. 올리기·지우기는 소유자만, 보기는 후보·매칭 상대도 가능(서버 판정).
// 오류: 422 IMAGE_LIMIT_REACHED, 409 TICKET_NOT_ACTIVE|REQUEST_DELETED, 429 LISTING_IMAGE_RATE_LIMITED, 503 SERVER_BUSY, 400(형식·크기).
// 보기: 404=볼 수 없음(숨김), 410 IMAGE_EXPIRED=보관 기간 지남.

export interface ListingImageOwnerApi {
  upload(ownerId: number, image: Blob, signal?: AbortSignal): Promise<ListingImageRef>;
  remove(ownerId: number, imageId: number): Promise<void>;
}

function makeOwnerApi(base: "tickets" | "exchange/requests"): ListingImageOwnerApi {
  return {
    /** POST multipart `image` → 201 {id} */
    async upload(ownerId, image, signal) {
      const form = new FormData();
      form.append("image", image, "photo.jpg");
      const { data } = await apiClient.post<ListingImageRef>(`/${base}/${ownerId}/images`, form, {
        signal,
        headers: { "Content-Type": undefined }, // axios가 multipart 경계를 붙이게 한다
      });
      return data;
    },
    /** DELETE → 204 */
    async remove(ownerId, imageId) {
      await apiClient.delete(`/${base}/${ownerId}/images/${imageId}`);
    },
  };
}

export const ticketImagesApi = makeOwnerApi("tickets");
export const requestImagesApi = makeOwnerApi("exchange/requests");

export const listingImagesApi = {
  /** 인증이 필요해 <img src>로 못 쓰므로 blob으로 받는다. 보기용 URL은 호출한 쪽이 revoke한다 */
  async fetch(imageId: number, signal?: AbortSignal): Promise<Blob> {
    const { data } = await apiClient.get<Blob>(`/listing-images/${imageId}`, { responseType: "blob", signal });
    return data;
  },
};
