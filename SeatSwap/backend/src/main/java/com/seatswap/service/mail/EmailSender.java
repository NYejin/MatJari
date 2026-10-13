package com.seatswap.service.mail;

/** 메일 한 통을 보낸다. 구현은 설정 seatswap.mail.mode 로 고른다(log: 개발용 로그 출력, smtp: 실제 발송). */
public interface EmailSender {

    /** 실패하면 {@link EmailSendException}. */
    void send(String to, String subject, String text);

    class EmailSendException extends RuntimeException {
        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
