package com.seatswap.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/** GET /api/users/me/social. provider 는 소문자 경로값(google/kakao/naver), providers 는 지금 활성인 제공자. */
public record SocialAccountsResponse(List<Item> accounts, List<String> providers, boolean canUnlink) {

    public record Item(String provider, LocalDateTime linkedAt) {
    }
}
