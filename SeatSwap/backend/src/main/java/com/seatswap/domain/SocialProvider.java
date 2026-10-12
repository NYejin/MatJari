package com.seatswap.domain;

import java.util.Locale;
import java.util.Optional;

/** 소셜 로그인 제공자. DB(social_account.provider)에는 이름(대문자)을 저장하고, URL 경로에는 소문자 경로값을 쓴다. */
public enum SocialProvider {
    GOOGLE("google", "Google", true),
    KAKAO("kakao", "카카오", true),
    // 네이버는 PKCE 를 지원하지 않는다(state 쿠키 검증으로 CSRF 방어)
    NAVER("naver", "네이버", false);

    private final String path;
    private final String displayName;
    private final boolean pkce;

    SocialProvider(String path, String displayName, boolean pkce) {
        this.path = path;
        this.displayName = displayName;
        this.pkce = pkce;
    }

    public String getPath() {
        return path;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean supportsPkce() {
        return pkce;
    }

    /** URL 경로값(소문자 정확히 일치)으로 찾는다. 없으면 빈 값. */
    public static Optional<SocialProvider> fromPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (SocialProvider p : values()) {
            if (p.path.equals(path)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** 쿠키 값처럼 외부에서 온 이름을 안전하게 되돌린다(대소문자 구분). */
    public static Optional<SocialProvider> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (SocialProvider p : values()) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** 로그용 소문자 이름. */
    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
