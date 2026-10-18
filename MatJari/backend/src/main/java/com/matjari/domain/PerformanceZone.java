package com.matjari.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Collate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공연별 좌석 구역 (V21). 공연 등록 때 이름(+선택적 모양)을 함께 저장하고 이후 수정하지 않는다(행은 갱신하지 않는다).
 * 티켓·교환 조건은 이 테이블을 참조하지 않는다(구역은 입력 보조 목록일 뿐이다).
 * 같은 name_key 는 한 행이며 모양은 다각형 배열(조각 1~8개, 조각마다 꼭짓점 3~40개, 좌표 0~1000 정수)이다.
 * 검증·정리는 PerformanceZoneSanitizer 가 먼저 한다(여기는 최후 방어선만).
 */
@Entity
@Table(name = "performance_zone")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class PerformanceZone {

    public static final int NAME_MAX_LENGTH = 30;
    public static final int NAME_KEY_MAX_LENGTH = 50;
    public static final String UNIQUE_NAME_KEY = "uk_performance_zone_name_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id", nullable = false, updatable = false)
    private Performance performance;

    @Column(name = "name", nullable = false, updatable = false, length = NAME_MAX_LENGTH)
    private String name;

    /** ticket.zone_key 와 같은 규칙(SeatKeyNormalizer.zoneKey), utf8mb4_bin. */
    @Collate("utf8mb4_bin")
    @Column(name = "name_key", nullable = false, updatable = false, length = NAME_KEY_MAX_LENGTH)
    private String nameKey;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private short sortOrder;

    /** [[[x,y],...],...] 0~1000 정규화 좌표. 모양이 없으면 NULL. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shape_polygons", columnDefinition = "json", updatable = false)
    private List<List<List<Integer>>> shapePolygons;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PerformanceZone create(Performance performance, String name, String nameKey, int sortOrder,
                                         List<List<List<Integer>>> shapePolygons) {
        if (performance == null) {
            throw new IllegalArgumentException("performance는 필수입니다.");
        }
        if (name == null || name.isBlank() || name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("구역 이름은 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        if (nameKey == null || nameKey.isEmpty() || nameKey.length() > NAME_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("구역 키는 1~" + NAME_KEY_MAX_LENGTH + "자여야 합니다.");
        }
        if (sortOrder < 0 || sortOrder > Short.MAX_VALUE) {
            throw new IllegalArgumentException("sortOrder 범위 오류");
        }
        PerformanceZone zone = new PerformanceZone();
        zone.performance = performance;
        zone.name = name;
        zone.nameKey = nameKey;
        zone.sortOrder = (short) sortOrder;
        zone.shapePolygons = shapePolygons == null || shapePolygons.isEmpty() ? null : shapePolygons;
        return zone;
    }
}
