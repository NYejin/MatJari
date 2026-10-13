package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;

import java.io.Serializable;

/**
 * 희망 회차와 사용자 우선순위 (1이 가장 높음). PK = (request_id, performance_session_id).
 * 연관 엔티티 없이 id 로만 참조한다(FK는 DB가 보장). 같은 공연의 회차인지는 서비스가 검사한다.
 * 키를 직접 지정하는 엔티티라 Persistable.isNew 를 구현해 save 가 행별 SELECT(merge) 없이 바로 INSERT 하게 한다.
 */
@Entity
@Table(name = "exchange_want_session")
@Getter
@NoArgsConstructor
public class ExchangeWantSession implements Persistable<ExchangeWantSession.Key> {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private Short priority;

    /** 새로 만든 인스턴스만 true. DB에서 읽거나 저장한 뒤에는 false. */
    @Transient
    private boolean newEntity = true;

    @Override
    public Key getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public static ExchangeWantSession create(Long requestId, Long sessionId, int priority) {
        ExchangeWantSession w = new ExchangeWantSession();
        w.id = new Key(requestId, sessionId);
        w.priority = (short) priority;
        return w;
    }

    @Embeddable
    public record Key(
            @Column(name = "request_id") Long requestId,
            @Column(name = "performance_session_id") Long sessionId) implements Serializable {
    }
}
