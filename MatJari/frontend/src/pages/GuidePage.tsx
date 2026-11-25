import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ui } from "../components/ui";

// 교환 안내 (공개 라우트 /guide, 정적 페이지). 규칙은 CLAUDE.md '제품·교환 규칙' 기준.
// 특정 판매처의 구체적 양도 정책은 확인된 사실이 아니므로 쓰지 않는다.
// 화면 캡처(public/guide/*.png)는 격리된 점검 스택에 넣은 예시 공연·예시 계정으로 찍었다(실제 이용자 정보 없음, 2배 해상도).
// 디자인: 단계마다 넓은 카드(설명 | 화면), 핵심은 본문 속 강조 대신 '포인트·주의' 상자로 모아 훑어보기 쉽게 한다.

type ShotInfo = { src: string; w: number; h: number; alt: string; caption: string; max?: number };

type StepInfo = {
  id: string;
  title: string;
  body: ReactNode;
  point?: ReactNode;
  danger?: ReactNode;
  shots: ShotInfo[];
};

/** 버튼 이름·핵심 용어 (primary-700 / 흰 배경 7.67:1) */
function Key({ children }: { children: ReactNode }) {
  return <strong className="font-semibold text-primary-700">{children}</strong>;
}

const STEPS: StepInfo[] = [
  {
    id: "step-ticket",
    title: "내 티켓 등록",
    body: (
      <>
        [내 티켓] → <Key>[티켓 등록]</Key>에서 공연·회차·구역·열·번을 입력해요. 등록한 티켓 카드에서 바로 교환 조건을 정할 수
        있어요.
      </>
    ),
    shots: [
      {
        src: "/guide/step1-ticket.png",
        w: 840,
        h: 494,
        alt: "내 티켓 화면. 맞자리 페스티벌 2026(예시), A구역 3열 12번 티켓 카드에 교환 조건 수정, 후보 보기, 내리기 버튼이 있다.",
        caption: "등록한 티켓 카드",
      },
    ],
  },
  {
    id: "step-condition",
    title: "받고 싶은 자리 정하기",
    body: (
      <>
        구역 지도에서 <Key>받고 싶은 구역</Key>을 누르고 열·번 범위와 희망 회차를 정해요. 범위는 여러 개 만들 수 있고, 범위마다
        추가금 조건(추가금 X · 상관없음 · 받고 싶어요 · 낼 수 있어요)을 골라요.
      </>
    ),
    point: <>‘받고 싶어요’끼리, ‘추가금 X’와 ‘받고 싶어요’는 서로 매칭되지 않아요. 금액은 상대에게 참고로만 보여요.</>,
    shots: [
      {
        src: "/guide/step2-condition.png",
        w: 840,
        h: 1845,
        max: 240,
        alt: "교환 조건 화면. 구역 지도에서 C구역이 선택되어 있고, 아래에 구역 C, 열 1~10, 번 1~20이 입력되어 있다.",
        caption: "지도에서 구역을 누르면 칸이 채워져요",
      },
      {
        src: "/guide/step2-extra.png",
        w: 840,
        h: 562,
        max: 240,
        alt: "범위 1의 추가금 선택. 낼 수 있어요가 선택되고 최대 금액 20000원이 입력되어 있다.",
        caption: "범위마다 추가금 조건",
      },
    ],
  },
  {
    id: "step-candidate",
    title: "후보 보고 제안하기",
    body: (
      <>
        서로의 조건이 맞는 사람만 후보로 보여요. 맞자리는 점수를 매기거나 추천하지 않아요. 이야기하고 싶은 후보에게{" "}
        <Key>[제안하기]</Key>를 누르면 채팅이 열려요.
      </>
    ),
    point: <>내 자리가 상대 조건에, 상대 자리가 내 조건에 둘 다 들어맞아야 후보가 돼요.</>,
    shots: [
      {
        src: "/guide/step3-candidate.png",
        w: 840,
        h: 480,
        alt: "매칭 후보 카드. 서연, C구역 5열 8번, 2026년 12월 19일 오후 6시, 내 조건 낼 수 있어요 20,000원, 제안하기 버튼.",
        caption: "후보 카드",
      },
    ],
  },
  {
    id: "step-reserve",
    title: "채팅하고 예약하기",
    body: (
      <>
        채팅에서 예매내역을 서로 확인한 뒤, 한 사람이 <Key>[예약 제안]</Key>을 누르고 상대가 <Key>[예약 수락]</Key>을 누르면
        예약이 돼요. 한 티켓으로 여러 사람과 채팅할 수 있지만 예약은 한 사람과만 할 수 있어요.
      </>
    ),
    point: <>예약되면 두 티켓이 잠겨 다른 사람의 새 제안·예약에 쓰이지 않아요. 교환 완료 전까지는 누구나 예약을 취소할 수 있어요.</>,
    shots: [
      {
        src: "/guide/step4-reserve.png",
        w: 840,
        h: 400,
        max: 300,
        alt: "채팅 화면 위쪽 거래 카드. 민지님이 예약을 제안했어요 안내와 예약 수락, 거절 버튼.",
        caption: "상대가 예약을 제안했을 때",
      },
      {
        src: "/guide/step4-chat.png",
        w: 840,
        h: 982,
        max: 300,
        alt: "예약 중인 채팅 화면. 예약 중 배지, 두 티켓이 예약되어 다른 매칭에서는 쓸 수 없다는 안내, 예약 취소 버튼, 주고받은 메시지.",
        caption: "예약 중인 채팅",
      },
    ],
  },
  {
    id: "step-transfer",
    title: "판매처에서 실제 양도",
    body: <>티켓은 두 사람이 예매한 판매처에서 직접 넘겨요. 판매처마다 양도·선물하기 규정이 달라요.</>,
    danger: <>맞자리는 티켓을 대신 옮겨 주지 않아요. 예매한 사이트의 양도 규정을 먼저 확인하세요.</>,
    shots: [],
  },
  {
    id: "step-complete",
    title: "교환 수락 → 교환 완료",
    body: (
      <>
        양도를 마쳤다면 [내 매칭]에서 <Key>[교환 수락]</Key>을 눌러요. 두 사람이 모두 누르면 교환이 완료되고, 받은 자리가 내
        티켓으로 등록되며 [교환 이력]에 남아요.
      </>
    ),
    danger: <>교환 완료는 되돌릴 수 없어요. 판매처에서 양도를 마친 것을 확인한 뒤에 눌러 주세요.</>,
    shots: [
      {
        src: "/guide/step5-accept.png",
        w: 840,
        h: 1096,
        max: 280,
        alt: "내 매칭 카드. 교환 수락 확인 안내와 교환 수락할게요, 돌아가기 버튼.",
        caption: "한 번 더 확인해요",
      },
      {
        src: "/guide/step6-history.png",
        w: 840,
        h: 584,
        max: 280,
        alt: "교환 이력 카드. 기존 자리 A구역 3열 12번, 바꾼 자리 C구역 5열 8번.",
        caption: "교환 이력",
      },
    ],
  },
];

