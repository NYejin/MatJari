package com.seatswap.service.social;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 소셜 프로필로 사용자를 정한다(트랜잭션 없음 — 가입은 {@link SocialUserRegistrar} 가 새 트랜잭션으로 한다).
 * 1) social_account 가 있으면 그 사용자로 로그인(이메일 변경은 보지 않는다) 2) 없으면 가입(같은 이메일의 기존 계정이 있으면 EMAIL_EXISTS, 자동 연결 안 함).
 * 동시에 같은 소셜 계정으로 첫 로그인하면 UNIQUE(provider, provider_user_id) 위반이 나므로, 위반 뒤 다시 조회해 먼저 만들어진 사용자로 진행한다.
 * 정지 계정은 AccountSuspendedException(403 ACCOUNT_SUSPENDED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialAccountRepository socialAccountRepository;
    private final SocialUserRegistrar registrar;
    private final UserRepository userRepository;
    private final AccountSuspensionChecker suspensionChecker;

    /** 로그인(또는 가입)하고 사용자 id 를 돌려준다. */
    public Long loginOrSignup(SocialProvider provider, SocialProfile profile) {
        Long userId = socialAccountRepository.findByProviderAndProviderUserId(provider, profile.providerUserId())
                .map(SocialAccount::getUserId)
                .orElse(null);
        if (userId == null) {
            userId = signup(provider, profile);
        }
        suspensionChecker.assertNotSuspended(userId);
        return userId;
    }

    private Long signup(SocialProvider provider, SocialProfile profile) {
        try {
            return registrar.register(provider, profile);
        } catch (DataIntegrityViolationException e) {
            // 경합: 같은 소셜 계정을 다른 요청이 먼저 만들었으면 그 사용자로 진행(재시도 1회 = 재조회)
            Optional<SocialAccount> raced = socialAccountRepository.findByProviderAndProviderUserId(provider, profile.providerUserId());
            if (raced.isPresent()) {
                log.info("Social signup race resolved (provider: {})", provider);
                return raced.get().getUserId();
            }
            // 소셜 계정은 없는데 이메일이 막 생겼다면 이메일 UNIQUE 경합
            if (profile.email() != null && userRepository.existsByEmail(profile.email())) {
                throw new SocialLoginFailure(SocialLoginFailure.SocialError.EMAIL_EXISTS);
            }
            throw e;
        }
    }
}
