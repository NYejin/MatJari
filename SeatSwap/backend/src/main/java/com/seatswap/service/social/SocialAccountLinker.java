package com.seatswap.service.social;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import com.seatswap.domain.User;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 연결(확정 API) 한 건을 한 트랜잭션으로 저장한다. 별도 빈이라 호출 쪽({@link SocialAuthService})이
 * UNIQUE 위반(DataIntegrityViolationException)을 잡아 제약 이름으로 SOCIAL_ALREADY_LINKED / SOCIAL_LINKED_OTHER 를 구분할 수 있다.
 * 잠금은 users 행 FOR UPDATE 하나뿐이다(해제 API 와 같은 순서). 이메일 일치 여부는 보지 않는다 —
 * 로그인한 본인이 명시적으로 연결하는 것이라 자동 연결이 아니다.
 */
@Component
@RequiredArgsConstructor
public class SocialAccountLinker {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final AccountSuspensionChecker suspensionChecker;

    /**
     * 연결 대상 사용자 존재·정지 여부를 다시 검사한 뒤 social_account 를 저장한다.
     * 사용자가 없으면 400 INVALID_LINK_CODE, 정지면 AccountSuspendedException, 내가 이미 그 제공자를 가졌으면 409 SOCIAL_ALREADY_LINKED,
     * 그 제공자 계정이 다른 사용자에 연결돼 있으면 409 SOCIAL_LINKED_OTHER.
     */
    @Transactional
    public void link(Long userId, SocialProvider provider, String providerUserId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(SocialAuthException::invalidLinkCode);
        suspensionChecker.assertNotSuspended(user.getId());

        SocialAccount existing = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId).orElse(null);
        if (existing != null) {
            throw existing.getUserId().equals(userId) ? SocialAuthException.alreadyLinked() : SocialAuthException.linkedOther();
        }
        if (socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw SocialAuthException.alreadyLinked();
        }
        // flush 까지 즉시 수행해 UNIQUE 위반(동시 중복)을 이 메서드 안(트랜잭션 롤백)에서 드러낸다. 예외는 삼키지 않는다
        socialAccountRepository.saveAndFlush(SocialAccount.create(userId, provider, providerUserId));
    }
}