const QUICK: ReadonlyArray<readonly [string, string, string]> = [
  ["#verify", "상대 좌석 확인", "예매내역 사진으로 확인하기"],
  ["#transfer", "실제 양도", "판매처에서 직접 넘겨요"],
  ["#money", "추가금 주의", "송금 전에 꼭 확인할 것"],
  ["#trouble", "문제가 생겼을 때", "예약 취소 · 차단 · 신고"],
];

/** 포인트 상자 (accent-100 배경 위 gray-900 8.23:1) */
function Point({ children }: { children: ReactNode }) {
  return (
    <div className="flex gap-3 rounded-2xl bg-accent-100 px-4 py-3.5">
      <span className="mt-0.5 inline-flex h-5 shrink-0 items-center rounded-full bg-white px-2 text-[0.6875rem] font-bold text-accent-700">
        포인트
      </span>
      <p className="text-[0.9375rem]/[1.65] font-medium text-gray-900">{children}</p>
    </div>
  );
}

/** 주의 상자: 되돌릴 수 없거나 손해가 생기는 일 (red-700 / red-50 6.1:1) */
function Danger({ children }: { children: ReactNode }) {
  return (
    <div className="flex gap-3 rounded-2xl bg-red-50 px-4 py-3.5 ring-1 ring-red-600/15">
      <span className="mt-0.5 inline-flex h-5 shrink-0 items-center rounded-full bg-red-600 px-2 text-[0.6875rem] font-bold text-white">
        주의
      </span>
      <p className="text-[0.9375rem]/[1.65] font-semibold text-red-700">{children}</p>
    </div>
  );
}

