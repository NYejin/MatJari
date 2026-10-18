import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ui } from "../components/ui";

// 서비스 이용약관 (공개 라우트 /terms, 정적 페이지).
// 주의: 이 문장은 법률 자문이 아닌 초안이다. 공개 운영 전 전문가 검토가 필요하다.
// 운영자 정보와 시행일은 개인정보처리방침과 같은 빌드 환경변수(VITE_PRIVACY_*)를 쓰며, 비어 있으면 "설정 필요"로 강조한다.

const MISSING = "[운영자 정보 설정 필요]";
const MISSING_DATE = "[시행일 설정 필요]";

const operator = import.meta.env.VITE_PRIVACY_OPERATOR?.trim() || "";
const contactEmail = import.meta.env.VITE_PRIVACY_CONTACT_EMAIL?.trim() || "";
const effectiveDate = import.meta.env.VITE_PRIVACY_EFFECTIVE_DATE?.trim() || "";

const cls = {
  list: "list-disc space-y-1 pl-5",
  missing: "rounded bg-accent-100 px-1.5 py-0.5 font-semibold text-gray-900",
} as const;

/** n은 조 번호. suffix가 있으면 "제5조의2"처럼 가지 번호를 붙인다(다른 조 번호는 바꾸지 않는다) */
function Section({ n, suffix, title, children }: { n: number; suffix?: number; title: string; children: ReactNode }) {
  const id = `terms-s${n}${suffix ? `-${suffix}` : ""}`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h2 id={id} className={ui.sectionTitle}>
        제{n}조{suffix ? `의${suffix}` : ""} ({title})
      </h2>
      <div className={`flex flex-col gap-2 break-words ${ui.body}`}>{children}</div>
    </section>
  );
}

function Setting({ value, missing }: { value: string; missing: string }) {
  return value ? <>{value}</> : <strong className={cls.missing}>{missing}</strong>;
}

