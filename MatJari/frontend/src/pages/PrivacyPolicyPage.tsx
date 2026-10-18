import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ui } from "../components/ui";

// 개인정보처리방침 (공개 라우트 /privacy, 정적 페이지). 항목·보유 기간은 실제 구현 기준으로만 적는다.
// 운영자 정보와 시행일은 빌드 환경변수(VITE_PRIVACY_*)로 받고, 비어 있으면 눈에 띄게 "설정 필요"로 보여준다.

const MISSING = "[운영자 정보 설정 필요]";
const MISSING_DATE = "[시행일 설정 필요]";

const operator = import.meta.env.VITE_PRIVACY_OPERATOR?.trim() || "";
const contactEmail = import.meta.env.VITE_PRIVACY_CONTACT_EMAIL?.trim() || "";
const effectiveDate = import.meta.env.VITE_PRIVACY_EFFECTIVE_DATE?.trim() || "";

const cls = {
  list: "list-disc space-y-1 pl-5",
  th: "border border-gray-200 bg-gray-100 px-3 py-2 text-left text-sm/[normal] font-semibold text-gray-900",
  td: "border border-gray-200 px-3 py-2 align-top text-sm/[normal] text-gray-700",
  missing: "rounded bg-accent-100 px-1.5 py-0.5 font-semibold text-gray-900",
} as const;

function Section({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  const id = `privacy-s${n}`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h2 id={id} className={ui.sectionTitle}>
        {n}. {title}
      </h2>
      <div className={`flex flex-col gap-2 ${ui.body}`}>{children}</div>
    </section>
  );
}

function Setting({ value, missing }: { value: string; missing: string }) {
  return value ? <>{value}</> : <strong className={cls.missing}>{missing}</strong>;
}

