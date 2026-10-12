package com.seatswap.service.social;

/**
 * 제공자 userinfo 에서 뽑은 최소 정보. email 은 제공자가 검증했다고 알린 것만 담고(아니면 null), nickname 은 없을 수 있다.
 * toString 에 이메일·닉네임이 실리지 않게 한다.
 */
public record SocialProfile(String providerUserId, String email, String nickname) {

    @Override
    public String toString() {
        return "SocialProfile[providerUserId=***]";
    }
}
