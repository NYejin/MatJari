import { useCallback, useEffect, useRef, useState } from "react";
import { parseApiError } from "../api/errors";
import type { SliceResponse } from "../types/page";

// 커서(keyset) 방식 "더 보기" 목록 (매칭 후보). usePagedList와 같은 규칙(세대 번호로 늦은 응답 폐기, 언마운트·재조회 시 abort)이고
// 페이지 번호 대신 서버가 준 nextCursor로 이어 읽는다. 전체 개수는 없다.

export type CursorState<T> =
  | { status: "loading" }
  | { status: "error"; error: unknown }
  | { status: "success"; items: T[]; hasNext: boolean; nextCursor: string | null };

export function useCursorList<T>(
  fetchSlice: (cursor: string | null, signal: AbortSignal) => Promise<SliceResponse<T>>,
  resetKey: string,
  itemKey: (item: T) => number | string
) {
  const [state, setState] = useState<CursorState<T>>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const generation = useRef(0);
  const moreController = useRef<AbortController | null>(null);
  const fetchRef = useRef(fetchSlice);
  const keyRef = useRef(itemKey);
  useEffect(() => {
    fetchRef.current = fetchSlice;
    keyRef.current = itemKey;
  });

  useEffect(() => {
    const gen = ++generation.current;
    const controller = new AbortController();
    moreController.current?.abort();
    setState({ status: "loading" });
    setMoreError(null);
    setLoadingMore(false);
    fetchRef
      .current(null, controller.signal)
      .then((res) => {
        if (gen !== generation.current) return;
        setState({ status: "success", items: res.content, hasNext: res.hasNext, nextCursor: res.nextCursor });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || gen !== generation.current) return;
        setState({ status: "error", error: err });
      });
    return () => {
      controller.abort();
      moreController.current?.abort();
    };
  }, [resetKey, retryKey]);

  const loadMore = useCallback(async () => {
    if (state.status !== "success" || !state.hasNext || !state.nextCursor || loadingMore) return;
    const gen = generation.current;
    const controller = new AbortController();
    moreController.current = controller;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const res = await fetchRef.current(state.nextCursor, controller.signal);
      if (gen !== generation.current) return;
      setState((prev) => {
        if (prev.status !== "success") return prev;
        const seen = new Set(prev.items.map((i) => keyRef.current(i)));
        return {
          status: "success",
          items: [...prev.items, ...res.content.filter((i) => !seen.has(keyRef.current(i)))],
          hasNext: res.hasNext,
          nextCursor: res.nextCursor,
        };
      });
    } catch (err) {
      if (!controller.signal.aborted && gen === generation.current) {
        setMoreError(parseApiError(err, "더 불러오지 못했습니다.").message);
      }
    } finally {
      if (!controller.signal.aborted && gen === generation.current) setLoadingMore(false);
    }
  }, [state, loadingMore]);

  /** 처음부터 다시 불러온다 */
  const reload = useCallback(() => setRetryKey((k) => k + 1), []);

  return { state, loadingMore, moreError, loadMore, reload };
}
