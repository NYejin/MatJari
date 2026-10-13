import { useEffect, useState } from "react";

/** 값이 delayMs 동안 바뀌지 않으면 그 값을 돌려준다 (검색 입력 디바운스용) */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
