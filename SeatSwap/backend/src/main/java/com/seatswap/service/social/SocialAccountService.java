package com.seatswap.service.social;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import com.seatswap.domain.User;
import com.seatswap.dto.response.SocialAccountsResponse;
import com.seatswap.dto.response.SocialLinkResponse;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 마이페이지 소셜 계정 연결 관리: 조회, 연결 시작(링크 티켓 발급), 해제.
 * 연결 자체는 브라우저가 제공자를 다녀온 뒤 콜백에서 {@link SocialAccountLinker} 가 저장한다.
 * 해제는 users 행을 FOR UPDATE 로 잠근 뒤 '마지막 로그인 수단' 여부를 판단해 동시 해제로 수단이 0개가 되는 것을 막는다.
 */
@Service
@RequiredArgsConstructor
public class SocialAccountService {

    private final SocialProperties properties;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final SocialLinkTicketStore ticketStore;
    private final SocialRateLimiter rateLimiter;

    /** canUnlink = 비밀번호 로그인 가능 || 연결 계정 2개 이상. */
    @Transactional(readOnly = true)
    public SocialAccountsResponse list(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(SocialAccountService::unauthenticated);
        List<SocialAccount> accounts = socialAccountRepository.findByUserIdOrderByIdAsc(userId);
        List<SocialAccountsResponse.Item> items = accounts.stream()
                .map(a -> new SocialAccountsResponse.Item(a.getProvider().getPath(), a.getCreatedAt()))
                .toList();
        boolean canUnlink = user.isPasswordLoginEnabled() || accounts.size() >= 2;
        return new SocialAccountsResponse(items, properties.enabledProviderPaths(), canUnlink);
    }

    /** 연결을 시작한다: 일회용 링크 티켓을 발급해 authorize URL(상대 경로)을 돌려준다. */
    public SocialLinkResponse startLink(Long userId, String providerPath) {
        rateLimiter.acquire("l:" + userId, properties.getLinkPerMinute());
        SocialProvider provider = SocialProvider.fromPath(providerPath).orElseThrow(SocialAuthException::providerDisabled);
        if (!properties.isEnabled(provider)) {
            throw SocialAuthException.providerDisabled();
        }
        if (socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new SocialAuthException(HttpStatus.CONFLICT, "SOCIAL_ALREADY_LINKED", "이미 연결된 소셜 계정이에요.");
        }
        String ticket = ticketStore.issue(userId);
        return new SocialLinkResponse("/api/auth/social/" + provider.getPath() + "/authorize?link=" + ticket);
    }

    /** 연결을 해제한다(없으면 멱등). 마지막 로그인 수단이면 409 LAST_LOGIN_METHOD. 제공자가 지금은 비활성이어도 해제할 수 있다. */
    @Transactional
    public void unlink(Long userId, String providerPath) {
        SocialProvider provider = SocialProvider.fromPath(providerPath).orElseThrow(SocialAuthException::providerDisabled);
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(SocialAccountService::unauthenticated);
        List<SocialAccount> accounts = socialAccountRepository.findByUserIdOrderByIdAsc(userId);
        SocialAccount target = accounts.stream().filter(a -> a.getProvider() == provider).findFirst().orElse(null);
        if (target == null) {
            return;
        }
        if (!user.isPasswordLoginEnabled() && accounts.size() <= 1) {
            throw new SocialAuthException(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD",
                    "로그인 수단이 하나뿐이라 해제할 수 없어요.");
        }
        socialAccountRepository.delete(target);
    }

    private static InsufficientAuthenticationException unauthenticated() {
        return new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다.");
    }
}
