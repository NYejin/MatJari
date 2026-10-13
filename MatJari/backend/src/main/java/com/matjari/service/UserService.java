package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.dto.response.UserResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 정보 조회·수정 (FR-01 회원 관리) */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int NICKNAME_MIN = 2;
    private static final int NICKNAME_MAX = 20;

    private final UserRepository userRepository;

    /**
     * 인증 주체의 userId로 내 정보를 조회한다.
     * 토큰은 유효했지만 그 사이 사용자가 삭제된 경우 AuthenticationException을 던진다
     * → GlobalExceptionHandler가 rethrow → ExceptionTranslationFilter → EntryPoint 401.
     */
    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    /**
     * 닉네임을 바꾼다. 규칙은 가입과 같다(앞뒤 공백 제거 JS trim 규칙 후 2~20자, 제어·제로폭·채움 문자 거부, 중복 허용).
     * 같은 값이면 아무것도 쓰지 않고 그대로 돌려준다(멱등). 행 잠금은 쓰지 않는다 — 마지막 쓰기가 이기는 단일 컬럼 수정이다.
     */
    @Transactional
    public UserResponse updateNickname(Long userId, String rawNickname) {
        String nickname = rawNickname == null ? null : AuthInputNormalizer.normalizeNickname(rawNickname);
        if (nickname == null || nickname.isEmpty()) {
            throw new FieldValidationException("nickname", "닉네임을 입력해주세요.");
        }
        if (nickname.length() < NICKNAME_MIN || nickname.length() > NICKNAME_MAX) {
            throw new FieldValidationException("nickname", "닉네임은 2~20자로 입력해주세요.");
        }
        if (AuthInputNormalizer.containsInvisibleChar(nickname)) {
            throw new FieldValidationException("nickname", "닉네임에 사용할 수 없는 문자가 포함되어 있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
        if (!nickname.equals(user.getNickname())) {
            user.changeNickname(nickname);
        }
        return UserResponse.from(user);
    }
}
