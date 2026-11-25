import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ui } from "../components/ui";

// 교환 안내 (공개 라우트 /guide, 정적 페이지). 규칙은 CLAUDE.md '제품·교환 규칙' 기준.
// 특정 판매처의 구체적 양도 정책은 확인된 사실이 아니므로 쓰지 않는다.
// 화면 캡처(public/guide/*.png)는 격리된 점검 스택에 넣은 예시 공연·예시 계정으로 찍었다(실제 이용자 정보 없음, 2배 해상도).

const cls = {
  list: "list-disc space-y-1.5 pl-5",
  steps: "flex flex-col gap-6",
  stepTitle: "text-base/[1.5] font-semibold text-gray-900",
  stepNo: "mr-1.5 inline-flex size-6 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white align-[1px]",
} as const;

const TOC: ReadonlyArray<readonly [string, string]> = [
  ["#flow", "교환 순서"],
  ["#verify", "상대 좌석 확인"],
  ["#transfer", "실제 양도"],
  ["#money", "추가금 주의"],
  ["#trouble", "문제가 생겼을 때"],
];

/** 형광펜: 꼭 기억할 문장 (accent-100 배경 위 gray-900 글자 8.23:1) */
function Hl({ children }: { children: ReactNode }) {
  return <mark className="rounded-sm bg-accent-100 px-0.5 font-semibold text-gray-900">{children}</mark>;
}

/** 강조 색 글자: 버튼 이름·핵심 용어 (primary-700 / 흰 배경 7.67:1) */
function Key({ children }: { children: ReactNode }) {
  return <strong className="font-semibold text-primary-700">{children}</strong>;
}

/** 경고: 되돌릴 수 없거나 손해가 생기는 행동 (red-700 / 흰 배경 6.47:1, 색 외에 밑줄로도 구분) */
function Warn({ children }: { children: ReactNode }) {
  return (
    <strong className="font-semibold text-red-700 underline decoration-red-600 decoration-2 underline-offset-[3px]">{children}</strong>
  );
}

/** 밑줄: 확인할 대상 */
function U({ children }: { children: ReactNode }) {
  return <span className="underline decoration-accent-500 decoration-2 underline-offset-4">{children}</span>;
}

/** 예시 화면 캡처. width/height는 원본(2배) 크기라 화면에는 max 이하로 줄어 보인다(레이아웃 흔들림 방지용 비율). */
function Shot({ src, alt, caption, w, h, max = 360 }: { src: string; alt: string; caption: ReactNode; w: number; h: number; max?: number }) {
  return (
    <figure className="flex w-full flex-col gap-1.5" style={{ maxWidth: max }}>
      <img
        src={src}
        alt={alt}
        width={w}
        height={h}
        loading="lazy"
        decoding="async"
        className="h-auto w-full rounded-xl border border-gray-200 bg-surface shadow-sm"
      />
      <figcaption className={ui.hint}>
        <span className="mr-1 rounded bg-gray-100 px-1.5 py-0.5 text-[0.6875rem] font-semibold text-gray-700">예시 화면</span>
        {caption}
      </figcaption>
    </figure>
  );
}

function Step({ no, title, children }: { no: number; title: ReactNode; children: ReactNode }) {
  return (
    <li className="flex flex-col gap-2">
      <h3 className={cls.stepTitle}>
        <span className={cls.stepNo} aria-hidden="true">
          {no}
        </span>
        <span className="sr-only">{no}단계. </span>
        {title}
      </h3>
      <div className="flex flex-col gap-2.5 sm:pl-7">{children}</div>
    </li>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="flex scroll-mt-4 flex-col gap-3">
      <h2 id={`${id}-title`} className={ui.sectionTitle}>
        {title}
      </h2>
      <div className={`flex flex-col gap-3 break-words ${ui.body}`}>{children}</div>
    </section>
  );
}

