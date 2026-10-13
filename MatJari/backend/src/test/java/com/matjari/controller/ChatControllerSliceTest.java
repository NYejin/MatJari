package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.response.ChatDtos;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.ChatRateLimiter;
import com.matjari.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/exchange/matches/{id}/messages — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class ChatControllerSliceTest {

    private static final String BASE = "/api/exchange/matches/50/messages";
    private static final String BODY = "{\"content\":\"안녕\",\"clientMessageId\":\"client-msg-0001\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer good");
    }

    private static ChatDtos.Message message(long id) {
        return new ChatDtos.Message(id, 1L, "안녕", "client-msg-0001", LocalDateTime.of(2026, 10, 11, 12, 0, 5));
    }

    private static final String MESSAGE_JSON =
            "{\"id\":700,\"senderId\":1,\"content\":\"안녕\",\"clientMessageId\":\"client-msg-0001\",\"createdAt\":\"2026-10-11T12:00:05\"}";

    @Test
    void 세_엔드포인트_모두_로그인이_필요하다() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/read").contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":1}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(chatService);
    }

    @Test
    void 전송은_새_메시지면_201_재전송이면_200이고_본문이_같다() throws Exception {
        when(chatService.send(1L, 50L, "안녕", "client-msg-0001")).thenReturn(new ChatService.SendResult(message(700), true));
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json(MESSAGE_JSON, true));

        when(chatService.send(1L, 50L, "안녕", "client-msg-0001")).thenReturn(new ChatService.SendResult(message(700), false));
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(content().json(MESSAGE_JSON, true));
    }

    @Test
    void 전송_본문_검증은_400이다() throws Exception {
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"content\":\"메시지를 입력해주세요.\",\"clientMessageId\":\"clientMessageId가 필요합니다.\"}", true));
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"a\",\"clientMessageId\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(post("/api/exchange/matches/abc/messages")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verify(chatService, never()).send(anyLong(), anyLong(), any(), any());

        doThrow(new FieldValidationException("content", "메시지는 1000자 이하로 입력해주세요.")).when(chatService).send(any(), any(), any(), any());
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"content\":\"메시지는 1000자 이하로 입력해주세요.\"}", true));
    }

    @Test
    void 비참여자는_404_종료된_매칭_전송은_409_속도_제한은_429다() throws Exception {
        doThrow(new NotFoundException("매칭을 찾을 수 없습니다.")).when(chatService).send(any(), any(), any(), any());
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"매칭을 찾을 수 없습니다.\"}", true));

        doThrow(new ConflictException("종료된 채팅에는 메시지를 보낼 수 없어요",
                Map.of("code", "MATCH_STATE_CONFLICT", "status", "CANCELED", "action", "SEND"))).when(chatService).send(any(), any(), any(), any());
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"종료된 채팅에는 메시지를 보낼 수 없어요\",\"code\":\"MATCH_STATE_CONFLICT\","
                        + "\"status\":\"CANCELED\",\"action\":\"SEND\"}", true));

        doThrow(new ChatRateLimiter.ChatRateLimitedException(3)).when(chatService).send(any(), any(), any(), any());
        mockMvc.perform(auth(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(content().json("{\"code\":\"CHAT_RATE_LIMITED\",\"message\":\"메시지를 너무 빨리 보내고 있어요. 잠시 후 다시 시도해주세요.\","
                        + "\"retryAfterSeconds\":3}", true));
    }

    @Test
    void 목록은_커서와_크기를_서비스에_넘기고_페이지_모양으로_응답한다() throws Exception {
        when(chatService.list(1L, 50L, 700L, null, 30)).thenReturn(new ChatDtos.MessagePage(List.of(message(699), message(700)), true, 690L, 695L));

        mockMvc.perform(auth(get(BASE)).param("before", "700").param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"messages\":[{\"id\":699,\"senderId\":1,\"content\":\"안녕\",\"clientMessageId\":\"client-msg-0001\","
                        + "\"createdAt\":\"2026-10-11T12:00:05\"},{\"id\":700,\"senderId\":1,\"content\":\"안녕\",\"clientMessageId\":\"client-msg-0001\","
                        + "\"createdAt\":\"2026-10-11T12:00:05\"}],\"hasMore\":true,\"myLastReadMessageId\":690,\"counterpartLastReadMessageId\":695}", true));

        when(chatService.list(1L, 50L, null, 600L, null)).thenReturn(new ChatDtos.MessagePage(List.of(), false, 0L, 0L));
        mockMvc.perform(auth(get(BASE)).param("after", "600")).andExpect(status().isOk());
        verify(chatService).list(1L, 50L, null, 600L, null);

        mockMvc.perform(auth(get(BASE)).param("before", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void 목록의_비참여자는_404_잘못된_커서는_400이다() throws Exception {
        doThrow(new NotFoundException("매칭을 찾을 수 없습니다.")).when(chatService).list(any(), any(), any(), any(), any());
        mockMvc.perform(auth(get(BASE))).andExpect(status().isNotFound());

        doThrow(new FieldValidationException("before", "before와 after는 함께 쓸 수 없습니다.")).when(chatService).list(any(), any(), any(), any(), any());
        mockMvc.perform(auth(get(BASE)).param("before", "5").param("after", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"before\":\"before와 after는 함께 쓸 수 없습니다.\"}", true));
    }

    @Test
    void 읽음은_204이고_본문과_참여자를_검증한다() throws Exception {
        mockMvc.perform(auth(put(BASE + "/read")).contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":700}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(chatService).markRead(eq(1L), eq(50L), eq(700L));

        mockMvc.perform(auth(put(BASE + "/read")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"lastReadMessageId\":\"lastReadMessageId가 필요합니다.\"}", true));
        mockMvc.perform(auth(put(BASE + "/read")).contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":-1}"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.doThrow(new NotFoundException("매칭을 찾을 수 없습니다.")).when(chatService).markRead(any(), eq(51L), any());
        mockMvc.perform(auth(put("/api/exchange/matches/51/messages/read")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastReadMessageId\":1}"))
                .andExpect(status().isNotFound());
    }
}
