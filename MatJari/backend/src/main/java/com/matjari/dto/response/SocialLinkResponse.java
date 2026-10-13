package com.matjari.dto.response;

/** 연결 시작 응답. authorizeUrl 은 API base 에 붙일 상대 경로(`/api/auth/social/{provider}/authorize?link={ticket}`). */
public record SocialLinkResponse(String authorizeUrl) {

    /** 티켓이 로그에 남지 않도록 toString 에서 가린다. */
    @Override
    public String toString() {
        return "SocialLinkResponse[authorizeUrl=****]";
    }
}
