import { useEffect, useState } from "react";
import { performancesApi } from "../api/performances";

/**
 * 공연의 구역 이름 목록(GET /api/performances/{id}/zones). 화면(훅을 쓰는 컴포넌트)이 열려 있는 동안 공연마다 한 번만 부른다.
 * 목록이 비었거나 호출이 실패하면 null — 호출한 쪽은 지금처럼 텍스트 입력을 그대로 쓴다(서버는 목록 밖 구역도 받는다).
 */
export function useZoneNames(performanceId: number | null): string[] | null {
  const [names, setNames] = useState<string[] | null>(null);

  useEffect(() => {
    setNames(null);
    if (performanceId === null) return;
    const controller = new AbortController();
    performancesApi
      .zones(performanceId, controller.signal)
      .then((res) => {
        const list = (res.zones ?? []).map((z) => z.name).filter((n) => typeof n === "string" && n.trim() !== "");
        setNames(list.length > 0 ? Array.from(new Set(list)) : null);
      })
      .catch(() => {
        // 실패는 조용히 텍스트 입력으로 되돌린다
      });
    return () => controller.abort();
  }, [performanceId]);

  return names;
}
