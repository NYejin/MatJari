package com.seatswap.repository;

import com.seatswap.domain.UserBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    long countByBlockerId(Long blockerId);

    Optional<UserBlock> findByIdAndBlockerId(Long id, Long blockerId);

    /** 내 차단 목록(최신순): [id, blockedNickname, createdAt]. uk_user_block_pair 의 blocker_id 접두를 쓴다. */
    @Query(value = """
            select b.id, u.nickname, b.createdAt from UserBlock b, User u
            where u.id = b.blockedId and b.blockerId = :blockerId
            order by b.id desc
            """,
            countQuery = "select count(b) from UserBlock b where b.blockerId = :blockerId")
    Page<Object[]> findListByBlockerId(@Param("blockerId") Long blockerId, Pageable pageable);
}
