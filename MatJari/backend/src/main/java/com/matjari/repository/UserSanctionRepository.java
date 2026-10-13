package com.matjari.repository;

import com.matjari.domain.UserSanction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 이용 정지 저장소. 활성 = revoked_at IS NULL AND (ends_at IS NULL OR ends_at > now) — idx_user_sanction_user 로 사용자 단위 조회.
 * 인증 경로(요청마다)의 조회는 잠금 없는 일반 SELECT 이다. 잠그는 조회는 관리자 처리 트랜잭션만 쓴다.
 */
public interface UserSanctionRepository extends JpaRepository<UserSanction, Long> {

    String ACTIVE = "s.revokedAt is null and (s.endsAt is null or s.endsAt > :now)";

    @Query("select s from UserSanction s where s.userId = :userId and " + ACTIVE)
    List<UserSanction> findActive(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 활성 제재 중복 검사용 잠금 읽기(최신 커밋 기준 + 범위 잠금). users 행을 잠그지 않고 같은 대상에 대한 제재 생성을 직렬화한다
     * (동시에 둘이 검사하면 한쪽이 교착으로 롤백돼 503 이 되고 중복 제재는 생기지 않는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSanction s where s.userId = :userId and " + ACTIVE)
    List<UserSanction> findActiveForUpdate(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSanction s where s.id = :id")
    Optional<UserSanction> findByIdForUpdate(@Param("id") Long id);

    @Query("select s from UserSanction s where s.userId in :userIds and " + ACTIVE)
    List<UserSanction> findActiveByUserIds(@Param("userIds") Collection<Long> userIds, @Param("now") LocalDateTime now);

    @Query(value = "select s from UserSanction s order by s.id desc", countQuery = "select count(s) from UserSanction s")
    Page<UserSanction> findPageAll(Pageable pageable);

    @Query(value = "select s from UserSanction s where " + ACTIVE + " order by s.id desc",
            countQuery = "select count(s) from UserSanction s where " + ACTIVE)
    Page<UserSanction> findPageActive(@Param("now") LocalDateTime now, Pageable pageable);

    @Query(value = "select s from UserSanction s where s.revokedAt is not null or (s.endsAt is not null and s.endsAt <= :now) order by s.id desc",
            countQuery = "select count(s) from UserSanction s where s.revokedAt is not null or (s.endsAt is not null and s.endsAt <= :now)")
    Page<UserSanction> findPageInactive(@Param("now") LocalDateTime now, Pageable pageable);
}
