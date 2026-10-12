package com.seatswap.service.social;

import com.seatswap.config.WebSocketConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 기동 시 소셜 설정을 검증한다(활성 제공자가 없으면 건너뜀). frontend-base-url 은 allowed-origins 에 있어야 한다. */
@Component
public class SocialPropertiesValidator {

    private final SocialProperties properties;
    private final String[] allowedOrigins;

    public SocialPropertiesValidator(SocialProperties properties,
                                     @Value("${seatswap.websocket.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.properties = properties;
        this.allowedOrigins = allowedOrigins;
    }

    @PostConstruct
    void validate() {
        if (!properties.anyEnabled()) {
            return;
        }
        properties.validate(WebSocketConfig.validatedOrigins(allowedOrigins));
    }
}
