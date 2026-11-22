package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 서버에 저장한 리프레시 토큰(V27). 평문 토큰은 저장하지 않고 SHA-256 소문자 hex({@link #tokenHash})만 둔다.
 * 로그인 한 번이 계열({@link #familyId}, UUID 소문자)을 만들고, 재발급마다 옛 행을 {@code ROTATED}로 폐기하며
 * {@link #replacedById}에 새 행 id 를 적는다. 이미 폐기된 토큰이 다시 오면 재사용으로 보고 계열 전체를 {@code REUSE}로 폐기한다.
 * 만료 행은 서비스가 가끔 배치로 지운다(스케줄러 없음). 폐기됐지만 만료 전인 행은 재사용 탐지를 위해 남긴다.
 *
 * <p>DB 제약: 해시 64자 소문자 hex·계열 UUID 형식, expires_at &gt; created_at, revoked_at·revoke_reason 은 함께 NULL 이거나 함께 값
 * (revoked_at &gt;= created_at), revoke_reason 6종(V28 에서 PASSWORD_RESET 추가), replaced_by_id 는 ROTATED 일 때만이며 ROTATED 면 필수.
 * replaced_by_id ≠ id 는 AUTO_INCREMENT 컬럼을 CHECK 에 쓸 수 없어 이 클래스({@link #rotateTo})가 지킨다.
 *
 * <h2>잠금 규칙 (서비스 구현 시 지킬 것)</h2>
 * <ul>
 *   <li><b>해시로 잠그지 않는다.</b> 재발급은 {@code findIdByTokenHash}(잠금 없음)로 id 를 얻고 {@code findByIdForUpdate}(PK)로 잠근 뒤
 *       상태(폐기·만료·사용자)를 다시 판단한다. 보조 유니크 인덱스(uk_refresh_token_hash)에 FOR UPDATE 를 걸면 PK 잠금 경로와
 *       교착한다(좌석 인증 승인과 같은 규칙, wiki/gotchas/lock-order-and-index-pitfalls.md).</li>
 *   <li><b>토큰을 INSERT 하는 경로(로그인·재발급·소셜 exchange)는 users 행을 먼저 FOR UPDATE 로 잡는다.</b>
 *       INSERT 의 FK 검사가 users 행에 공유 잠금을 걸기 때문이다. 재발급이 [토큰 행 X → users S] 순서로 잡고 탈퇴가
 *       [users X → 토큰 행 X] 순서로 잡으면 교착한다. 그래서 재발급 순서는 users FOR UPDATE(탈퇴·정지 확인) → 토큰 PK FOR UPDATE
 *       → 새 행 INSERT → 옛 행 ROTATED(또는 재사용이면 계열 폐기)로 둔다. 같은 사용자의 동시 재발급은 users 잠금으로 직렬화된다.</li>
 *   <li><b>탈퇴({@code WithdrawService})는 users 행 FOR UPDATE 뒤(소셜 연결 삭제와 같은 단계)에서
 *       {@code revokeAllActiveByUserId(..., WITHDRAW)}를 호출한다.</b> 이 테이블은 티켓·요청·매칭 잠금 사슬과 겹치지 않으므로
 *       탈퇴의 기존 순서(인증 요청 → 티켓 → 매칭 → 요청 → users)에 users 다음으로 붙이면 된다. users 잠금 전에 호출하면
 *       그 사이 로그인이 새 행을 만들 수 있다(인증 단계가 탈퇴 계정을 거절하므로 위험은 없지만 정리가 빠진다).
 *       관리자 이용 정지(ADMIN)는 대상 users 행을 FOR UPDATE 로 잡은 뒤 같은 메서드를 쓰고, 관리자 강제 탈퇴는 탈퇴 코어를 그대로 써서 WITHDRAW 로 폐기한다.</li>
 *   <li>로그아웃은 INSERT 하지 않지만 회전 중 새로 생기는 행을 놓치지 않도록 재발급과 같은 순서(users → 토큰 PK → 계열 폐기)로 잡는다.</li>
 *   <li>만료 행 배치 삭제({@code deleteExpiredBatch})는 users 잠금을 잡은 트랜잭션 밖에서 짧은 별도 트랜잭션으로 부른다.
 *       idx_refresh_token_expires 범위만 잠그며, 새 행의 expires_at 은 항상 미래라 로그인·재발급 INSERT 와 겹치지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "refresh_token")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class RefreshToken {

    /** 폐기 사유. DB CHECK(ck_refresh_token_reason)와 같은 6종(PASSWORD_RESET 은 V28). */
    public enum RevokeReason {
        /** 재발급으로 새 토큰으로 바뀜(replacedById 필수). */
        ROTATED,
        /** 사용자가 로그아웃. */
        LOGOUT,
        /** 이미 폐기된 토큰이 다시 쓰임(도난 의심) — 계열 전체 폐기. */
        REUSE,
        /** 회원 탈퇴. */
        WITHDRAW,
        /** 관리자 조치(이용 정지·강제 탈퇴 등). */
        ADMIN,
        /** 비밀번호 재설정으로 그 사용자의 세션을 모두 끊음(V28). */
        PASSWORD_RESET
    }

    public static final int TOKEN_HASH_LENGTH = 64;
    public static final int FAMILY_ID_LENGTH = 36;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 토큰 주인 -> users. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** SHA-256(토큰) 소문자 hex 64자. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = TOKEN_HASH_LENGTH, columnDefinition = "char(64)")
    private String tokenHash;

    /** 회전 계열(로그인 한 번), UUID 소문자 36자. */
    @Column(name = "family_id", nullable = false, updatable = false, length = FAMILY_ID_LENGTH, columnDefinition = "char(36)")
    private String familyId;

    /** 계열의 첫 로그인 시각(V27, 회전 때 그대로 물려준다). 세션 최대 수명 = 이 값 + 설정(matjari.auth.refresh-max-session-days). */
    @Column(name = "family_started_at", nullable = false, updatable = false)
    private LocalDateTime familyStartedAt;

    /** JPA Auditing 이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "revoke_reason", length = 20)
    private RevokeReason revokeReason;

    /** 회전으로 만든 새 행 id(FK 아님). ROTATED 일 때만 값. */
    @Column(name = "replaced_by_id")
    private Long replacedById;

    /**
     * 새 토큰 행. expiresAt 은 서비스가 Clock 기준 현재 시각 + 유효기간으로 계산해 넘긴다(created_at 은 저장 때 Auditing 이 채움).
     * 형식 검사(해시 64자 소문자 hex·UUID 소문자)는 DB CHECK 가 최후 방어선이다.
     */
    public static RefreshToken issue(Long userId, String tokenHash, String familyId, LocalDateTime familyStartedAt, LocalDateTime expiresAt) {
        RefreshToken t = new RefreshToken();
        t.userId = Objects.requireNonNull(userId, "userId");
        t.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        t.familyId = Objects.requireNonNull(familyId, "familyId");
        t.familyStartedAt = Objects.requireNonNull(familyStartedAt, "familyStartedAt");
        t.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return t;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 폐기되지 않았고 now 가 만료 전이면 쓸 수 있다. */
    public boolean isUsableAt(LocalDateTime now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /**
     * 회전: 새 행(이미 저장돼 id 가 있는 행)으로 바뀌었음을 기록한다. 잠근 행(findByIdForUpdate)에서만 부른다.
     * @throws IllegalStateException 이미 폐기된 행(재사용 탐지는 호출 전에 서비스가 판단한다)
     */
    public void rotateTo(Long newTokenId, LocalDateTime now) {
        Objects.requireNonNull(newTokenId, "newTokenId");
        if (newTokenId.equals(id)) {
            throw new IllegalArgumentException("자기 자신으로 회전할 수 없습니다");
        }
        markRevoked(RevokeReason.ROTATED, now);
        this.replacedById = newTokenId;
    }

    /**
     * 회전이 아닌 사유로 이 행 하나를 폐기한다(이미 폐기됐으면 아무것도 하지 않는다 — 멱등).
     * 여러 행은 리포지토리의 일괄 UPDATE(revokeAllActiveByFamilyId / revokeAllActiveByUserId)를 쓴다.
     */
    public void revoke(RevokeReason reason, LocalDateTime now) {
        if (reason == RevokeReason.ROTATED) {
            throw new IllegalArgumentException("ROTATED 는 rotateTo 로 기록합니다");
        }
        if (revokedAt != null) {
            return;
        }
        markRevoked(reason, now);
    }

    private void markRevoked(RevokeReason reason, LocalDateTime now) {
        if (revokedAt != null) {
            throw new IllegalStateException("이미 폐기된 리프레시 토큰입니다");
        }
        this.revokedAt = Objects.requireNonNull(now, "now");
        this.revokeReason = reason;
    }
}
