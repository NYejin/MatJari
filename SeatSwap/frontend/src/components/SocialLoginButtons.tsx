import { useEffect, useState } from "react";
import { fetchSocialProviders, socialAuthorizeUrl, type SocialProvider } from "../api/socialAuth";
import { FOCUS_RING } from "./ui";

// 로그인·회원가입 화면 하단의 소셜 버튼 구역. 활성 제공자가 없거나 조회에 실패하면 구역 자체를 숨긴다.
// 로고 이미지는 쓰지 않는 텍스트 버튼. 버튼은 페이지 이동 링크(<a>)다.

const BASE =
  "flex min-h-12 w-full touch-manipulation items-center justify-center rounded-[10px] px-4 text-base/[normal] " +
  "font-semibold no-underline active:opacity-80 " +
  FOCUS_RING;

const PROVIDERS: Record<SocialProvider, { label: string; className: string }> = {
  google: { label: "Google로 계속하기", className: `${BASE} border border-gray-300 bg-white text-gray-900` },
  naver: { label: "네이버로 계속하기", className: `${BASE} border border-[#03C75A] bg-[#03C75A] text-white` },
  kakao: { label: "카카오로 계속하기", className: `${BASE} border border-[#FEE500] bg-[#FEE500] text-black` },
};

export default function SocialLoginButtons() {
  const [providers, setProviders] = useState<SocialProvider[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetchSocialProviders()
      .then((list) => {
        if (!cancelled) setProviders(list);
      })
      .catch(() => {
        // 조회 실패 시 소셜 구역만 숨기고 이메일 로그인은 그대로 쓴다
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (providers.length === 0) return null;

  return (
    <div className="mt-6" role="group" aria-label="소셜 계정으로 계속하기">
      <div className="mb-4 flex items-center gap-3 text-sm/[normal] text-gray-500" aria-hidden="true">
        <span className="h-px flex-1 bg-gray-200" />
        <span>또는</span>
        <span className="h-px flex-1 bg-gray-200" />
      </div>
      <div className="flex flex-col gap-3">
        {providers.map((p) => (
          <a key={p} href={socialAuthorizeUrl(p)} className={PROVIDERS[p].className}>
            {PROVIDERS[p].label}
          </a>
        ))}
      </div>
    </div>
  );
}
