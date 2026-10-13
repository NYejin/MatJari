package com.matjari.dto.response;

import com.matjari.domain.User;
import com.matjari.domain.UserRole;

// role은 UI 분기용 표시값이다. 서버의 권한 판단은 항상 DB(매 요청 로드한 role) 기준이다.
// 소셜 가입의 자리표시 이메일(@social.matjari.invalid)은 원문을 내리지 않고 email=null, emailPlaceholder=true 로 알린다.
public record UserResponse(Long id, String email, String nickname, UserRole role,
                           boolean emailPlaceholder, boolean passwordLoginEnabled) {

    private static final String PLACEHOLDER_SUFFIX = "@social.matjari.invalid";

    public static UserResponse from(User user) {
        boolean placeholder = user.getEmail() != null && user.getEmail().endsWith(PLACEHOLDER_SUFFIX);
        return new UserResponse(user.getId(), placeholder ? null : user.getEmail(), user.getNickname(),
                user.getRole(), placeholder, user.isPasswordLoginEnabled());
    }
}
