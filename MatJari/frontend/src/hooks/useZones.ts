import { useEffect, useState } from "react";
import { performancesApi } from "../api/performances";
import type { PerformanceZone, SeatmapImage, ZoneMapInfo } from "../types/performance";

export interface ZoneData {
  zones: PerformanceZone[];
  aspect: number | null;
  seatmapImage?: SeatmapImage | null;
  /** 예매처별 지도(대표 먼저). 구 응답이면 대표 지도 하나짜리 배열 */
  maps?: ZoneMapInfo[];
}

/**
 * 공연의 구역(이름·모양) 목록(GET /api/performances/{id}/zones). 화면(훅을 쓰는 컴포넌트)이 열려 있는 동안 공연마다 한 번만 부른다.
 * 목록이 비었거나 호출이 실패하면 null — 호출한 쪽은 지금처럼 텍스트 입력을 그대로 쓴다(서버는 목록 밖 구역도 받는다).
 */
export function useZones(performanceId: number | null): ZoneData | null {
  const [data, setData] = useState<ZoneData | null>(null);

  useEffect(() => {
    setData(null);
    if (performanceId === null) return;
    const controller = new AbortController();
    performancesApi
      .zones(performanceId, controller.signal)
      .then((res) => {
        const clean = (list: PerformanceZone[] | undefined) =>
          (list ?? []).filter((z) => typeof z.name === "string" && z.name.trim() !== "");
        const zones = clean(res.zones);
        const primary: ZoneMapInfo = {
          sourceId: null,
          site: "",
          siteName: "",
          primary: true,
          status: zones.length > 0 ? "READY" : "NONE",
          aspect: res.seatmapAspect ?? null,
          seatmapImage: res.seatmapImage ?? null,
          zones,
        };
        const maps = res.maps && res.maps.length > 0 ? res.maps.map((m) => ({ ...m, zones: clean(m.zones) })) : [primary];
        // 지도가 하나도 없고 읽는 중인 지도도 없으면 텍스트 입력 그대로
        const useful = maps.some((m) => m.zones.length > 0 || m.status === "PENDING");
        setData(useful ? { zones, aspect: res.seatmapAspect ?? null, seatmapImage: res.seatmapImage ?? null, maps } : null);
      })
      .catch(() => {
        // 실패는 조용히 텍스트 입력으로 되돌린다
      });
    return () => controller.abort();
  }, [performanceId]);

  return data;
}