function Shot({ shot }: { shot: ShotInfo }) {
  return (
    <figure className="flex w-full flex-col items-center gap-2" style={{ maxWidth: shot.max ?? 320 }}>
      <img
        src={shot.src}
        alt={shot.alt}
        width={shot.w}
        height={shot.h}
        loading="lazy"
        decoding="async"
        className="h-auto w-full rounded-xl bg-white shadow-[0_8px_30px_rgba(17,24,39,0.12)] ring-1 ring-black/5"
      />
      <figcaption className="text-[0.8125rem] text-gray-500">{shot.caption}</figcaption>
    </figure>
  );
}

function Bullets({ items }: { items: ReactNode[] }) {
  return (
    <ul className="flex flex-col gap-3">
      {items.map((item, i) => (
        <li key={i} className="flex gap-3 text-[0.9375rem]/[1.7] text-gray-700">
          <span aria-hidden="true" className="mt-[0.6rem] size-1.5 shrink-0 rounded-full bg-primary-500" />
          <span>{item}</span>
        </li>
      ))}
    </ul>
  );
}

function SectionHead({ id, eyebrow, title }: { id: string; eyebrow: string; title: string }) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-semibold text-primary-700">{eyebrow}</span>
      <h2 id={id} className="text-2xl/[1.35] font-bold text-balance text-gray-900 sm:text-[1.75rem]">
        {title}
      </h2>
    </div>
  );
}

const CARD = "rounded-3xl bg-white p-6 shadow-[0_4px_24px_rgba(0,0,0,0.05)] sm:p-10";
const FRAME = "flex flex-wrap items-start justify-center gap-6 rounded-2xl bg-surface px-4 py-6 sm:px-8 sm:py-8";

function StepCard({ step, index }: { step: StepInfo; index: number }) {
  const flip = index % 2 === 1;
  const hasShots = step.shots.length > 0;
  return (
    <li id={step.id} className={`${CARD} scroll-mt-6`}>
      <div className={`grid items-center gap-8 md:gap-12 ${hasShots ? "md:grid-cols-2" : ""}`}>
        <div className={`flex flex-col gap-4 ${flip ? "md:order-2" : ""}`}>
          <span aria-hidden="true" className="text-5xl/none font-extrabold tracking-tight text-primary-200">
            {String(index + 1).padStart(2, "0")}
          </span>
          <h3 className="text-xl/[1.4] font-bold text-gray-900 sm:text-[1.375rem]">
            <span className="sr-only">{index + 1}단계. </span>
            {step.title}
          </h3>
          <p className="max-w-[560px] text-[0.9375rem]/[1.75] text-gray-700 sm:text-base/[1.75]">{step.body}</p>
          {step.point && <Point>{step.point}</Point>}
          {step.danger && <Danger>{step.danger}</Danger>}
        </div>
        {hasShots && (
          <div className={FRAME}>
            {step.shots.map((s) => (
              <Shot key={s.src} shot={s} />
            ))}
          </div>
        )}
      </div>
    </li>
  );
}

