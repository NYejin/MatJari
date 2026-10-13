package com.seatswap.repository;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    List<SocialAccount> findByUserIdOrderByIdAsc(Long userId);

    /** 회원 탈퇴: 사용자의 소셜 연결을 모두 지운다(users 행 잠금을 잡은 트랜잭션 안에서). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from SocialAccount a where a.userId = :userId")
    int deleteAllByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    boolean existsByUserIdAndProvider(Long userId, SocialProvider provider);

    /** 탈퇴 재인증: 제공자에서 방금 인증한 계정(제공자 사용자 id)이 이 사용자에게 연결된 그 계정인가. */
    boolean existsByUserIdAndProviderAndProviderUserId(Long userId, SocialProvider provider, String providerUserId);
}
