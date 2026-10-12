package com.seatswap.exception;

import com.seatswap.service.ChatRateLimiter;
import com.seatswap.service.linkinfo.PerformancePreviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String SERVER_ERROR_MESSAGE = "서버 오류가 발생했습니다.";
    public static final String DATA_CONFLICT_MESSAGE = "요청이 다른 변경과 충돌했습니다. 다시 시도해주세요.";

    // 한 필드에 제약이 여러 개 걸려 동시에 실패할 때(예: 빈 값 → NotBlank + Size) 어떤 메시지를
    // 보여줄지 결정적으로 고르기 위한 우선순위. 값이 작을수록 우선.
    private static final Set<String> REQUIRED_CODES = Set.of("NotNull", "NotBlank", "NotEmpty");

    // 필드에 귀속되는 비즈니스 오류(이메일 중복 등) — @Valid 실패와 동일한 {field: message} 포맷
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<Map<String, String>> handleFieldValidationException(FieldValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getErrors());
    }

    // 409 — {"message": ..., ...details} (예: {"message":"이미 등록된 공연입니다.","performanceId":3})
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflictException(ConflictException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        e.getDetails().forEach(body::putIfAbsent);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 서비스에서 제약 이름으로 분류하지 못한 무결성 위반 (동시 변경 레이스 등) → 409 일반 문구.
     * 이메일 중복(AuthService)·공연 중복·회차 중복은 각 서비스가 제약 이름으로 먼저 잡아 전용 응답으로 바꾸므로
     * 여기까지 오지 않는다. 원인은 로그에만 남긴다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Unclassified data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", DATA_CONFLICT_MESSAGE));
    }

    // 429 — 채팅 전송 속도 제한: {"code":"CHAT_RATE_LIMITED","message":..,"retryAfterSeconds":n} + Retry-After 헤더
    @ExceptionHandler(ChatRateLimiter.ChatRateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleChatRateLimited(ChatRateLimiter.ChatRateLimitedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds())).body(body);
    }

    // 429 — 링크 미리보기 속도 제한: {"code":"PREVIEW_RATE_LIMITED","message":..,"retryAfterSeconds":n} + Retry-After 헤더
    @ExceptionHandler(PerformancePreviewService.PreviewRateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handlePreviewRateLimited(
            PerformancePreviewService.PreviewRateLimitedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds())).body(body);
    }

    // 행 잠금 대기 초과·교착(CannotAcquireLockException 포함) -> 503 일반 문구. 잠시 뒤 다시 시도하면 된다
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("Lock acquisition failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "BUSY", "message", "요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해주세요."));
    }

    // multipart 업로드 크기 초과 (spring.servlet.multipart.max-file-size) — 컨트롤러에 들어오기 전에 던져진다
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("code", "IMAGE_TOO_LARGE", "message", "이미지는 5MB 이하만 올릴 수 있습니다."));
    }

    // 410 - 처리 후 삭제된 좌석 인증 사진: {"code":"IMAGE_DELETED","message":..}
    @ExceptionHandler(com.seatswap.service.SeatClaimService.ImageDeletedException.class)
    public ResponseEntity<Map<String, String>> handleImageDeleted(com.seatswap.service.SeatClaimService.ImageDeletedException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("code", e.getCode(), "message", e.getMessage()));
    }

    // 403 — 이용 정지: {"message","code":"ACCOUNT_SUSPENDED","until"} (로그인·토큰 재발급 경로. 보안 필터 단계는 SecurityErrorResponseWriter 가 같은 본문)
    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountSuspended(AccountSuspendedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.toBody());
    }

    // 403 — {"message": ...} (Security의 AccessDeniedHandler는 문구를 바꿀 수 없어 업무 규칙 위반은 이 예외로 처리)
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbiddenException(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFoundException(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    // 422 — {"code": ..., "message": ..., ...details} (예: 활성 티켓 상한 초과, 희망 좌석 상한 초과의 count/limit)
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRuleException(BusinessRuleException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        e.getDetails().forEach(body::putIfAbsent);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(SeatSwapException.class)
    public ResponseEntity<Map<String, String>> handleSeatSwapException(SeatSwapException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    // @Valid 바인딩 실패 시 필드별 오류 메시지 반환 (필드당 메시지 1개)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, FieldError> picked = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            picked.merge(fieldError.getField(), fieldError, (current, candidate) ->
                    constraintPriority(candidate) < constraintPriority(current) ? candidate : current);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        picked.forEach((field, fieldError) -> errors.put(field, fieldError.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    // 경로/쿼리 파라미터 타입 불일치 (예: /api/performances/abc) — 처리하지 않으면 아래 Exception 핸들러에서 500이 된다
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "요청 형식이 올바르지 않습니다."));
    }

    // 깨진 JSON, 타입 불일치 등 요청 본문을 읽을 수 없는 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "요청 형식이 올바르지 않습니다."));
    }

    /**
     * Spring Security 예외는 여기서 처리하지 않고 다시 던진다.
     * 컨트롤러/메서드 보안에서 발생한 인증·인가 예외가 아래 Exception 핸들러에 잡혀 500으로 둔갑하지 않도록,
     * Security의 ExceptionTranslationFilter(→ AuthenticationEntryPoint / AccessDeniedHandler)에 맡긴다.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    /**
     * 그 외 모든 예외. 스택트레이스는 로그에만 남기고 응답에는 노출하지 않는다.
     * 단, ErrorResponse 구현체(Spring MVC 표준 예외, ResponseStatusException 등)는 원래 상태코드를
     * 4xx/5xx 모두 유지하고, 메시지는 영문 detail 대신 한국어 일반 문구로 대체한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("Server error response ({})", status.value(), e);
            } else {
                log.debug("Client error response ({}): {}", status.value(), e.getMessage());
            }
            return ResponseEntity.status(status).body(Map.of("message", messageFor(status)));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", SERVER_ERROR_MESSAGE));
    }

    private static String messageFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return SERVER_ERROR_MESSAGE;
        }
        return switch (status.value()) {
            case 404 -> "요청한 리소스를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 방식입니다.";
            case 415 -> "지원하지 않는 형식입니다.";
            default -> "잘못된 요청입니다.";
        };
    }

    private static int constraintPriority(FieldError fieldError) {
        String code = fieldError.getCode();
        if (code != null && REQUIRED_CODES.contains(code)) {
            return 0;
        }
        if ("Size".equals(code)) {
            return 1;
        }
        return 2;
    }
}
