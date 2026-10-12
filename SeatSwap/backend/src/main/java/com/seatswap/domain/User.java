package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /**
     * 비밀번호로 로그인할 수 있는 계정인가(V16). 일반 가입 true, 소셜 가입 false(비밀번호는 무작위 해시라 쓸 수 없다).
     * 소셜 연결 해제 때 '마지막 로그인 수단' 판단에 쓴다.
     */
    @Column(name = "password_login_enabled", nullable = false)
    private boolean passwordLoginEnabled = true;

    @Column(nullable = false)
    private String nickname;

    /** 권한. 가입은 항상 USER, ADMIN은 DB에서 직접 부여한다. (varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    /** 탈퇴 시각(V17, KST). NULL 이면 활성 회원. 값이 있으면 익명화된 탈퇴 회원이다. */
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // 신뢰도 점수 — 거래 완료 후 Review 누적 기반 (요구사항정의서 FR-13)
    private Double trustScore = 0.0;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 회원가입 시 사용하는 생성 팩토리. password는 호출 전에 반드시 암호화되어 있어야 한다
     * (AuthService에서 PasswordEncoder.encode() 거친 값만 전달).
     */
    public static User create(String email, String encodedPassword, String nickname) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.nickname = nickname;
        user.passwordLoginEnabled = true; // 일반 가입
        user.trustScore = 0.0;
        user.role = UserRole.USER; // 가입은 항상 USER
        return user;
    }

    /** 소셜 가입용 팩토리. 비밀번호는 아무도 모르는 무작위 해시이므로 비밀번호 로그인은 불가(passwordLoginEnabled=false). */
    public static User createSocial(String email, String unknownEncodedPassword, String nickname) {
        User user = create(email, unknownEncodedPassword, nickname);
        user.passwordLoginEnabled = false;
        return user;
    }

    public static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }

    public static String withdrawnEmail(Long userId) {
        return "withdrawn." + userId + "@withdrawn.seatswap.invalid";
    }

    /**
     * 탈퇴 익명화. 이메일은 예약 도메인(.invalid)의 id 기반 값이라 유니크가 유지되고, 비밀번호는 아무도 모르는 무작위 해시이며
     * 비밀번호 로그인은 꺼진다. role 은 그대로 둔다. 연관 데이터 정리는 WithdrawService 몫이다.
     */
    public void anonymizeForWithdrawal(String unknownEncodedPassword, LocalDateTime now) {
        if (withdrawnAt != null) {
            throw new IllegalStateException("이미 탈퇴한 회원입니다.");
        }
        this.email = withdrawnEmail(id);
        this.nickname = WITHDRAWN_NICKNAME;
        this.password = unknownEncodedPassword;
        this.passwordLoginEnabled = false;
        this.withdrawnAt = now;
    }

    /** 닉네임 변경. 검증·정규화는 호출 쪽(UserService)이 한다. */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
