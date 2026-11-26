import { useEffect, useRef, useState } from "react";
import { kstEpoch } from "../api/dateTime";
import { performancesApi } from "../api/performances";
import { parsePosterUrl } from "./PosterImage";

// 공연 목록 카드의 썸네일. 카드가 화면에 들어올 때만 포스터 주소를 물어(서버 분당 제한·사이트 부담을 줄이려고)
// 원 사이트 이미지를 인라인 링크로 보여 준다(서버는 이미지를 저장·전달하지 않는다, 포스터 표시 규칙과 같음).
// 포스터가 없거나 못 불러오면 다음 회차 날짜를 크게 보여 준다.

/** 같은 화면에서 다시 그려도 다시 묻지 않게 공연 id 별 결과를 기억한다(null = 포스터 없음) */
const cache = new Map<number, string | null>();

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** KST 기준 "11.28" · "토" */
function kstDay(iso: string): { date: string; weekday: string } | null {
  const t = kstEpoch(iso);
  if (t === null) return null;
  const d = new Date(t + 9 * 3600_000);
  return { date: `${d.getUTCMonth() + 1}.${String(d.getUTCDate()).padStart(2, "0")}`, weekday: WEEKDAYS[d.getUTCDay()] };
}

interface Props {
  performanceId: number;
  title: string;
  nextSessionStartsAt: string | null;
}

export default function PerformanceThumb({ performanceId, title, nextSessionStartsAt }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const [url, setUrl] = useState<string | null | undefined>(cache.has(performanceId) ? cache.get(performanceId) : undefined);
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (url !== undefined) return;
    const el = ref.current;
    if (!el) return;
    const controller = new AbortController();
    let started = false;
    const load = () => {
      if (started) return;
      started = true;
      performancesApi
        .poster(performanceId, controller.signal)
        .then((u) => {
          cache.set(performanceId, u);
          setUrl(u);
        })
        .catch(() => {
          if (!controller.signal.aborted) setUrl(null); // 실패는 다음 방문 때 다시 묻도록 기억하지 않는다
        });
    };
    if (typeof IntersectionObserver === "undefined") {
      load();
      return () => controller.abort();
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          io.disconnect();
          load();
        }
      },
      { rootMargin: "200px" },
    );
    io.observe(el);
    return () => {
      io.disconnect();
      controller.abort();
    };
  }, [performanceId, url]);

  const poster = failed ? null : parsePosterUrl(url);
  const day = nextSessionStartsAt ? kstDay(nextSessionStartsAt) : null;

  return (
    <span
      ref={ref}
      className="relative flex aspect-[16/9] items-center justify-center overflow-hidden rounded-2xl bg-gradient-to-br from-primary-100 via-sky-100 to-coral-100"
    >
      {poster ? (
        <>
          {/* 같은 포스터를 흐리게 깔아 빈 양옆을 채우고, 가운데에 포스터 전체를 보인다 */}
          <img
            src={poster.href}
            alt=""
            aria-hidden="true"
            referrerPolicy="no-referrer"
            loading="lazy"
            decoding="async"
            className={`absolute inset-0 h-full w-full scale-110 object-cover blur-xl transition-opacity ${loaded ? "opacity-60" : "opacity-0"}`}
          />
          <img
            src={poster.href}
            alt={`${title} 포스터`}
            referrerPolicy="no-referrer"
            loading="lazy"
            decoding="async"
            className={`relative h-full w-auto object-contain shadow-md transition-opacity ${loaded ? "opacity-100" : "opacity-0"}`}
            onLoad={() => setLoaded(true)}
            onError={() => setFailed(true)}
          />
          <span className="absolute right-2 bottom-1.5 rounded-full bg-black/45 px-2 py-0.5 text-[0.6875rem]/[normal] text-white">
            출처 {poster.source} · 저작권은 원 권리자에게
          </span>
        </>
      ) : day ? (
        <span className="flex flex-col items-center gap-1 text-primary-700" aria-hidden="true">
          <span className="text-4xl/[1] font-bold tracking-tight">{day.date}</span>
          <span className="text-sm/[normal] font-semibold text-primary-600">{day.weekday}요일 공연</span>
        </span>
      ) : (
        <span className="text-4xl font-bold text-primary-700" aria-hidden="true">
          ♪
        </span>
      )}
    </span>
  );
}
