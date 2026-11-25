// 닉네임 이니셜 원형 아바타. 색은 닉네임 해시로 고른다(같은 사람은 항상 같은 색).
// 프로필 이미지 기능은 없으므로 이니셜만 쓴다. 장식이라 보조기기에는 숨긴다(이름은 옆 글자가 읽어 준다).
// 글자색은 각 색의 700, 배경은 100 (amber는 gray-900) — 대비 4.5:1 이상.

const TONES = [
  "bg-primary-100 text-primary-700",
  "bg-sky-100 text-sky-700",
  "bg-mint-100 text-mint-700",
  "bg-coral-100 text-coral-700",
  "bg-accent-100 text-gray-900",
] as const;

function hash(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
  return h;
}

const SIZE = {
  sm: "size-9 text-sm",
  md: "size-11 text-base",
} as const;

export default function Avatar({ name, size = "md" }: { name: string; size?: keyof typeof SIZE }) {
  const initial = Array.from(name.trim())[0] ?? "?";
  return (
    <span
      aria-hidden="true"
      className={`inline-flex shrink-0 items-center justify-center rounded-full font-bold ${SIZE[size]} ${TONES[hash(name) % TONES.length]}`}
    >
      {initial}
    </span>
  );
}
