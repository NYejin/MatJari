package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** 소셜 계정 연결(V14). 행은 INSERT 만 한다. 이메일·프로필은 저장하지 않는다(최소 수집). */
@Entity
@Table(name = "social_account")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    /** 제공자가 주는 고유 식별자(구글 sub / 카카오 id / 네이버 response.id). */
    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public static SocialAccount create(Long userId, SocialProvider provider, String providerUserId) {
        SocialAccount a = new SocialAccount();
        a.userId = userId;
        a.provider = provider;
        a.providerUserId = providerUserId;
        return a;
    }
}
