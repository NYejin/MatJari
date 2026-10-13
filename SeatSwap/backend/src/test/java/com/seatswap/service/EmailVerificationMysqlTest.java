package com.seatswap.service;

import com.seatswap.service.mail.EmailSender;
import com.seatswap.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가입 전 이메일 인증번호(V19)의 실제 MySQL 통합 테스트(발송 → 가입, 틀린 시도 횟수, 만료, 재발송 대기, 1회용, 발송 실패).
 * SEATSWAP_IT_* 환경변수가 없으면 건너뛴다. 메일 발송기는 목으로 바꿔 코드를 가로챈다. IP/이메일 한도는 넉넉히 열어 두고 한도 자체는 단위로 확인한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(properties = {"seatswap.email-verification.ip-per10-min=100000", "seatswap.email-verification.email-per10-min=100000",
        "seatswap.email-verification.global-per-hour=100000"})
@AutoConfigureMockMvc
// 이 클래스만의 컨텍스트(목 발송기·한도 설정)라 끝나면 닫아 커넥션 풀을 돌려준다(MySQL 기본 최대 연결 수 보호)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationMysqlTest {

    private static final String PASSWORD = "password123";
    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private EmailSender emailSender;

    @BeforeEach
    void cleanTables() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE email_verification");
        jdbc.execute("TRUNCATE TABLE users");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        reset(emailSender);
    }

    private ResultActions sendCode(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/signup/email-code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions signup(String email, String nickname, String code) throws Exception {
        String codePart = code == null ? "" : ",\"emailCode\":\"" + code + "\"";
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + nickname + "\"" + codePart + "}"));
    }

    /** 마지막으로 보낸 메일 본문에서 6자리 코드를 읽는다. */
    private String lastCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(anyString(), anyString(), text.capture());
        Matcher m = CODE.matcher(text.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void expireCooldown(String email) {
        jdbc.update("UPDATE email_verification SET sent_at = ? WHERE email = ?", LocalDateTime.now().minusMinutes(5), email);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void 인증번호를_받아_가입하면_계정이_만들어지고_코드는_한_번만_쓸_수_있다() throws Exception {
        sendCode("New.User@Example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(600)).andExpect(jsonPath("$.resendAfterSeconds").value(60));
        verify(emailSender).send(eq("new.user@example.com"), anyString(), any());
        String code = lastCode();
        // 평문 코드는 저장하지 않는다: 64자 16진 해시
        String hash = jdbc.queryForObject("SELECT code_hash FROM email_verification WHERE email = 'new.user@example.com'", String.class);
        assertThat(hash).hasSize(64).doesNotContain(code);

        signup("new.user@example.com", "새회원", code).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM users WHERE email = 'new.user@example.com'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM email_verification")).isZero();

        // 같은 코드로 다른 계정(같은 이메일 재가입 시도 포함)을 만들 수 없다
        signup("new.user@example.com", "또가입", code).andExpect(status().isBadRequest());
    }

    @Test
    void 인증번호_없이_또는_다른_이메일의_코드로는_가입할_수_없다() throws Exception {
        sendCode("a@example.com").andExpect(status().isOk());
        String code = lastCode();
        signup("a@example.com", "회원", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailCode").value("이메일로 받은 인증번호를 입력해주세요."));
        signup("a@example.com", "회원", "12ab56").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailCode").value("인증번호는 숫자 6자리예요."));
        // 인증받지 않은 이메일은 남의 코드를 써도 실패
        signup("b@example.com", "회원", code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailCode").exists());
        assertThat(count("SELECT COUNT(*) FROM users")).isZero();
        // 형식 오류(빈 값·비숫자)는 시도 횟수를 쓰지 않는다
        assertThat(count("SELECT attempts FROM email_verification WHERE email = 'a@example.com'")).isZero();
    }

    @Test
    void 틀린_시도는_5번까지이고_넘으면_맞는_코드도_거부하며_새_코드를_받으면_다시_된다() throws Exception {
        sendCode("c@example.com").andExpect(status().isOk());
        String code = lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            signup("c@example.com", "회원", wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.emailCode").value("인증번호가 일치하지 않아요."));
        }
        assertThat(count("SELECT attempts FROM email_verification WHERE email = 'c@example.com'")).isEqualTo(5);
        // 6번째는 맞는 코드라도 거부
        signup("c@example.com", "회원", code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailCode").value("인증번호가 만료되었거나 입력 횟수를 넘었어요. 인증번호를 다시 받아 주세요."));
        assertThat(count("SELECT COUNT(*) FROM users")).isZero();

        expireCooldown("c@example.com");
        reset(emailSender);
        sendCode("c@example.com").andExpect(status().isOk());
        assertThat(count("SELECT attempts FROM email_verification WHERE email = 'c@example.com'")).isZero();
        signup("c@example.com", "회원", lastCode()).andExpect(status().isOk());
    }

    @Test
    void 만료된_코드는_거부한다() throws Exception {
        sendCode("d@example.com").andExpect(status().isOk());
        String code = lastCode();
        jdbc.update("UPDATE email_verification SET expires_at = ? WHERE email = 'd@example.com'", LocalDateTime.now().minusSeconds(1));
        signup("d@example.com", "회원", code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailCode").value("인증번호가 만료되었거나 입력 횟수를 넘었어요. 인증번호를 다시 받아 주세요."));
        assertThat(count("SELECT COUNT(*) FROM users")).isZero();
    }

    @Test
    void 방금_보낸_이메일은_대기시간_동안_다시_보낼_수_없고_이미_가입된_이메일은_받을_수_없다() throws Exception {
        sendCode("e@example.com").andExpect(status().isOk());
        sendCode("e@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CODE_COOLDOWN"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"));
        assertThat(count("SELECT COUNT(*) FROM email_verification")).isEqualTo(1);

        signup("e@example.com", "회원", lastCode()).andExpect(status().isOk());
        sendCode("e@example.com").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("이미 가입된 이메일입니다."));
        // 예약 도메인과 형식 오류는 보내지 않는다
        sendCode("x@social.seatswap.invalid").andExpect(status().isBadRequest());
        sendCode("not-an-email").andExpect(status().isBadRequest());
    }

    @Test
    void 메일_발송에_실패하면_503이고_행을_지워_바로_다시_요청할_수_있다() throws Exception {
        doThrow(new EmailSender.EmailSendException("fail", new RuntimeException("boom")))
                .when(emailSender).send(anyString(), anyString(), anyString());
        sendCode("f@example.com").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"));
        assertThat(count("SELECT COUNT(*) FROM email_verification")).isZero();
        reset(emailSender);
        sendCode("f@example.com").andExpect(status().isOk());
    }

    @Test
    void 인증을_통과한_뒤_다른_이유로_가입이_실패하면_코드는_되살아난다() throws Exception {
        sendCode("g@example.com").andExpect(status().isOk());
        String code = lastCode();
        // 닉네임 오류는 인증번호 확인 전에 걸러져 시도 횟수를 쓰지 않는다
        signup("g@example.com", "가", code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.nickname").exists());
        assertThat(count("SELECT attempts FROM email_verification WHERE email = 'g@example.com'")).isZero();
        signup("g@example.com", "정상닉네임", code).andExpect(status().isOk());
    }

    @Test
    void 슬라이딩_윈도_한도는_넘으면_429이고_시간이_지나면_풀린다() {
        EmailVerificationService.Limiter limiter = new EmailVerificationService.Limiter();
        for (int i = 0; i < 3; i++) {
            limiter.acquire("k", 3, 60_000, 1_000L + i);
        }
        assertThatThrownBy(() -> limiter.acquire("k", 3, 60_000, 2_000L))
                .isInstanceOf(EmailVerificationService.EmailCodeRateLimitedException.class)
                .satisfies(e -> assertThat(((EmailVerificationService.EmailCodeRateLimitedException) e).getRetryAfterSeconds()).isPositive());
        limiter.acquire("other", 3, 60_000, 2_000L);
        limiter.acquire("k", 3, 60_000, 61_001L);
    }
}
