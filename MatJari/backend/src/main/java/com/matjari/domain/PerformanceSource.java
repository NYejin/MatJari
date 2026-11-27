package com.matjari.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Collate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 공연에 달린 예매처 링크 한 개 (V32). 한 공연을 여러 예매처(멜론·NOL·티켓링크·YES24)가 나눠 파는 경우를 한 공연으로 묶는다.
 * 링크 키(sourceKey)는 전체에서 유일하다(같은 링크는 한 공연에만 달린다). 대표 링크(performance.source_key)도 이 테이블에 한 행으로 있다.
 * 링크 자체(키·주소·등록자)는 갱신하지 않고 추가·삭제만 한다. 공연·사용자는 FK 이지만 연관 객체 대신 id 만 들고 있다(지연 로딩 프록시 불필요).
 *
 * 지도 정보(V34): 추가 링크(대표가 아닌 행)는 그 링크 상세 이미지에서 읽은 구역 지도를 따로 둘 수 있다. mapStatus 는 NONE/PENDING/READY/FAILED,
 * seatmapAspect·seatmapImage* 는 그 지도의 비율과 원본 좌석표 이미지 위치(Performance 의 V31 컬럼과 같은 뜻). 지도 컬럼은 인덱스·FK 에 들어 있지 않다.
 * 대표 링크 행은 지도 정보를 채우지 않는다(대표 지도는 Performance 쪽).
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

    /** 지도 상태(V34). 대표 링크 행은 항상 NONE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "map_status", nullable = false, length = 16)
    private MapStatus mapStatus = MapStatus.NONE;

    @Column(name = "map_updated_at")
    private LocalDateTime mapUpdatedAt;

    @Column(name = "seatmap_aspect", precision = 6, scale = 3)
    private BigDecimal seatmapAspect;

    @Column(name = "seatmap_image_url", length = Performance.SOURCE_URL_MAX_LENGTH)
    private String seatmapImageUrl;

    @Column(name = "seatmap_crop_left", precision = 8, scale = 6)
    private BigDecimal seatmapCropLeft;

    @Column(name = "seatmap_crop_top", precision = 8, scale = 6)
    private BigDecimal seatmapCropTop;

    @Column(name = "seatmap_crop_width", precision = 8, scale = 6)
    private BigDecimal seatmapCropWidth;

    @Column(name = "seatmap_crop_height", precision = 8, scale = 6)
    private BigDecimal seatmapCropHeight;

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

    /** 추가 링크: 지도를 읽는 중(PENDING)으로 만든다. 새 링크를 INSERT 하는 트랜잭션에서 쓴다(커밋 뒤 이벤트가 읽기를 시작). */
    public static PerformanceSource createAdditional(Long performanceId, String sourceKey, String sourceUrl, Long addedById,
                                                     LocalDateTime now) {
        PerformanceSource s = create(performanceId, sourceKey, sourceUrl, addedById);
        s.mapStatus = MapStatus.PENDING;
        s.mapUpdatedAt = now;
        return s;
    }

    /**
     * 지도 읽기 결과를 반영한다(공연 행을 잠근 트랜잭션에서). image 값은 다섯 개가 함께 null 이거나 함께 값이어야 한다(서비스가 검증한 값만 넘긴다).
     * aspect·image 는 상태가 READY 일 때만 의미가 있고 다른 상태에서는 비운다.
     */
    public void applyMap(MapStatus status, BigDecimal aspect, String imageUrl, BigDecimal left, BigDecimal top,
                         BigDecimal width, BigDecimal height, LocalDateTime now) {
        if (status == null) {
            throw new IllegalArgumentException("status는 필수입니다.");
        }
        boolean ready = status == MapStatus.READY;
        this.mapStatus = status;
        this.mapUpdatedAt = now;
        this.seatmapAspect = ready ? aspect : null;
        boolean hasImage = ready && imageUrl != null && left != null && top != null && width != null && height != null;
        this.seatmapImageUrl = hasImage ? imageUrl : null;
        this.seatmapCropLeft = hasImage ? left : null;
        this.seatmapCropTop = hasImage ? top : null;
        this.seatmapCropWidth = hasImage ? width : null;
        this.seatmapCropHeight = hasImage ? height : null;
    }

    /** 지도 정보를 모두 지운다(상태 NONE). */
    public void clearMap(LocalDateTime now) {
        applyMap(MapStatus.NONE, null, null, null, null, null, null, now);
    }

    public enum MapStatus { NONE, PENDING, READY, FAILED }
}
