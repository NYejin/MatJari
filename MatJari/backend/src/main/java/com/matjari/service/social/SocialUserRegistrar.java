package com.matjari.service.social;

import com.matjari.domain.SocialAccount;
import com.matjari.domain.SocialProvider;
import com.matjari.domain.User;
import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 소셜 가입 한 건(users + social_account)을 한 트랜잭션으로 만든다. 별도 빈이라 호출 쪽({@link SocialLoginService})이 UNIQUE 위반을 잡아 새 트랜잭션으로 다시 조회할 수 있다.
 * 같은 이메일의 기존 계정이 있으면 자동 연결하지 않고 EMAIL_EXISTS 로 거부한다. 이메일이 없으면 자리표시 이메일(.invalid 예약 TLD)을 쓴다.
 * 비밀번호는 무작위 32바이트의 BCrypt 해시라 아무도 모른다(비밀번호 로그인 불가, 컬럼 NOT NULL 유지).
 */
@Component
@RequiredArgsConstructor
public class SocialUserRegistrar {

    static final String PLACEHOLDER_DOMAIN = "social.matjari.invalid";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int NICKNAME_MIN = 2;
    private static final int NICKNAME_MAX = 20;

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    /** 가입하고 사용자 id 를 돌려준다. 이메일 중복이면 SocialLoginFailure(EMAIL_EXISTS). */
    @Transactional
    public Long register(SocialProvider provider, SocialProfile profile) {
        String email = profile.email();
        if (email != null) {
            if (userRepository.existsByEmail(email)) {
                throw new SocialLoginFailure(SocialLoginFailure.SocialError.EMAIL_EXISTS);
            }
        } else {
            email = placeholderEmail(provider, profile.providerUserId());
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String unknownPassword = passwordEncoder.encode(Base64.getEncoder().encodeToString(secret));

        // flush 까지 즉시 수행해 UNIQUE 위반을 이 메서드 안(트랜잭션 롤백)에서 드러낸다. 예외는 삼키지 않고 던진다.
        User user = userRepository.saveAndFlush(User.createSocial(email, unknownPassword, nicknameFor(provider, profile.nickname())));
        socialAccountRepository.saveAndFlush(SocialAccount.create(user.getId(), provider, profile.providerUserId()));
        return user.getId();
    }

    /** `{provider}.{id}@social.matjari.invalid`. id 가 영숫자·-_ 가 아니거나 너무 길면 SHA-256 앞 16자 hex. */
    static String placeholderEmail(SocialProvider provider, String providerUserId) {
        // DB 는 provider_user_id 를 대소문자 구분(bin)으로 비교하므로 소문자로 합치지 않고, 항상 해시(hex)를 쓴다
        return provider.getPath() + "." + sha256Prefix(provider.getPath() + ":" + providerUserId) + "@" + PLACEHOLDER_DOMAIN;
    }

    /** 제공자 닉네임을 정리(보이지 않는 문자 제거, 2~20자)하고, 쓸 수 없으면 `{한글명}사용자NNNN`. 닉네임은 유니크가 아니라 충돌 처리는 없다. */
    String nicknameFor(SocialProvider provider, String raw) {
        String cleaned = raw == null ? "" : AuthInputNormalizer.normalizeNickname(raw);
        StringBuilder sb = new StringBuilder();
        cleaned.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            if (!AuthInputNormalizer.containsInvisibleChar(s)) {
                sb.append(s);
            }
        });
        // 가입(AuthService)의 닉네임 검증이 UTF-16 길이 기준이라 같은 기준으로 20 이내로 자른다(코드 포인트 경계 유지)
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < sb.length(); ) {
            int cp = sb.codePointAt(i);
            int len = Character.charCount(cp);
            if (truncated.length() + len > NICKNAME_MAX) {
                break;
            }
            truncated.appendCodePoint(cp);
            i += len;
        }
        String nickname = AuthInputNormalizer.stripLikeJs(truncated.toString());
        if (nickname.length() >= NICKNAME_MIN) {
            return nickname;
        }
        return provider.getDisplayName() + "사용자" + String.format("%04d", random.nextInt(10_000));
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
