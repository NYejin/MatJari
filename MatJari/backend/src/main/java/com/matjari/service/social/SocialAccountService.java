package com.matjari.service.social;

import com.matjari.domain.SocialAccount;
import com.matjari.domain.SocialProvider;
import com.matjari.domain.User;
import com.matjari.dto.response.SocialAccountsResponse;
import com.matjari.dto.response.SocialConfirmResponse;
import com.matjari.dto.response.SocialLinkResponse;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 마이페이지 소셜 계정 연결 관리: 조회, 연결 시작(링크 티켓 발급), 해제.
 * 연결 저장은 제공자를 다녀온 콜백이 발급한 '연결 확정 코드'를 로그인한 본인이 {@link #confirm} 으로 확정할 때만 {@link SocialAccountLinker} 가 한다.
 * 해제는 users 행을 FOR UPDATE 로 잠근 뒤 '마지막 로그인 수단' 여부를 판단해 동시 해제로 수단이 0개가 되는 것을 막는다.
 */
@Service
@RequiredArgsConstructor
public class SocialAccountService {

    private final SocialProperties properties;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final SocialLinkTicketStore ticketStore;
    private final SocialLinkConfirmStore confirmStore;
    private final SocialAccountLinker accountLinker;
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
        String ticket = ticketStore.issue(userId, provider);
        return new SocialLinkResponse("/api/auth/social/" + provider.getPath() + "/authorize?link=" + ticket);
    }

    /**
     * 탈퇴 재인증을 시작한다: **이미 연결된** 제공자에 대해 같은 링크 티켓 흐름을 연다(연결 모드와 같은 authorize → 콜백이 `/me#linkCode=` 로 일회용 코드를 넘김).
     * 이 코드는 연결 확정이 아니라 회원탈퇴 요청의 reauthCode 로 쓴다. 연결돼 있지 않은 제공자는 409 SOCIAL_NOT_LINKED, 꺼진 제공자는 404.
     * 사용자당 분당 link-per-minute 회(연결 시작과 같은 버킷).
     */
    public SocialLinkResponse startReauth(Long userId, String providerPath) {
        rateLimiter.acquire("l:" + userId, properties.getLinkPerMinute());
        SocialProvider provider = SocialProvider.fromPath(providerPath).orElseThrow(SocialAuthException::providerDisabled);
        if (!properties.isEnabled(provider)) {
            throw SocialAuthException.providerDisabled();
        }
        if (!socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new SocialAuthException(HttpStatus.CONFLICT, "SOCIAL_NOT_LINKED", "연결되어 있지 않은 소셜 계정이에요.");
        }
        String ticket = ticketStore.issue(userId, provider);
        return new SocialLinkResponse("/api/auth/social/" + provider.getPath() + "/authorize?link=" + ticket);
    }

    /**
     * 연결을 확정한다. 코드를 먼저 소비(불일치여도 소진)한 뒤 코드의 사용자와 인증 주체가 다르면 400 INVALID_LINK_CODE
     * (없음·만료·다른 사용자 모두 같은 응답). 사용자당 분당 link-per-minute 회(별도 버킷).
     * 이 메서드는 트랜잭션이 아니며 저장은 {@link SocialAccountLinker#link}(users FOR UPDATE) 한 트랜잭션이다.
     */
    public SocialConfirmResponse confirm(Long userId, String code) {
        rateLimiter.acquire("lc:" + userId, properties.getLinkPerMinute());
        SocialLinkConfirmStore.Payload payload = confirmStore.consume(code).orElseThrow(SocialAuthException::invalidLinkCode);
        if (!payload.userId().equals(userId)) {
            throw SocialAuthException.invalidLinkCode();
        }
        try {
            accountLinker.link(userId, payload.provider(), payload.providerUserId());
        } catch (DataIntegrityViolationException e) {
            // 동시 연결 경합: 어느 유니크 제약인지로 구분한다(값은 로그에 남기지 않는다)
            throw linkConflict(e);
        }
        return new SocialConfirmResponse(payload.provider().getPath());
    }

    /** UNIQUE 위반을 제약 이름으로 구분: (user_id, provider)=내가 이미 보유 -> SOCIAL_ALREADY_LINKED, 그 외(provider,provider_user_id 포함)=SOCIAL_LINKED_OTHER. */
    static SocialAuthException linkConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t instanceof org.hibernate.exception.ConstraintViolationException cve ? cve.getConstraintName() : null;
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if ((name != null && name.contains("uk_social_account_user_provider")) || msg.contains("uk_social_account_user_provider")) {
                return SocialAuthException.alreadyLinked();
            }
            if ((name != null && name.contains("uk_social_account_provider_user")) || msg.contains("uk_social_account_provider_user")) {
                return SocialAuthException.linkedOther();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return SocialAuthException.linkedOther();
    }

    /** 연결을 해제한다(없으면 멱등). 마지막 로그인 수단이면 409 LAST_LOGIN_METHOD. 제공자가 지금은 비활성이어도 해제할 수 있다. */
    @Transactional
    public void unlink(Long userId, String providerPath) {
        SocialProvider provider = SocialProvider.fromPath(providerPath).orElseThrow(SocialAuthException::providerDisabled);
        User user = userRepository.findByIdForUpdate(userId)
                .filter(u -> !u.isWithdrawn()).orElseThrow(SocialAccountService::unauthenticated);
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
