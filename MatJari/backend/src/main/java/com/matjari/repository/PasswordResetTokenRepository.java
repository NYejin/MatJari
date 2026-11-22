package com.matjari.repository;

import com.matjari.domain.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 저장소(V28). 잠금 순서와 호출 위치는 {@link PasswordResetToken} Javadoc 을 따른다.
 *
 * <p>일괄 UPDATE·DELETE 는 영속성 컨텍스트를 거치지 않는다. 같은 트랜잭션에서 이미 읽은 엔티티는 낡은 값이 되므로 다시 읽어야 한다
 * (clearAutomatically 는 쓰지 않는다 — 함께 잡은 users 엔티티까지 분리하지 않도록).
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 해시로 id 만 잠금 없이 읽는다. 이어서 users FOR UPDATE → {@link #findByIdForUpdate}(PK) 순서로 잠근다(해시 인덱스에 FOR UPDATE 금지). */
    @Query("select t.id from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<Long> findIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** 토큰 주인 id 를 잠금 없이 읽는다(엔티티를 영속성 컨텍스트에 올리지 않아 이어지는 FOR UPDATE 조회가 낡은 값을 쓰지 않는다). */
    @Query("select t.userId from PasswordResetToken t where t.id = :id")
    Optional<Long> findUserIdById(@Param("id") Long id);

    /** 토큰 행 잠금(SELECT ... FOR UPDATE, PK). users 행 FOR UPDATE 를 먼저 잡은 뒤 부른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.id = :id")
    Optional<PasswordResetToken> findByIdForUpdate(@Param("id") Long id);

    /**
     * 사용자의 살아 있는(미사용·미무효) 행을 모두 무효화한다(만료 여부 무관 — 만료됐어도 uk_password_reset_token_live 에서는 살아 있는 행이다).
     * 새 요청의 INSERT 직전, users 행 FOR UPDATE 를 잡은 트랜잭션 안에서 부른다. 바뀐 행 수를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.invalidatedAt = :now "
            + "where t.userId = :userId and t.usedAt is null and t.invalidatedAt is null")
    int invalidateAllActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 사용자의 행 id 를 (expires_at, id) 순으로 잠금 없이 읽는다. 만료 정리(expires_at 순)와 같은 방향으로 지워 반대 순서 잠금을 피한다. */
    @Query("select t.id from PasswordResetToken t where t.userId = :userId order by t.expiresAt, t.id")
    java.util.List<Long> findIdsByUserIdOrdered(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PasswordResetToken t where t.id = :id")
    int deleteRowById(@Param("id") Long id);

    /**
     * 탈퇴: 사용자의 행을 모두 지운다(request_ip 개인정보 파기). users 행 FOR UPDATE 를 잡은 트랜잭션 안에서 부른다(그 사이 새 행이 생기지 않는다).
     * 한 문장 DELETE 대신 (expires_at, id) 순으로 한 행씩 지워, 같은 방향(expires_at 순)으로 잠그는 만료 정리 배치와 교착하지 않게 한다.
     */
    default int deleteAllByUserId(Long userId) {
        int n = 0;
        for (Long id : findIdsByUserIdOrdered(userId)) {
            n += deleteRowById(id);
        }
        return n;
    }

    /**
     * 만료 행 배치 삭제(사용·무효 여부와 무관, expires_at &lt; cutoff). idx_password_reset_token_expires 범위로 오래된 것부터 최대 limit 개를
     * 지우고 지운 행 수를 돌려준다. users 잠금 밖의 짧은 별도 트랜잭션에서 부른다.
     */
    @Modifying
    @Query(value = "DELETE FROM password_reset_token WHERE expires_at < :cutoff ORDER BY expires_at LIMIT :limit", nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
