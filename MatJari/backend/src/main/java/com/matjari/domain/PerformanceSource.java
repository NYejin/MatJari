package com.matjari.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Collate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공연에 달린 예매처 링크 한 개 (V32). 한 공연을 여러 예매처(멜론·NOL·티켓링크·YES24)가 나눠 파는 경우를 한 공연으로 묶는다.
 * 링크 키(sourceKey)는 전체에서 유일하다(같은 링크는 한 공연에만 달린다). 대표 링크(performance.source_key)도 이 테이블에 한 행으로 있다.
 * 행은 갱신하지 않고 추가·삭제만 한다. 공연·사용자는 FK 이지만 연관 객체 대신 id 만 들고 있다(지연 로딩 프록시 불필요).
 */
@Entity
@Table(name = "performance_source")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class PerformanceSource {

    public static final String UNIQUE_SOURCE_KEY = "uk_performance_source_source_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "performance_id", nullable = false, updatable = false)
    private Long performanceId;

    /** performance.source_key 와 같은 규칙(정규화 링크), utf8mb4_bin. 전체에서 유일. */
    @Collate("utf8mb4_bin")
    @Column(name = "source_key", nullable = false, updatable = false, length = Performance.SOURCE_KEY_MAX_LENGTH)
    private String sourceKey;

    @Column(name = "source_url", nullable = false, updatable = false, length = Performance.SOURCE_URL_MAX_LENGTH)
    private String sourceUrl;

    @Column(name = "added_by_id", updatable = false)
    private Long addedById;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PerformanceSource create(Long performanceId, String sourceKey, String sourceUrl, Long addedById) {
        if (performanceId == null || sourceKey == null || sourceKey.isBlank() || sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("performanceId, sourceKey, sourceUrl 은 필수입니다.");
        }
        PerformanceSource s = new PerformanceSource();
        s.performanceId = performanceId;
        s.sourceKey = sourceKey;
        s.sourceUrl = sourceUrl;
        s.addedById = addedById;
        return s;
    }
}
