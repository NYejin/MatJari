/** 백엔드 페이지 응답 공통 포맷. page는 0부터 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 커서(keyset) 조각 응답. 전체 개수가 없고, hasNext 가 true 면 nextCursor 를 다음 요청에 그대로 넣는다 */
export interface SliceResponse<T> {
  content: T[];
  size: number;
  hasNext: boolean;
  nextCursor: string | null;
}
