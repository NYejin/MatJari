package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 사용자 차단 (V13). 방향은 하나(blocker -> blocked)이고 효과는 양방향이다: 둘 중 누가 차단했든 서로 후보에서 빠지고
 * 둘 사이의 열린 매칭은 차단 시점에 시스템 취소된다(서비스). 행은 INSERT/DELETE 만 하며(해제 = 행 삭제) 수정하지 않는다.
 *
 * DB 제약: uk_user_block_pair(blocker_id, blocked_id) — 중복 차단 방지이자 후보 SQL 의 양방향 NOT EXISTS 조회 인덱스,
 * ck_user_block_self(자기 차단 불가). 사용자당 상한(MAX_PER_USER)은 서비스가 검사한다.
 */
@Entity
@Table(name = "user_block")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class UserBlock {

    public static final String UNIQUE_PAIR = "uk_user_block_pair";
    public static final int MAX_PER_USER = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 차단한 사람 -> users. */
    @Column(name = "blocker_id", nullable = false, updatable = false)
    private Long blockerId;

    /** 차단당한 사람 -> users. */
    @Column(name = "blocked_id", nullable = false, updatable = false)
    private Long blockedId;

    /** JPA Auditing 이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static UserBlock of(Long blockerId, Long blockedId) {
        if (blockerId == null || blockedId == null) {
            throw new IllegalArgumentException("blockerId 와 blockedId 는 필수입니다.");
        }
        if (blockerId.equals(blockedId)) {
            throw new IllegalArgumentException("자기 자신은 차단할 수 없습니다.");
        }
        UserBlock b = new UserBlock();
        b.blockerId = blockerId;
        b.blockedId = blockedId;
        return b;
    }
}
