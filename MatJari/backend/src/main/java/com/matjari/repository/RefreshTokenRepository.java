package com.matjari.repository;

import com.matjari.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 리프레시 토큰 저장소(V27). 잠금 순서와 호출 위치는 {@link RefreshToken} Javadoc 을 따른다.
 *
 * <p>일괄 UPDATE·DELETE 는 영속성 컨텍스트를 거치지 않는다. 같은 트랜잭션에서 이미 읽은 RefreshToken 엔티티는 낡은 값이 되므로
 * 다시 읽어야 한다(clearAutomatically 는 쓰지 않는다 — 함께 잡은 users 엔티티까지 분리하지 않도록).
 * 일괄 폐기에 {@link RefreshToken.RevokeReason#ROTATED}를 넘기면 replaced_by_id 가 없어 DB CHECK(ck_refresh_token_replaced)가 거부한다.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 해시로 id 만 잠금 없이 읽는다. 이어서 {@link #findByIdForUpdate}로 PK 를 잠그고 상태를 다시 판단한다(해시 인덱스에 FOR UPDATE 금지). */
    @Query("select t.id from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<Long> findIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** 토큰 주인 id 를 잠금 없이 읽는다(엔티티를 영속성 컨텍스트에 올리지 않아 이어지는 FOR UPDATE 조회가 낡은 값을 쓰지 않는다). */
    @Query("select t.userId from RefreshToken t where t.id = :id")
    Optional<Long> findUserIdById(@Param("id") Long id);

    /** 토큰 행 잠금(SELECT ... FOR UPDATE, PK). 토큰을 INSERT 할 경로라면 users 행 FOR UPDATE 를 먼저 잡는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") Long id);

    /** 재사용 탐지: 계열의 살아 있는 행을 모두 폐기한다(이미 폐기된 행의 사유는 그대로 둔다). 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeAllActiveByFamilyId(@Param("familyId") String familyId,
                                  @Param("reason") RefreshToken.RevokeReason reason,
                                  @Param("now") LocalDateTime now);

    /** 탈퇴·관리자 조치: 사용자의 살아 있는 행을 모두 폐기한다. users 행 FOR UPDATE 를 잡은 트랜잭션 안에서 부른다. */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") Long userId,
                                @Param("reason") RefreshToken.RevokeReason reason,
                                @Param("now") LocalDateTime now);

    /**
     * 만료 행 배치 삭제(폐기 여부와 무관, expires_at &lt; cutoff). idx_refresh_token_expires 범위로 오래된 것부터 최대 limit 개를 지우고
     * 지운 행 수를 돌려준다. users 잠금 밖의 짧은 별도 트랜잭션에서 부른다(스케줄러 없이 로그인·재발급 때 가끔).
     * replaced_by_id 는 FK 가 아니라 계열 순서와 무관하게 지울 수 있다.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_token WHERE expires_at < :cutoff ORDER BY expires_at LIMIT :limit", nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
