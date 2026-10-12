package com.seatswap.repository;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    List<SocialAccount> findByUserIdOrderByIdAsc(Long userId);

    boolean existsByUserIdAndProvider(Long userId, SocialProvider provider);
}
