package com.matjari.service.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.matjari.domain.SocialProvider;
import com.matjari.dto.request.AuthInputNormalizer;

/** 제공자별 userinfo JSON 에서 (식별자, 검증된 이메일, 닉네임)을 뽑는다. 식별자가 없거나 비정상이면 {@link SocialProviderClient.ProviderException}. */
final class SocialProfileParser {

    private static final int MAX_ID_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 255;

    private SocialProfileParser() {
    }

    static SocialProfile parse(SocialProvider provider, JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new SocialProviderClient.ProviderException("userinfo-shape");
        }
        String id;
        String email = null;
        String nickname;
        switch (provider) {
            case GOOGLE -> {
                id = text(root.get("sub"));
                if (isTrue(root.get("email_verified"))) {
                    email = text(root.get("email"));
                }
                nickname = text(root.get("name"));
            }
            case KAKAO -> {
                id = text(root.get("id"));
                JsonNode account = root.path("kakao_account");
                if (isTrue(account.get("is_email_valid")) && isTrue(account.get("is_email_verified"))) {
                    email = text(account.get("email"));
                }
                nickname = text(account.path("profile").get("nickname"));
            }
            case NAVER -> {
                JsonNode response = root.path("response");
                id = text(response.get("id"));
                // 네이버는 응답에 이메일 검증 여부 표시가 없고 공식 문서에서 소유 확인을 확인하지 못했다(2026-10-13 조사).
                // 검증된 이메일로 볼 수 없으므로 쓰지 않는다 -> 자리표시 이메일로 가입하고 EMAIL_EXISTS 판정에도 쓰지 않는다.
                nickname = text(response.get("nickname"));
                if (nickname == null) {
                    nickname = text(response.get("name"));
                }
            }
            default -> throw new SocialProviderClient.ProviderException("provider");
        }
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
            throw new SocialProviderClient.ProviderException("userinfo-id");
        }
        return new SocialProfile(id, validEmailOrNull(email), nickname);
    }

    private static String validEmailOrNull(String email) {
        if (email == null) {
            return null;
        }
        String normalized = AuthInputNormalizer.normalizeEmail(email);
        if (normalized.isEmpty() || normalized.length() > MAX_EMAIL_LENGTH
                || normalized.indexOf('@') <= 0 || normalized.indexOf('@') != normalized.lastIndexOf('@')
                || normalized.indexOf('@') == normalized.length() - 1) {
            return null;
        }
        return normalized;
    }

    /** 문자열 또는 숫자 노드만 텍스트로(객체·배열·null 은 null). */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            return node.asText();
        }
        return null;
    }

    private static boolean isTrue(JsonNode node) {
        if (node == null) {
            return false;
        }
        return node.isBoolean() ? node.asBoolean() : node.isTextual() && "true".equalsIgnoreCase(node.asText());
    }
}
