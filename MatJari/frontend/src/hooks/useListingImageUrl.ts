import { isAxiosError } from "axios";
import { useEffect, useState } from "react";
import { listingImagesApi } from "../api/listingImages";

// 티켓·희망 조건 사진을 인증 요청으로 받아 object URL로 돌려준다. 화면을 떠나면 URL을 해제한다.
export type ListingImageState = { kind: "loading" } | { kind: "ready"; url: string } | { kind: "hidden" } | { kind: "expired" } | { kind: "error" };

export function useListingImageUrl(imageId: number, retryKey = 0): ListingImageState {
  const [state, setState] = useState<ListingImageState>({ kind: "loading" });
  useEffect(() => {
    const controller = new AbortController();
    let url: string | null = null;
    setState({ kind: "loading" });
    listingImagesApi
      .fetch(imageId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setState({ kind: "ready", url });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        const status = isAxiosError(err) ? err.response?.status : undefined;
        setState(status === 404 ? { kind: "hidden" } : status === 410 ? { kind: "expired" } : { kind: "error" });
      });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [imageId, retryKey]);
  return state;
}
