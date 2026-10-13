package com.seatswap.dto.request;

/**
 * 회원 탈퇴 본인 확인. 비밀번호 로그인 계정은 password, 소셜 전용 계정은 confirmText("탈퇴합니다")와 reauthCode(연결된 소셜 계정으로 방금 다시 인증해 받은
 * 일회용 코드; 연결된 제공자가 지금 하나도 켜져 있지 않으면 생략 가능)를 쓴다.
 * 모두 선택 필드이며 어느 쪽이 필요한지는 서버가 계정 종류로 정한다. 값은 로그에 남기지 않는다(toString 가림).
 */
public record WithdrawRequest(String password, String confirmText, String reauthCode) {

    public WithdrawRequest(String password, String confirmText) {
        this(password, confirmText, null);
    }

    @Override
    public String toString() {
        return "WithdrawRequest[redacted]";
    }
}