export default function PrivacyPolicyPage() {
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <article className={`${ui.card} flex flex-col gap-6`} aria-labelledby="privacy-title">
          <header className="flex flex-col gap-2">
            <h1 id="privacy-title" className={ui.pageTitle}>
              개인정보처리방침
            </h1>
            <p className={ui.body}>
              맞자리(이하 "서비스")은 이용자의 개인정보를 소중히 다루며, 아래와 같이 처리합니다. 시행일:{" "}
              <Setting value={effectiveDate} missing={MISSING_DATE} />
            </p>
          </header>

          <Section n={1} title="개인정보의 처리 목적">
            <ul className={cls.list}>
              <li>회원 가입과 본인 확인, 로그인 유지, 계정 관리</li>
              <li>같은 공연 티켓 보유자끼리 좌석을 교환할 수 있도록 하는 서비스 제공(티켓 등록, 교환 조건, 매칭, 채팅, 알림)</li>
              <li>분쟁 대응과 부정 이용 방지(차단, 신고 처리, 이용 정지)</li>
              <li>좌석 소유 인증 요청의 확인</li>
            </ul>
          </Section>

          <Section n={2} title="처리하는 개인정보 항목">
            <ul className={cls.list}>
              <li>
                <strong>회원 가입 시(필수):</strong> 이메일, 비밀번호(암호화된 해시로만 저장), 닉네임
              </li>
              <li>
                <strong>이메일 인증 시:</strong> 가입 전에 이메일 주인임을 확인하려고 인증번호를 메일로 보냅니다. 인증번호는 해시로만
                저장하고 발송·만료 시각과 입력 횟수를 함께 기록합니다.
              </li>
              <li>
                <strong>소셜 로그인 시:</strong> 제공자 이름(카카오·구글·네이버)과 제공자 고유 식별자만 저장합니다. 이메일과 프로필
                이미지는 저장하지 않지만, 제공자의 프로필 닉네임을 초기 닉네임으로 사용합니다(마이페이지에서 바꿀 수 있습니다). 다만 제공자가 검증된 이메일을 주면 가입 이메일로 사용합니다. 검증된 이메일이 없으면 서비스가
                만든 자리표시 이메일을 사용합니다.
              </li>
              <li>
                <strong>서비스 이용 중 생성되는 정보:</strong> 티켓, 교환 요청(희망 조건), 매칭, 채팅 메시지, 교환 이력, 차단·신고·제재
                기록
              </li>
              <li>
                <strong>좌석 인증 요청 시:</strong> 예매내역 사진(처리가 끝나면 삭제합니다)
              </li>
            </ul>
          </Section>

          <Section n={3} title="만 14세 미만 아동의 개인정보">
            <p>만 14세 미만은 가입할 수 없습니다.</p>
          </Section>

          <Section n={4} title="개인정보의 처리 및 보유 기간">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[480px] border-collapse">
                <caption className="sr-only">항목별 보유 기간</caption>
                <thead>
                  <tr>
                    <th scope="col" className={cls.th}>
                      항목
                    </th>
                    <th scope="col" className={cls.th}>
                      보유 기간
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <th scope="row" className={`${cls.td} text-left font-semibold`}>
                      계정 정보(이메일, 닉네임, 비밀번호 해시, 소셜 연결 정보)
                    </th>
                    <td className={cls.td}>탈퇴 즉시 삭제하거나 익명화합니다.</td>
                  </tr>
                  <tr>
                    <th scope="row" className={`${cls.td} text-left font-semibold`}>
                      이메일 인증번호 기록(해시, 발송·만료 시각, 입력 횟수)
                    </th>
                    <td className={cls.td}>
                      가입이 끝나면 즉시 삭제합니다. 가입하지 않고 만료된 기록은 이후 서비스가 정리합니다.
                    </td>
                  </tr>
                  <tr>
                    <th scope="row" className={`${cls.td} text-left font-semibold`}>
                      좌석 인증 요청의 예매내역 사진
                    </th>
                    <td className={cls.td}>요청 처리(승인·거절)가 끝나면 즉시 삭제합니다.</td>
                  </tr>
                  <tr>
                    <th scope="row" className={`${cls.td} text-left font-semibold`}>
                      받은 알림
                    </th>
                    <td className={cls.td}>직접 삭제하기 전까지, 탈퇴하면 삭제합니다.</td>
                  </tr>
                  <tr>
                    <th scope="row" className={`${cls.td} text-left font-semibold`}>
                      채팅 메시지, 교환 이력, 차단·신고·제재 기록
                    </th>
                    <td className={cls.td}>
                      탈퇴 후에는 익명화하여, 분쟁 대응과 부정 이용 방지를 위해 서비스 운영 기간 동안 보관하고 서비스 종료 시
                      파기합니다.
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </Section>

          <Section n={5} title="개인정보의 파기 절차와 방법">
            <ul className={cls.list}>
              <li>회원이 탈퇴하면 계정 정보를 삭제하거나 익명화하고, 소셜 연결 정보와 받은 알림을 삭제합니다.</li>
              <li>예매내역 사진은 요청 처리가 끝나면 서버에서 삭제합니다.</li>
            </ul>
          </Section>

          <Section n={6} title="개인정보의 제3자 제공">
            <p>서비스는 개인정보를 제3자에게 제공하지 않습니다.</p>
          </Section>

          <Section n={7} title="개인정보 처리의 위탁">
            <p>
              서비스는 개인정보 처리를 외부에 위탁하지 않습니다. 다만 이메일 인증번호는 운영자가 설정한 메일 발송 서비스(SMTP)를 통해
              보내므로, 인증번호를 받을 때 입력한 이메일 주소가 그 서비스를 거칩니다.
            </p>
          </Section>

          <Section n={8} title="정보주체의 권리와 행사 방법">
            <ul className={cls.list}>
              <li>
                <Link to="/me" className={ui.link}>
                  마이페이지
                </Link>
                에서 내 정보를 열람하고, 닉네임을 수정하고, 소셜 연결을 해제하고, 회원탈퇴를 할 수 있습니다.
              </li>
              <li>비밀번호로 가입한 계정은 탈퇴할 때 비밀번호로, 소셜 가입 계정은 확인 문구 입력으로 본인을 확인합니다.</li>
              <li>
                탈퇴하면 진행 중인 교환(예약 포함)은 모두 취소되고 등록한 티켓은 내려갑니다. 되돌릴 수 없습니다.
              </li>
              <li>
                이용 정지 중에는 서비스에 로그인할 수 없어 마이페이지에서 탈퇴할 수 없습니다. 이 경우 아래 문의처로 요청해 주시기 바랍니다.
              </li>
              <li>
                다른 이용자의 부정 이용은 채팅 화면의 신고 기능으로 알릴 수 있습니다. 그 밖의 문의는 아래 문의처로 연락해 주시기 바랍니다.
              </li>
            </ul>
          </Section>

          <Section n={9} title="개인정보 자동 수집 장치(쿠키 등)">
            <ul className={cls.list}>
              <li>
                소셜 로그인 과정에서만 한시적인 쿠키(<code>ss_oauth</code>, 10분 이내)를 사용하며, 로그인 절차가 끝나면
                삭제합니다.
              </li>
              <li>로그인 토큰은 쿠키가 아니라 이용자 브라우저의 localStorage에 저장됩니다. 로그아웃하면 삭제됩니다.</li>
            </ul>
          </Section>

          <Section n={10} title="개인정보 보호책임자 및 문의처">
            <ul className={cls.list}>
              <li>
                운영자: <Setting value={operator} missing={MISSING} />
              </li>
              <li>
                문의 이메일:{" "}
                {contactEmail ? (
                  <a href={`mailto:${contactEmail}`} className={ui.link}>
                    {contactEmail}
                  </a>
                ) : (
                  <strong className={cls.missing}>{MISSING}</strong>
                )}
              </li>
            </ul>
          </Section>

          <Section n={11} title="개인정보의 안전성 확보 조치">
            <ul className={cls.list}>
              <li>비밀번호는 복호화할 수 없는 BCrypt 해시로만 저장합니다.</li>
              <li>예매내역 사진은 처리 후 삭제하며, 본인과 관리자만 볼 수 있습니다.</li>
              <li>채팅 내용은 신고 처리에 필요할 때 관리자만 열람하고, 처리 목적 외에는 사용하지 않습니다.</li>
            </ul>
          </Section>

          <Section n={12} title="방침의 변경 및 시행일">
            <p>
              이 방침이 바뀌면 이 페이지에 변경 내용을 알립니다. 시행일: <Setting value={effectiveDate} missing={MISSING_DATE} />
            </p>
          </Section>
        </article>
      </div>
    </div>
  );
}