export default function GuidePage() {
  return (
    <div className={ui.page}>
      <div className="flex w-full max-w-[1040px] min-w-0 flex-col gap-14 pb-10 break-keep sm:gap-20">
        {/* 첫 화면 */}
        <header className="flex flex-col gap-6 rounded-3xl bg-gradient-to-br from-primary-100 via-primary-50 to-white px-6 py-10 sm:px-12 sm:py-16">
          <span className="text-sm font-semibold text-primary-700">교환 안내</span>
          <h1 className="text-3xl/[1.3] font-bold text-balance text-gray-900 sm:text-[2.5rem]/[1.25]">
            같은 공연, 더 좋은 자리로.
            <br />
            맞자리 교환은 이렇게 해요
          </h1>
          <p className="max-w-[560px] text-base/[1.75] text-gray-700">
            티켓을 등록하고 조건이 맞는 사람과 채팅으로 약속한 뒤, 판매처에서 직접 양도하면 끝이에요. 아래 화면은 예시 공연·예시
            계정으로 찍었어요.
          </p>
          <nav aria-label="교환 단계" className="pt-2">
            <ol className="flex flex-wrap gap-2">
              {STEPS.map((s, i) => (
                <li key={s.id}>
                  <a
                    href={`#${s.id}`}
                    className="inline-flex min-h-11 items-center gap-2 rounded-full bg-white px-4 text-sm font-medium text-gray-900 shadow-sm ring-1 ring-black/5 hover:ring-primary-300"
                  >
                    <span className="text-xs font-bold text-primary-700">{i + 1}</span>
                    {s.title}
                  </a>
                </li>
              ))}
            </ol>
          </nav>
        </header>

        {/* 교환 순서 */}
        <section aria-labelledby="flow-title" className="flex flex-col gap-8">
          <SectionHead id="flow-title" eyebrow="교환 순서" title="여섯 단계면 자리가 바뀌어요" />
          <ol className="flex flex-col gap-6 sm:gap-8">
            {STEPS.map((s, i) => (
              <StepCard key={s.id} step={s} index={i} />
            ))}
          </ol>
        </section>

        {/* 바로가기 */}
        <nav aria-label="더 알아두기" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {QUICK.map(([href, title, desc]) => (
            <a
              key={href}
              href={href}
              className="flex min-h-11 flex-col gap-1 rounded-2xl bg-white px-5 py-4 shadow-[0_4px_24px_rgba(0,0,0,0.05)] ring-1 ring-transparent hover:ring-primary-300"
            >
              <span className="font-semibold text-gray-900">{title}</span>
              <span className="text-[0.8125rem] text-gray-500">{desc}</span>
            </a>
          ))}
        </nav>

        {/* 상대 좌석 확인 */}
        <section id="verify" aria-labelledby="verify-title" className={`${CARD} scroll-mt-6`}>
          <div className="grid items-center gap-8 md:grid-cols-2 md:gap-12">
            <div className="flex flex-col gap-5">
              <SectionHead id="verify-title" eyebrow="안전하게" title="상대 좌석은 예매내역으로 확인해요" />
              <Danger>맞자리는 상대가 정말 그 자리를 가졌는지 확인해 주지 못해요.</Danger>
              <Bullets
                items={[
                  <>
                    채팅 입력창 왼쪽 <Key>사진 버튼</Key>으로 예매내역을 보내요.
                  </>,
                  <>
                    보내기 전에 <strong className="text-gray-900">이름·연락처·예매번호·결제 정보</strong>를 손가락이나 마우스로 끌어
                    가려요. 가린 부분은 원본에 남지 않아요.
                  </>,
                  <>공연명·회차·구역·열·번은 보이게 남겨요.</>,
                  <>상대가 보낸 사진의 자리가 채팅 속 자리와 같은지 확인해요.</>,
                  <>보낸 사진은 지울 수 없고, 채팅이 끝나고 30일 뒤 삭제돼요.</>,
                ]}
              />
            </div>
            <div className={FRAME}>
              <Shot
                shot={{
                  src: "/guide/verify-redact.png",
                  w: 840,
                  h: 1420,
                  max: 280,
                  alt: "사진 보내기 대화상자. 예시 예매 확인 화면에서 예매번호, 예매자, 연락처, 결제가 검게 가려지고 공연명, 관람일시, 좌석은 보인다.",
                  caption: "개인정보는 가리고, 자리 정보는 남기고",
                }}
              />
            </div>
          </div>
        </section>

        {/* 양도·추가금 */}
        <div className="grid gap-6 sm:gap-8 md:grid-cols-2">
          <section id="transfer" aria-labelledby="transfer-title" className={`${CARD} flex scroll-mt-6 flex-col gap-5`}>
            <SectionHead id="transfer-title" eyebrow="실제 양도" title="티켓은 판매처에서 넘겨요" />
            <Bullets
              items={[
                <>맞자리는 티켓을 대신 옮겨 주지 않아요. 두 사람이 예매한 판매처에서 직접 양도해요.</>,
                <>판매처마다 양도·선물하기 가능 여부와 규정이 달라요. 먼저 확인하세요.</>,
                <>판매처 규정을 어겨 생기는 불이익은 이용자 본인이 책임져요.</>,
              ]}
            />
            <Danger>티켓을 취소한 뒤 상대가 다시 예매하는 방식은 위험해요. 그 사이에 다른 사람이 자리를 가져갈 수 있어요.</Danger>
          </section>

          <section id="money" aria-labelledby="money-title" className={`${CARD} flex scroll-mt-6 flex-col gap-5`}>
            <SectionHead id="money-title" eyebrow="추가금" title="돈은 양도가 끝난 뒤에" />
            <Bullets
              items={[
                <>맞자리는 결제를 중개하지 않아요. 추가금은 두 사람이 직접 주고받아요.</>,
                <>송금하기 전에 예금주 이름과 채팅 상대가 같은 사람인지 확인하세요.</>,
                <>급하게 재촉하며 먼저 보내라고 하면 의심하세요.</>,
              ]}
            />
            <Danger>양도가 끝난 것을 확인하기 전에는 돈을 보내지 마세요.</Danger>
            <p className="text-[0.8125rem]/[1.6] text-gray-500">
              사기 피해는 경찰청 사이버범죄 신고시스템(ECRM)에 신고할 수 있어요.{" "}
              <a href="https://ecrm.police.go.kr" target="_blank" rel="noopener noreferrer" className={ui.link}>
                ecrm.police.go.kr
              </a>
            </p>
          </section>
        </div>

        {/* 문제가 생겼을 때 */}
        <section id="trouble" aria-labelledby="trouble-title" className={`${CARD} scroll-mt-6`}>
          <div className="grid items-center gap-8 md:grid-cols-2 md:gap-12">
            <div className="flex flex-col gap-5">
              <SectionHead id="trouble-title" eyebrow="도움말" title="문제가 생겼을 때" />
              <Bullets
                items={[
                  <>
                    <Key>예약 취소</Key>: 교환 완료 전에는 누구나 예약을 취소할 수 있어요. 채팅은 그대로 남아요.
                  </>,
                  <>
                    <Key>차단·신고</Key>: 채팅 화면 오른쪽 위 ⋯ 메뉴에서 할 수 있어요. 차단하면 서로 후보에서 빠지고 진행 중인 채팅이
                    끝나요. 신고는 관리자가 확인해요.
                  </>,
                ]}
              />
              <Danger>교환 완료 뒤에는 되돌릴 수 없어요.</Danger>
            </div>
            <div className={FRAME}>
              <Shot
                shot={{
                  src: "/guide/trouble-menu.png",
                  w: 840,
                  h: 660,
                  max: 300,
                  alt: "채팅 화면의 더보기 메뉴. 신고하기, 차단하기, 닫기 버튼.",
                  caption: "채팅 오른쪽 위 ⋯ 메뉴",
                }}
              />
            </div>
          </div>
        </section>

        <p className="text-center text-[0.8125rem] text-gray-500">
          이용 규정은{" "}
          <Link to="/terms" className={ui.link}>
            서비스 이용약관
          </Link>
          에서도 볼 수 있어요.
        </p>
      </div>
    </div>
  );
}
