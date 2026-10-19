package com.matjari.repository;

import com.matjari.domain.User;
import jakarta.persistence.LockModeType;
import com.matjari.domain.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** 사용자 단위 직렬화용 행 잠금(SELECT ... FOR UPDATE). 트랜잭션 안에서만 호출한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /** 탈퇴하지 않은 ADMIN 전부를 id 순으로 잠근다(역할 변경 직렬화: 마지막 관리자 보호). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = com.matjari.domain.UserRole.ADMIN and u.withdrawnAt is null order by u.id")
    List<User> lockActiveAdmins();

    /**
     * 관리자 이용자 목록. pattern 은 이미 '!' 로 이스케이프한 %..% 형태(없으면 '%'), idQuery 는 숫자 검색어(없으면 null 대신 -1).
     * status: ALL / ACTIVE(탈퇴 아님·정지 아님) / SUSPENDED(탈퇴 아님·활성 제재) / WITHDRAWN. role 이 null 이면 전체.
     */
    @Query(value = "select u from User u where "
            + "(:pattern = '%' or u.id = :idQuery or lower(u.email) like :pattern escape '!' or lower(u.nickname) like :pattern escape '!') "
            + "and (:role is null or u.role = :role) "
            + "and (:status = 'ALL' "
            + " or (:status = 'WITHDRAWN' and u.withdrawnAt is not null) "
            + " or (:status = 'SUSPENDED' and u.withdrawnAt is null and exists (select 1 from UserSanction s where s.userId = u.id and s.revokedAt is null and (s.endsAt is null or s.endsAt > :now))) "
            + " or (:status = 'ACTIVE' and u.withdrawnAt is null and not exists (select 1 from UserSanction s where s.userId = u.id and s.revokedAt is null and (s.endsAt is null or s.endsAt > :now)))) "
            + "order by u.id desc",
            countQuery = "select count(u) from User u where "
            + "(:pattern = '%' or u.id = :idQuery or lower(u.email) like :pattern escape '!' or lower(u.nickname) like :pattern escape '!') "
            + "and (:role is null or u.role = :role) "
            + "and (:status = 'ALL' "
            + " or (:status = 'WITHDRAWN' and u.withdrawnAt is not null) "
            + " or (:status = 'SUSPENDED' and u.withdrawnAt is null and exists (select 1 from UserSanction s where s.userId = u.id and s.revokedAt is null and (s.endsAt is null or s.endsAt > :now))) "
            + " or (:status = 'ACTIVE' and u.withdrawnAt is null and not exists (select 1 from UserSanction s where s.userId = u.id and s.revokedAt is null and (s.endsAt is null or s.endsAt > :now))))")
    Page<User> adminSearch(@Param("pattern") String pattern, @Param("idQuery") long idQuery, @Param("role") UserRole role,
                           @Param("status") String status, @Param("now") LocalDateTime now, Pageable pageable);
}
