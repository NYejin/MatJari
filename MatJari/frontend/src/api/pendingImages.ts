// 등록 폼에서 가리기까지 마친 사진을 대기 목록에 두었다가, 티켓·요청이 만들어진 뒤 순서대로 올리는 순수 로직.

export interface PendingUploadResult<T> {
  /** 올리기에 성공한 결과(순서 유지) */
  uploaded: T[];
  /** 올리지 못한 장수 */
  failed: number;
  /** 첫 실패 오류(문구 선택용). 없으면 null */
  firstError: unknown;
}

/** 대기 사진을 순서대로 하나씩 올린다. 한 장이 실패해도 나머지는 계속 시도한다(부분 실패 허용). */
export async function uploadPending<B, T>(items: readonly B[], upload: (item: B) => Promise<T>): Promise<PendingUploadResult<T>> {
  const uploaded: T[] = [];
  let failed = 0;
  let firstError: unknown = null;
  for (const item of items) {
    try {
      uploaded.push(await upload(item));
    } catch (err) {
      failed += 1;
      if (firstError === null) firstError = err;
    }
  }
  return { uploaded, failed, firstError };
}

/** 부분 실패 안내 문구. 실패가 없으면 null */
export function partialFailureNotice(failed: number, where: string): string | null {
  return failed > 0 ? `사진 ${failed}장을 올리지 못했어요. ${where}에서 다시 올릴 수 있어요.` : null;
}