export default function GuidePage() {
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <article className={`${ui.card} flex flex-col gap-8`} aria-labelledby="guide-title">
          <header className="flex flex-col gap-2">
            <h1 id="guide-title" className={ui.pageTitle}>
              교환 안내
            </h1>
            <p className={ui.body}>
              맞자리에서 어떻게 자리를 바꾸는지, 어떤 점을 조심해야 하는지 화면과 함께 알려 드려요. 아래 화면은{" "}
              <strong>예시 공연·예시 계정</strong>으로 찍었어요.
            </p>
            <nav aria-label="안내 목차">
              <ul className="flex flex-wrap gap-x-4 gap-y-1">
                {TOC.map(([href, label]) => (
                  <li key={href}>
                    <a href={href} className={`${ui.link} inline-flex min-h-11 items-center`}>
                      {label}
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          </header>

          <Section id="flow" title="1. 맞자리로 교환하는 순서">
            <ol className={cls.steps}>
              <Step no={1} title="티켓 등록">
                <p>
                  [내 티켓] → <Key>[티켓 등록]</Key>에서 내 티켓의 <U>공연·회차·구역·열·번</U>을 입력해요. 등록한 티켓 카드에서
                  바로 교환 조건을 정할 수 있어요.
                </p>
                <Shot
                  src="/guide/step1-ticket.png"
                  w={840}
                  h={494}
                  alt="내 티켓 화면. 맞자리 페스티벌 2026(예시), A구역 3열 12번 티켓 카드에 교환 조건 수정, 후보 보기, 내리기 버튼이 있다."
                  caption="등록한 티켓 카드. 교환 조건을 정하면 [후보 보기]가 생겨요."
                />
              </Step>

              <Step no={2} title="교환 희망 조건 정하기">
                <p>
                  <Key>받고 싶은 자리</Key>를 구역 지도에서 눌러 고르고, <U>열·번 범위</U>와 <U>희망 회차</U>를 정해요. 범위는
                  여러 개 만들 수 있고, 회차는 우선순위를 정할 수 있어요.
                </p>
                <Shot
                  src="/guide/step2-condition.png"
                  w={840}
                  h={1845}
                  max={300}
                  alt="교환 조건 화면. 구역 지도에서 C구역이 선택되어 있고, 아래에 구역 C, 열 1~10, 번 1~20이 입력되어 있다."
                  caption="지도에서 C구역을 누르면 구역 칸이 채워져요. 열·번은 범위로 입력해요."
                />
                <p>
                  범위마다 <Key>추가금 조건</Key>을 골라요.{" "}
                  <Hl>‘받고 싶어요’끼리, ‘추가금 X’와 ‘받고 싶어요’는 서로 매칭되지 않아요.</Hl> 금액은 매칭 판정에 쓰이지 않고
                  상대에게 참고로만 보여요.
                </p>
                <Shot
                  src="/guide/step2-extra.png"
                  w={840}
                  h={562}
                  alt="범위 1의 추가금 선택. 추가금 X, 상관없음, 받고 싶어요, 낼 수 있어요 중 낼 수 있어요가 선택되고 최대 금액 20000원이 입력되어 있다."
                  caption="추가금 네 가지: 추가금 X · 상관없음 · 받고 싶어요 · 낼 수 있어요"
                />
              </Step>

              <Step no={3} title="후보 확인과 제안">
                <p>
                  <Hl>내 자리가 상대의 희망 조건에, 상대 자리가 내 희망 조건에 둘 다 들어맞는 사람</Hl>만 후보로 보여요. 맞자리는
                  점수를 매기거나 추천하지 않고, 누구와 이야기할지는 내가 골라요. 마음에 드는 후보에게 <Key>[제안하기]</Key>를
                  누르면 채팅이 열려요.
                </p>
                <Shot
                  src="/guide/step3-candidate.png"
                  w={840}
                  h={480}
                  alt="매칭 후보 카드. 서연, C구역 5열 8번, 2026년 12월 19일 오후 6시, 내 조건 낼 수 있어요 20,000원, 제안하기 버튼."
                  caption="후보 카드에는 상대 자리와 회차, 내 추가금 조건이 보여요."
                />
              </Step>

              <Step no={4} title="채팅하고 예약하기">
                <p>
                  채팅에서 예매내역을 서로 확인한 뒤(아래 2번), 한 사람이 <Key>[예약 제안]</Key>을 누르고 상대가{" "}
                  <Key>[예약 수락]</Key>을 누르면 예약이 돼요. 한 티켓으로 여러 사람과 동시에 채팅할 수 있지만,{" "}
                  <Hl>예약은 한 사람과만</Hl> 할 수 있어요.
                </p>
                <Shot
                  src="/guide/step4-reserve.png"
                  w={840}
                  h={400}
                  alt="채팅 화면 위쪽 거래 카드. 내 C구역 5열 8번에서 상대 A구역 3열 12번으로. 민지님이 예약을 제안했어요 안내와 예약 수락, 거절 버튼."
                  caption="상대가 예약을 제안하면 거래 카드에 [예약 수락]과 [거절]이 나와요."
                />
                <p>
                  예약되면 <Hl>두 티켓이 잠겨서 다른 사람의 새 제안·예약에 쓰이지 않아요.</Hl> 예약 중에는 채팅을 끝내기 전에
                  먼저 예약을 취소해야 해요.
                </p>
                <Shot
                  src="/guide/step4-chat.png"
                  w={840}
                  h={982}
                  alt="예약 중인 채팅 화면. 예약 중 배지, 두 티켓이 예약되어 다른 매칭에서는 쓸 수 없다는 안내, 예약 취소 버튼, 아래에 주고받은 메시지."
                  caption="예약 중인 채팅. 교환이 끝나면 [내 매칭 목록]에서 교환을 수락해요."
                />
              </Step>

              <Step no={5} title="판매처에서 실제 양도">
                <p>
                  <Warn>맞자리는 티켓을 옮겨 주지 않아요.</Warn> 티켓은 두 사람이 예매한 판매처에서 직접 넘겨요(아래 3번 참고).
                </p>
              </Step>

              <Step no={6} title="교환 수락 → 교환 완료">
                <p>
                  <Hl>판매처에서 양도를 마친 것을 확인한 뒤에</Hl> [내 매칭]에서 <Key>[교환 수락]</Key>을 눌러요. 두 사람이 모두
                  누르면 교환이 완료되고 <Warn>취소할 수 없어요.</Warn>
                </p>
                <Shot
                  src="/guide/step5-accept.png"
                  w={840}
                  h={1096}
                  alt="내 매칭 카드. 예약 중 배지, 내 자리 A구역 3열 12번, 상대 자리 C구역 5열 8번. 교환 수락 확인 안내와 교환 수락할게요, 돌아가기 버튼."
                  caption="[교환 수락]을 누르면 한 번 더 확인해요."
                />
                <p>
                  교환이 완료되면 <Key>받은 자리가 내 티켓으로 등록</Key>되고, 마이페이지의 [교환 이력]에 기록이 남아요.
                </p>
                <Shot
                  src="/guide/step6-history.png"
                  w={840}
                  h={584}
                  alt="교환 이력 카드. 교환 완료 배지, 기존 자리 A구역 3열 12번, 바꾼 자리 C구역 5열 8번, 채팅 기록 보기 링크."
                  caption="교환 이력: 기존 자리 → 바꾼 자리"
                />
              </Step>
            </ol>
          </Section>

          <Section id="verify" title="2. 상대 좌석 확인하기">
            <p>
              <Warn>맞자리는 상대가 정말 그 자리를 가졌는지 확인해 주지 못해요.</Warn> 채팅 입력창 왼쪽의 <Key>사진 버튼</Key>
              으로 예매내역을 보여 주세요.
            </p>
            <ul className={cls.list}>
              <li>
                보내기 전에 <Hl>이름·연락처·예매번호·결제 정보는 꼭 가려 주세요.</Hl> 사진 위를 손가락이나 마우스로 끌면 검은
                사각형으로 가려지고, <strong>가린 부분은 원본에 남지 않고</strong> 다시 만든 사진만 보내져요.
              </li>
              <li>
                <U>공연명·회차·구역·열·번</U>은 보이도록 해 주세요.
              </li>
              <li>
                상대가 보낸 사진에서도 <U>공연명·회차·구역·열·번</U>이 채팅의 자리와 맞는지 확인하세요.
              </li>
              <li>보낸 사진은 지울 수 없고, 채팅이 끝나고 30일 뒤 서버에서 삭제돼요.</li>
            </ul>
            <Shot
              src="/guide/verify-redact.png"
              w={840}
              h={1420}
              max={320}
              alt="사진 보내기 대화상자. 예시 예매 확인 화면에서 예매번호, 예매자, 연락처, 결제 줄이 검은 사각형으로 가려져 있고 공연명, 관람일시, 좌석은 보인다."
              caption="예매번호·예매자·연락처·결제를 가리고, 공연명·관람일시·좌석은 남긴 예시"
            />
          </Section>

          <Section id="transfer" title="3. 실제 양도는 판매처에서">
            <p>맞자리는 티켓을 대신 옮겨 주지 않아요. 실제 양도는 두 사람이 예매한 판매처에서 직접 해야 해요.</p>
            <ul className={cls.list}>
              <li>
                판매처마다 양도·선물하기 가능 여부와 규정이 달라요. <Hl>예매한 사이트의 규정을 먼저 확인하세요.</Hl>
              </li>
              <li>
                <Warn>티켓을 취소한 뒤 상대가 다시 예매하는 방식은 위험해요.</Warn> 그 사이에 다른 사람이 그 자리를 가져갈 수
                있어요.
              </li>
              <li>판매처 규정을 어겨 생기는 불이익은 이용자 본인이 책임져요.</li>
            </ul>
          </Section>

          <Section id="money" title="4. 추가금은 조심하세요">
            <p>
              <Key>맞자리는 결제를 중개하지 않아요.</Key> 추가금은 서비스 밖에서 두 사람이 직접 주고받아요.
            </p>
            <ul className={cls.list}>
              <li>
                <Warn>양도가 끝난 것을 확인하기 전에는 돈을 보내지 마세요.</Warn>
              </li>
              <li>
                송금하기 전에 <U>예금주 이름과 채팅 상대가 같은 사람인지</U> 확인하세요.
              </li>
              <li>
                <Hl>급하게 재촉하며 먼저 보내라고 하면 의심하세요.</Hl>
              </li>
            </ul>
            <p>
              사기 피해를 입었다면 경찰청 사이버범죄 신고시스템(ECRM)에 신고할 수 있어요.{" "}
              <a href="https://ecrm.police.go.kr" target="_blank" rel="noopener noreferrer" className={ui.link}>
                https://ecrm.police.go.kr
              </a>
            </p>
          </Section>

          <Section id="trouble" title="5. 문제가 생겼을 때">
            <ul className={cls.list}>
              <li>
                <Key>예약 취소</Key>: 교환이 완료되기 전에는 두 사람 중 누구나 예약을 취소할 수 있어요. 취소하면 채팅은 유지되고
                두 티켓을 다시 쓸 수 있어요.
              </li>
              <li>
                <Key>차단·신고</Key>: 채팅 화면 <U>오른쪽 위 ⋯ 메뉴</U>에서 상대를 차단하거나 신고할 수 있어요. 차단하면 서로
                후보에서 빠지고 진행 중인 채팅이 끝나요. 신고는 관리자가 확인해요.
              </li>
              <li>
                <Warn>교환 완료 뒤에는 되돌릴 수 없어요.</Warn> [교환 수락]은 판매처에서 양도를 마친 것을 확인한 뒤에 눌러 주세요.
              </li>
            </ul>
            <Shot
              src="/guide/trouble-menu.png"
              w={840}
              h={660}
              alt="채팅 화면의 더보기 메뉴. 신고하기, 차단하기, 닫기 버튼."
              caption="채팅 오른쪽 위 ⋯ 를 누르면 나오는 메뉴"
            />
          </Section>

          <p className={ui.hint}>
            이용 규정은{" "}
            <Link to="/terms" className={ui.link}>
              서비스 이용약관
            </Link>
            에서도 볼 수 있어요.
          </p>
        </article>
      </div>
    </div>
  );
}
