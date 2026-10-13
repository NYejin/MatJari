package com.matjari.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInputNormalizerTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void emailIsStrippedLikeJsTrimAndLowercased() {
        // 전각 공백, BOM, NBSP, LS, NNBSP(U+202F) 모두 JS trim 대상
        String raw = "\u3000\uFEFF  Foo@Test.COM\u00A0\u2028\u202F";
        assertThat(AuthInputNormalizer.normalizeEmail(raw)).isEqualTo("foo@test.com");
    }

    @Test
    void zeroWidthSpaceIsNotTrimmed() {
        // U+200B(Cf)는 JS trim 대상이 아니다 -> 남는다 (닉네임은 별도 invisible 검사에서 거부)
        assertThat(AuthInputNormalizer.normalizeNickname("\u200B닉네임")).isEqualTo("\u200B닉네임");
    }

    @Test
    void nullStaysNull() {
        assertThat(AuthInputNormalizer.normalizeEmail(null)).isNull();
        assertThat(AuthInputNormalizer.normalizeNickname(null)).isNull();
    }

    @Test
    void detectsInvisibleCharacters() {
        assertThat(AuthInputNormalizer.containsInvisibleChar("정상닉네임")).isFalse();
        assertThat(AuthInputNormalizer.containsInvisibleChar("ab\u200Bcd")).isTrue();     // Cf
        assertThat(AuthInputNormalizer.containsInvisibleChar("ab\u0007cd")).isTrue();     // Cc
        assertThat(AuthInputNormalizer.containsInvisibleChar("\u3164\u3164")).isTrue(); // HANGUL FILLER
        assertThat(AuthInputNormalizer.containsInvisibleChar("a\u115Fb")).isTrue();
        assertThat(AuthInputNormalizer.containsInvisibleChar("a\u1160b")).isTrue();
        assertThat(AuthInputNormalizer.containsInvisibleChar("a\uFFA0b")).isTrue();
    }

    @Test
    void signupValidationUsesNormalizedValuesAndKoreanMessages() {
        SignupRequest ok = new SignupRequest("  A@B.COM ", "password123", "\u3000닉네임\u3000");
        assertThat(ok.email()).isEqualTo("a@b.com");
        assertThat(ok.nickname()).isEqualTo("닉네임");
        assertThat(validator.validate(ok)).isEmpty();

        Set<ConstraintViolation<SignupRequest>> bad =
                validator.validate(new SignupRequest("x", "short", "\u3000가\u3000"));
        assertThat(bad).extracting(ConstraintViolation::getMessage)
                .contains("올바른 이메일 형식이 아닙니다.", "비밀번호는 8~64자로 입력해주세요.", "닉네임은 2~20자로 입력해주세요.");
    }

    @Test
    void whitespaceOnlyPasswordIsRejectedByNotBlank() {
        Set<ConstraintViolation<SignupRequest>> v =
                validator.validate(new SignupRequest("a@b.com", "          ", "닉네임"));
        assertThat(v).extracting(ConstraintViolation::getMessage).contains("비밀번호를 입력해주세요.");
    }

    @Test
    void toStringMasksPassword() {
        assertThat(new SignupRequest("a@b.com", "secret-pass", "닉네임").toString()).doesNotContain("secret-pass");
        assertThat(new LoginRequest("a@b.com", "secret-pass").toString()).doesNotContain("secret-pass");
    }
}
