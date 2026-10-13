import { useCallback, useEffect, useRef, useState } from "react";
import { parseApiError } from "../api/errors";
import type { PageResponse } from "../types/page";

// "더 보기" 방식 페이지 목록 (후보·내 매칭). 첫 페이지는 resetKey가 바뀔 때마다 다시 불러온다.
// 늦게 도착한 이전 요청 응답은 세대 번호로 버리고, 언마운트·재조회 시 진행 중인 요청은 abort한다.

export type PagedState<T> =
  | { status: "loading" }
  | { status: "error"; error: unknown }
  | { status: "success"; items: T[]; page: number; totalPages: number; totalElements: number };

export function usePagedList<T>(
  fetchPage: (page: number, signal: AbortSignal) => Promise<PageResponse<T>>,
  resetKey: string,
  itemKey: (item: T) => number | string
) {
  const [state, setState] = useState<PagedState<T>>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const generation = useRef(0);
  const moreController = useRef<AbortController | null>(null);
  const fetchRef = useRef(fetchPage);
  const keyRef = useRef(itemKey);
  // 렌더 중이 아니라 effect에서 최신 함수를 보관한다 (아래 효과보다 먼저 선언해야 같은 커밋에서 먼저 실행된다)
  useEffect(() => {
    fetchRef.current = fetchPage;
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
      .current(0, controller.signal)
      .then((res) => {
        if (gen !== generation.current) return;
        setState({
          status: "success",
          items: res.content,
          page: res.page,
          totalPages: res.totalPages,
          totalElements: res.totalElements,
        });
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
    if (state.status !== "success" || loadingMore) return;
    const gen = generation.current;
    const controller = new AbortController();
    moreController.current = controller;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const res = await fetchRef.current(state.page + 1, controller.signal);
      if (gen !== generation.current) return;
      setState((prev) => {
        if (prev.status !== "success") return prev;
        const seen = new Set(prev.items.map((i) => keyRef.current(i)));
        return {
          status: "success",
          // 페이지 사이에 항목이 밀려도 중복 없이
          items: [...prev.items, ...res.content.filter((i) => !seen.has(keyRef.current(i)))],
          page: res.page,
          totalPages: res.totalPages,
          totalElements: res.totalElements,
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

  /** 첫 페이지부터 다시 불러온다 */
  const reload = useCallback(() => setRetryKey((k) => k + 1), []);

  return { state, loadingMore, moreError, loadMore, reload };
}
