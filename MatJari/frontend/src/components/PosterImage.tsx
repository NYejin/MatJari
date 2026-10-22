import { useState } from "react";

// 공연 포스터. 서버는 이미지를 저장·전달하지 않고 주소 문자열만 알려 주며, 브라우저가 원 사이트에서 직접 불러온다.
// 주소가 허용 호스트의 https 이미지가 아니거나 불러오지 못하면(핫링크 차단 포함) 포스터 영역 전체를 숨긴다 — 대체 링크는 두지 않는다.

/** 백엔드 LinkInfoParsers.POSTER_HOSTS 와 같은 목록 (방어용 이중 확인) */
const POSTER_SOURCES: Record<string, string> = {
  "tkfile.yes24.com": "YES24",
  "ticketimage.interpark.com": "인터파크(NOL)",
  "image.toast.com": "티켓링크",
  "cdnticket.melon.co.kr": "멜론티켓",
};

/** 허용 호스트의 https 주소면 { href, source }, 아니면 null */
export function parsePosterUrl(
  url: string | null | undefined,
): { href: string; source: string } | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    const source = POSTER_SOURCES[parsed.hostname.toLowerCase()];
    if (parsed.protocol !== "https:" || !source) return null;
    return { href: parsed.href, source };
  } catch {
    return null;
  }
}

interface Props {
  url: string | null | undefined;
  /** 대체 텍스트로 쓰는 공연 제목 */
  title: string;
  className?: string;
}

export default function PosterImage({ url, title, className = "" }: Props) {
  const poster = parsePosterUrl(url);
  // 실패한 주소를 기억해 같은 주소로는 다시 그리지 않는다(주소가 바뀌면 다시 시도)
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  if (!poster || failedUrl === poster.href) return null;

  return (
    <figure
      className={`m-0 flex w-36 shrink-0 flex-col gap-1.5 md:w-44 ${className}`}
    >
      <div className="aspect-[3/4] w-full overflow-hidden rounded-lg border border-gray-200 bg-gray-100">
        <img
          key={poster.href}
          src={poster.href}
          alt={`${title} 포스터`}
          referrerPolicy="no-referrer"
          loading="lazy"
          decoding="async"
          className={`h-full w-full object-contain transition-opacity ${loaded ? "opacity-100" : "opacity-0"}`}
          onLoad={() => setLoaded(true)}
          onError={() => setFailedUrl(poster.href)}
        />
      </div>
      <figcaption className="text-xs/[normal] text-gray-600">
        포스터 이미지 출처: {poster.source} · 저작권은 원 권리자에게 있어요
      </figcaption>
    </figure>
  );
}
