import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ui } from "../components/ui";

// 교환 안내 (공개 라우트 /guide, 정적 페이지). 규칙은 CLAUDE.md '제품·교환 규칙' 기준.
// 특정 판매처의 구체적 양도 정책은 확인된 사실이 아니므로 쓰지 않는다.

const cls = {
  list: "list-disc space-y-1 pl-5",
  steps: "list-decimal space-y-1.5 pl-5",
} as const;

const TOC: ReadonlyArray<readonly [string, string]> = [
  ["#flow", "교환 순서"],
  ["#verify", "상대 좌석 확인"],
  ["#transfer", "실제 양도"],
  ["#money", "추가금 주의"],
  ["#trouble", "문제가 생겼을 때"],
];

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="flex scroll-mt-4 flex-col gap-2">
      <h2 id={`${id}-title`} className={ui.sectionTitle}>
        {title}
      </h2>
      <div className={`flex flex-col gap-2 break-words ${ui.body}`}>{children}</div>
    </section>
  );
}

export default function GuidePage() {
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <article className={`${ui.card} flex flex-col gap-6`} aria-labelledby="guide-title">
          <header className="flex flex-col gap-2">
            <h1 id="guide-title" className={ui.pageTitle}>
              교환 안내
            </h1>
            <p className={ui.body}>맞자리에서 어떻게 자리를 바꾸는지, 어떤 점을 조심해야 하는지 알려 드려요.</p>
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
              <li>
                <strong>티켓 등록</strong>: 내 티켓의 공연·회차·구역·열·번을 입력해요.
              </li>
              <li>
                <strong>교환 희망 조건</strong>: 받고 싶은 구역·열·번 범위와 회차를 고르고, 범위마다 추가금(없음·상관없음·받아야
                교환·낼 수 있음)을 정해요.
              </li>
              <li>
                <strong>후보 확인</strong>: 내 자리가 상대의 희망 조건에, 상대 자리가 내 희망 조건에 들어맞는 사람이 후보로 보여요.
                맞자리는 점수를 매기거나 추천하지 않고, 누구와 이야기할지는 내가 골라요.
              </li>
              <li>
                <strong>채팅</strong>: 후보에게 제안하면 채팅이 열려요. 한 티켓으로 여러 사람과 동시에 이야기할 수 있어요.
              </li>
              <li>
                <strong>예약 제안·수락</strong>: 한 사람이 예약을 제안하고 상대가 수락하면 두 티켓이 서로 잠겨요. 잠긴 동안 두
                티켓은 다른 사람의 새 제안·예약에 쓰이지 않아요.
              </li>
              <li>
                <strong>판매처에서 실제 양도</strong>: 티켓은 예매한 판매처에서 직접 넘겨요(아래 3번 참고).
              </li>
              <li>
                <strong>교환 수락</strong>: 양도를 마쳤다면 두 사람 모두 [교환 수락]을 눌러요.
              </li>
              <li>
                <strong>교환 완료</strong>: 두 사람이 모두 누르면 교환이 완료되고 새 자리가 내 티켓으로 등록돼요.
              </li>
            </ol>
          </Section>

          <Section id="verify" title="2. 상대 좌석 확인하기">
            <p>맞자리는 상대가 정말 그 자리를 가졌는지 확인해 주지 못해요. 채팅의 사진 보내기로 예매내역을 보여 주세요.</p>
            <ul className={cls.list}>
              <li>사진을 보내기 전에 이름·연락처·예매번호 일부·결제 정보를 꼭 가려 주세요.</li>
              <li>공연명·회차·구역·열·번은 보이도록 해 주세요.</li>
              <li>상대가 보낸 사진에서도 공연명·회차·구역·열·번이 맞는지 확인하세요.</li>
            </ul>
          </Section>

          <Section id="transfer" title="3. 실제 양도는 판매처에서">
            <p>맞자리는 티켓을 대신 옮겨 주지 않아요. 실제 양도는 두 사람이 예매한 판매처에서 직접 해야 해요.</p>
            <ul className={cls.list}>
              <li>판매처마다 양도·선물하기 가능 여부와 규정이 달라요. 예매한 사이트의 규정을 먼저 확인하세요.</li>
              <li>
                티켓을 취소한 뒤 상대가 다시 예매하는 방식은 위험해요. 그 사이에 다른 사람이 그 자리를 가져갈 수 있어요.
              </li>
              <li>판매처 규정을 어겨 생기는 불이익은 이용자 본인이 책임져요.</li>
            </ul>
          </Section>

          <Section id="money" title="4. 추가금은 조심하세요">
            <p>맞자리는 결제를 중개하지 않아요. 추가금은 서비스 밖에서 두 사람이 직접 주고받아요.</p>
            <ul className={cls.list}>
              <li>양도가 끝난 것을 확인하기 전에는 돈을 보내지 마세요.</li>
              <li>송금하기 전에 예금주 이름과 채팅 상대가 같은 사람인지 확인하세요.</li>
              <li>급하게 재촉하며 먼저 보내라고 하면 의심하세요.</li>
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
                <strong>예약 취소</strong>: 교환이 완료되기 전에는 두 사람 중 누구나 예약을 취소할 수 있어요. 취소하면 채팅이
                유지되고 두 티켓을 다시 쓸 수 있어요.
              </li>
              <li>
                <strong>차단·신고</strong>: 채팅 화면 오른쪽 위 ⋯ 메뉴에서 상대를 차단하거나 신고할 수 있어요. 신고는 관리자가
                확인해요.
              </li>
              <li>
                <strong>교환 완료 뒤</strong>에는 되돌릴 수 없어요. [교환 수락]은 판매처에서 양도를 마친 것을 확인한 뒤에 눌러
                주세요.
              </li>
            </ul>
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