export default function TermsOfServicePage() {
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <article className={`${ui.card} flex flex-col gap-6`} aria-labelledby="terms-title">
          <header className="flex flex-col gap-2">
            <h1 id="terms-title" className={ui.pageTitle}>
              서비스 이용약관
            </h1>
            <p className={ui.body}>
              시행일: <Setting value={effectiveDate} missing={MISSING_DATE} />
            </p>
          </header>

          <Section n={1} title="목적">
            <p>
              이 약관은 운영자가 제공하는 맞자리 서비스(이하 "서비스")의 이용과 관련하여 운영자와 이용자 사이의 권리, 의무 및
              책임사항, 그 밖에 필요한 사항을 정함을 목적으로 합니다.
            </p>
          </Section>

          <Section n={2} title="정의">
            <ul className={cls.list}>
              <li>"서비스"란 같은 공연의 티켓을 가진 이용자끼리 좌석 교환을 위한 정보를 연결해 주는 맞자리의 웹 서비스를 말합니다.</li>
              <li>"이용자"란 이 약관에 따라 서비스를 이용하는 모든 사람을 말합니다.</li>
              <li>"회원"이란 가입하여 계정을 만든 이용자를 말합니다.</li>
              <li>"티켓 정보"란 회원이 서비스에 등록한 공연 회차와 좌석(구역, 열, 번) 정보를 말합니다.</li>
              <li>"교환 요청(희망 조건)"이란 회원이 자신의 티켓 정보와 함께 교환을 원하는 좌석 범위, 회차, 추가금 조건을 등록한 것을 말합니다.</li>
              <li>"매칭"이란 회원이 후보 목록에서 상대를 선택해 시작한 교환 진행 단위를 말합니다.</li>
            </ul>
          </Section>

          <Section n={3} title="약관의 게시, 효력 및 변경">
            <ul className={cls.list}>
              <li>운영자는 이 약관을 서비스 화면에 게시하며, 약관은 게시한 시행일부터 효력이 발생합니다.</li>
              <li>
                운영자가 약관을 변경하는 경우 시행일과 변경 내용을 밝혀 시행일 7일 전부터 서비스 화면에 공지합니다. 다만 이용자에게
                불리한 변경은 시행일 30일 전부터 공지합니다.
              </li>
              <li>
                공지한 시행일 이후에도 이용자가 서비스를 계속 이용하는 경우 변경된 약관에 동의한 것으로 봅니다. 변경된 약관에 동의하지
                않는 이용자는 회원 탈퇴를 할 수 있습니다.
              </li>
            </ul>
          </Section>

          <Section n={4} title="회원가입과 계정">
            <ul className={cls.list}>
              <li>만 14세 이상만 가입할 수 있습니다.</li>
              <li>가입은 이메일 인증번호를 통한 가입 또는 소셜 로그인(카카오·구글·네이버)으로 할 수 있습니다.</li>
              <li>회원은 가입할 때 정확한 정보를 제공해야 하며, 정보가 바뀌면 지체 없이 수정해야 합니다.</li>
              <li>회원은 자신의 계정과 비밀번호를 직접 관리할 책임이 있으며, 계정을 타인에게 양도하거나 대여할 수 없습니다.</li>
            </ul>
          </Section>

          <Section n={5} title="서비스의 내용">
            <p>운영자는 다음의 서비스를 제공합니다.</p>
            <ul className={cls.list}>
              <li>공연 정보 등록</li>
              <li>티켓 좌석 등록</li>
              <li>교환 희망 조건 등록</li>
              <li>서로의 조건이 맞는 후보 목록 제공</li>
              <li>매칭 상대와의 채팅</li>
              <li>예약 제안과 수락</li>
              <li>교환 수락</li>
              <li>알림</li>
              <li>교환 이력 조회</li>
              <li>차단·신고</li>
            </ul>
            <p>
              서비스는 조건이 맞는 후보를 보여 줄 뿐 점수화, 추천, 순위 매기기를 하지 않으며, 후보의 선택은 이용자가 직접 합니다.
            </p>
          </Section>

          <Section n={5} suffix={2} title="생성형 AI 기반 기능">
            <ul className={cls.list}>
              <li>
                서비스는 공연 등록 때 공개된 공연 상세 페이지의 본문과 이미지를 생성형 AI로 읽어 회차와 좌석 구역 정보를
                제안합니다.
              </li>
              <li>AI가 읽은 결과는 부정확할 수 있으며, 등록 전에 이용자가 확인하고 고쳐야 합니다.</li>
              <li>운영자는 AI가 제안한 정보의 정확성을 보증하지 않습니다.</li>
            </ul>
          </Section>

          <Section n={6} title="서비스의 성격과 책임 범위">
            <ul className={cls.list}>
              <li>
                서비스는 같은 공연 티켓 보유자 사이의 정보 연결만 제공하는 통신 중개 도구입니다. 운영자는 티켓의 진위, 좌석의 소유,
                상대방의 이행을 보증하지 않습니다.
              </li>
              <li>
                운영자는 결제와 대금 수수를 중개하지 않으며, 추가금은 서비스 밖에서 이용자끼리 정합니다.
              </li>
              <li>
                티켓의 실제 양도는 이용자가 해당 티켓 판매처의 규정에 따라 직접 하며, 판매처 규정을 위반하여 생기는 불이익은 이용자의
                책임입니다.
              </li>
              <li>상대방의 예매내역 확인은 이용자가 직접 합니다.</li>
            </ul>
          </Section>

          <Section n={7} title="이용자의 의무와 금지행위">
            <p>이용자는 다음 행위를 해서는 안 됩니다.</p>
            <ul className={cls.list}>
              <li>허위 정보 또는 타인의 정보를 등록하는 행위</li>
              <li>소유하지 않은 티켓을 등록하는 행위</li>
              <li>매크로 등 자동화 수단을 이용하는 행위</li>
              <li>다른 이용자를 괴롭히거나 욕설, 협박, 사기를 하는 행위</li>
              <li>관련 법령과 판매처 규정을 위반하는 거래, 부정 판매, 불법 프리미엄 거래를 하는 행위</li>
              <li>서비스 운영을 방해하거나 보안을 우회하는 행위</li>
              <li>타인의 개인정보를 수집하는 행위</li>
              <li>채팅에 예매번호 등 민감정보를 노출하는 행위</li>
            </ul>
          </Section>

          <Section n={8} title="채팅과 이용자 콘텐츠">
            <ul className={cls.list}>
              <li>이용자가 작성한 내용에 대한 책임은 작성자에게 있습니다.</li>
              <li>
                채팅 기록은 분쟁 확인과 신고 처리를 위해 보관하며, 이용자가 삭제하거나 수정할 수 없습니다.
              </li>
              <li>채팅에는 텍스트만 보낼 수 있으며 이미지와 파일은 첨부할 수 없습니다.</li>
            </ul>
          </Section>

          <Section n={9} title="차단, 신고 및 이용 정지">
            <ul className={cls.list}>
              <li>이용자가 상대를 차단하면 서로 후보 목록과 채팅에서 제외됩니다.</li>
              <li>신고는 운영자가 확인하고 처리하며, 신고만으로 자동 제재를 하지 않습니다.</li>
              <li>
                운영자는 이용자가 이 약관을 위반한 경우 기간을 정하여 서비스 이용을 정지할 수 있습니다. 이용 정지 중에도 회원 탈퇴는
                할 수 있습니다.
              </li>
            </ul>
          </Section>

          <Section n={10} title="서비스의 변경과 중단">
            <ul className={cls.list}>
              <li>운영자는 정당한 사유가 있는 경우 서비스의 전부 또는 일부를 변경하거나 중단할 수 있으며, 이 경우 사전에 공지합니다.</li>
              <li>불가피한 사유로 사전 공지가 어려운 경우에는 사후에 공지합니다.</li>
            </ul>
          </Section>

          <Section n={11} title="회원 탈퇴와 이용 종료">
            <ul className={cls.list}>
              <li>
                회원은 언제든지{" "}
                <Link to="/me" className={ui.link}>
                  마이페이지
                </Link>
                에서 탈퇴할 수 있습니다.
              </li>
              <li>
                탈퇴하면 진행 중인 교환은 자동으로 정리되고 활성 티켓은 내려가며, 채팅과 교환 이력 등은 익명화되어 보관됩니다.
              </li>
              <li>
                자세한 내용은{" "}
                <Link to="/privacy" className={ui.link}>
                  개인정보처리방침
                </Link>
                에 따릅니다.
              </li>
            </ul>
          </Section>

          <Section n={12} title="저작권과 외부 정보">
            <ul className={cls.list}>
              <li>서비스 화면과 소스에 대한 저작권은 운영자에게 있습니다.</li>
              <li>
                공연 정보와 포스터 이미지 등에 대한 권리는 각 권리자에게 있습니다. 포스터는 원 판매처에서 직접 불러와 표시하며
                서버에 저장하지 않습니다.
              </li>
            </ul>
          </Section>

          <Section n={13} title="책임의 제한">
            <p>
              운영자는 법령이 허용하는 범위에서 다음 사유로 발생한 손해에 대하여 책임을 지지 않습니다. 다만 운영자의 고의 또는
              중대한 과실로 발생한 손해는 제외합니다.
            </p>
            <ul className={cls.list}>
              <li>천재지변 등 불가항력</li>
              <li>이용자 사이의 거래와 분쟁</li>
              <li>이용자의 귀책 사유</li>
              <li>외부 판매처 정보의 오류</li>
            </ul>
          </Section>

          <Section n={14} title="개인정보 보호">
            <p>
              운영자는 이용자의 개인정보를{" "}
              <Link to="/privacy" className={ui.link}>
                개인정보처리방침
              </Link>
              에 따라 처리합니다.
            </p>
          </Section>

          <Section n={15} title="분쟁 해결과 관할">
            <p>
              이 약관과 서비스 이용에 관한 분쟁에는 대한민국 법을 적용하며, 분쟁에 관한 소송은 민사소송법에 따른 관할 법원에
              제기합니다.
            </p>
          </Section>

          <section aria-labelledby="terms-addendum" className="flex flex-col gap-2">
            <h2 id="terms-addendum" className={ui.sectionTitle}>
              부칙
            </h2>
            <div className={`flex flex-col gap-2 break-words ${ui.body}`}>
              <p>이 약관은 다음 시행일부터 적용합니다.</p>
              <ul className={cls.list}>
                <li>
                  시행일: <Setting value={effectiveDate} missing={MISSING_DATE} />
                </li>
                <li>
                  운영자: <Setting value={operator} missing={MISSING} />
                </li>
                <li>
                  문의 이메일:{" "}
                  {contactEmail ? (
                    <a href={`mailto:${contactEmail}`} className={`${ui.link} break-all`}>
                      {contactEmail}
                    </a>
                  ) : (
                    <strong className={cls.missing}>{MISSING}</strong>
                  )}
                </li>
              </ul>
            </div>
          </section>
        </article>
      </div>
    </div>
  );
}
