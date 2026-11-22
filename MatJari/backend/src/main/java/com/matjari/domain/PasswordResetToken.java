package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 비밀번호 재설정 링크 토큰(V28). 링크에는 무작위 32바이트 토큰을 싣고 DB 에는 SHA-256 소문자 hex({@link #tokenHash})만 둔다.
 * 상태는 두 시각으로 나눈다: {@link #usedAt}(이 링크로 비밀번호를 바꿈), {@link #invalidatedAt}(쓰이지 않고 무효 — 새 요청으로 대체 등).
 * 둘 다 NULL 이고 만료 전이면 쓸 수 있다.
 *
 * <p>DB 제약: 해시 64자 소문자 hex, expires_at &gt; created_at, used_at·invalidated_at 은 동시에 값일 수 없고 각각 &gt;= created_at,
 * request_ip 는 NULL 이거나 2~45자 [0-9A-Fa-f:.]. 사용자당 살아 있는(used_at·invalidated_at 모두 NULL) 행은 1개
 * (생성 컬럼 live_user_id + uk_password_reset_token_live, 이 엔티티는 매핑하지 않는다). 만료됐어도 무효화하지 않으면 살아 있는 것으로 센다.
 *
 * <h2>잠금 규칙 (서비스 구현 시 지킬 것)</h2>
 * <ul>
 *   <li><b>요청(링크 발급)</b>: users 행 FOR UPDATE(탈퇴·정지·비밀번호 로그인 가능 여부 확인) →
 *       {@code invalidateAllActiveByUserId}(이전 링크 무효화) → 새 행 INSERT. INSERT 의 FK 검사가 users 행에 공유 잠금을 걸므로
 *       users 를 먼저 X 로 잡는다(V27 RefreshToken 과 같은 이유). 같은 사용자의 동시 요청은 users 잠금으로 직렬화되고,
 *       그래도 무효화를 빠뜨리면 uk_password_reset_token_live 가 두 번째 INSERT 를 거부한다. 메일 발송은 커밋 뒤에 한다.</li>
 *   <li><b>확정(새 비밀번호 저장)</b>: {@code findIdByTokenHash}(잠금 없음)로 id 를 얻고 → {@code findUserIdById}(잠금 없음) →
 *       users 행 FOR UPDATE → {@code findByIdForUpdate}(토큰 PK) → 상태 재판단({@link #isUsableAt}, 토큰의 userId 가 잠근 users 와 같은지,
 *       탈퇴 여부) → 비밀번호 변경 → {@link #markUsed} → 그 사용자 리프레시 토큰 일괄 폐기(PASSWORD_RESET).
 *       보조 유니크 인덱스(uk_password_reset_token_hash)에 FOR UPDATE 를 걸지 않는다(좌석 인증 승인·V27 과 같은 규칙).
 *       users 를 토큰보다 먼저 잡는 이유: 요청 경로가 [users X → 토큰 INSERT]이고 탈퇴가 [users X → 토큰 정리]라서,
 *       확정이 [토큰 X → users X]로 잡으면 교착한다.</li>
 *   <li><b>탈퇴({@code WithdrawService})</b>: users 행 FOR UPDATE 뒤(소셜 연결 삭제·리프레시 토큰 폐기와 같은 단계)에서
 *       {@code deleteAllByUserId}로 그 사용자의 행을 모두 지운다. 탈퇴 뒤에는 쓸 일이 없고 request_ip 가 개인정보라 무효화보다 삭제가 낫다.
 *       users 잠금 전에 지우면 그 사이 요청이 새 행을 만들 수 있다. 이 테이블은 티켓·요청·매칭 잠금 사슬과 겹치지 않는다.</li>
 *   <li>다른 경로로 비밀번호가 바뀌면(나중에 비밀번호 변경 기능이 생기면) users 잠금 안에서 {@code invalidateAllActiveByUserId}를 부른다.</li>
 *   <li>만료 행 배치 삭제({@code deleteExpiredBatch})는 users 잠금 밖 짧은 별도 트랜잭션에서 부른다. idx_password_reset_token_expires 범위만 잠그며,
 *       새 행의 expires_at 은 항상 미래라 요청 INSERT 와 겹치지 않는다. 사용·무효 여부와 무관하게 만료된 행을 지운다(감사용으로 더 두려면 cutoff 를 과거로).</li>
 * </ul>
 */
@Entity
@Table(name = "password_reset_token")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class PasswordResetToken {

    public static final int TOKEN_HASH_LENGTH = 64;
    public static final int REQUEST_IP_MAX_LENGTH = 45;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 재설정 대상 -> users. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** SHA-256(토큰) 소문자 hex 64자. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = TOKEN_HASH_LENGTH, columnDefinition = "char(64)")
    private String tokenHash;

    /** JPA Auditing 이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    /** 이 링크로 비밀번호를 바꾼 시각. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** 쓰이지 않고 무효가 된 시각(새 요청으로 대체 등). */
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    /** 요청 IP(getRemoteAddr, 감사용). 개인정보라 만료 정리·탈퇴 때 행과 함께 지운다. */
    @Column(name = "request_ip", updatable = false, length = REQUEST_IP_MAX_LENGTH)
    private String requestIp;

    /**
     * 새 링크 토큰 행. expiresAt 은 서비스가 Clock 기준 현재 시각 + 유효기간으로 넘긴다(created_at 은 저장 때 Auditing).
     * requestIp 는 NULL 가능, 45자를 넘거나 형식이 다르면 DB CHECK 가 거부하므로 서비스가 미리 걸러 NULL 로 둔다.
     */
    public static PasswordResetToken issue(Long userId, String tokenHash, LocalDateTime expiresAt, String requestIp) {
        PasswordResetToken t = new PasswordResetToken();
        t.userId = Objects.requireNonNull(userId, "userId");
        t.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        t.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        t.requestIp = requestIp;
        return t;
    }

    /** 쓰이지도 무효화되지도 않았고 now 가 만료 전이면 쓸 수 있다. */
    public boolean isUsableAt(LocalDateTime now) {
        return usedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

    /**
     * 비밀번호를 바꿨음을 기록한다. 잠근 행(findByIdForUpdate)에서 {@link #isUsableAt}을 확인한 뒤에만 부른다.
     * @throws IllegalStateException 이미 쓰였거나 무효화된 행
     */
    public void markUsed(LocalDateTime now) {
        if (usedAt != null || invalidatedAt != null) {
            throw new IllegalStateException("이미 쓰였거나 무효화된 비밀번호 재설정 토큰입니다");
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }
}
