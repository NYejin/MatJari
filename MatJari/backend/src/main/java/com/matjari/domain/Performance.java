package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Collate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공연 (FR-02). 로그인한 사용자 누구나 티켓팅 사이트 링크(sourceUrl)를 입력해 등록한다.
 * 날짜·시간은 회차(PerformanceSession)로 분리했다 — Performance 1:N PerformanceSession.
 *
 * 중복 방지: 티켓팅 사이트의 상품 하나 = 공연 하나이므로 링크를 정규화한 sourceKey에 unique 제약을 둔다.
 * sourceKey 계산 규칙(사이트별 상품 ID 추출 → "{site}:{productId}", 미지원 사이트는 일반 URL 정규화)은
 * 서비스 계층에서 수행한 뒤 넘긴다. "공연장+제목"은 같은 공연장에서 같은 제목으로 다시 열리는 공연이
 * 있어 unique로 쓰지 않는다(서비스에서 유사 공연 안내용으로만 사용).
 *
 * 공연장은 별도 테이블 없이 공연의 텍스트 속성(venueName)이다. 사용자가 직접 수정할 수 없다
 * (관리자가 수정 제안을 승인할 때만 {@link #applyApprovedEdit} 로 바뀐다).
 *
 * 수정/삭제 정책: 등록 후 사용자는 수정·삭제할 수 없다(제목·공연장·링크 모두). 제목·공연장 변경은 관리자 승인으로만
 * ({@link #applyApprovedEdit} 가 유일한 수정 경로, 링크·회차는 수정 대상이 아니다). 삭제는 없다.
 * 등록자(registrant)는 상세 응답의 canEdit 표시 등에 쓰는 기록이다.
 */
@Entity
@Table(
        name = "performance",
        uniqueConstraints = @UniqueConstraint(name = "uk_performance_source_key", columnNames = "source_key")
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Performance {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int VENUE_NAME_MAX_LENGTH = 100;
    public static final int SOURCE_URL_MAX_LENGTH = 2048;
    public static final int SOURCE_KEY_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 공연장 이름 (텍스트, 앞뒤 공백 제거·연속 공백 한 칸). 관리자 승인으로만 수정된다. */
    @Column(name = "venue_name", nullable = false, length = VENUE_NAME_MAX_LENGTH)
    private String venueName;

    /** 사용자가 입력한 티켓팅 사이트 링크 원문 (공연 중복 판정의 기준). */
    @Column(name = "source_url", nullable = false, length = SOURCE_URL_MAX_LENGTH)
    private String sourceUrl;

    /**
     * 중복 판정 키 (정규화된 링크). 생성 후 변경 불가.
     * collation은 utf8mb4_bin(대소문자·악센트 구분): URL 경로·쿼리는 대소문자를 구분하므로 테이블 기본값
     * utf8mb4_0900_ai_ci를 쓰면 대소문자만 다른 링크가 같은 공연으로 합쳐진다. 키에 비ASCII(인코딩 안 된
     * 경로, 디코딩된 상품 ID)가 들어올 수 있어 ascii_bin이 아니라 utf8mb4_bin을 쓴다.
     */
    @Collate("utf8mb4_bin")
    @Column(name = "source_key", nullable = false, updatable = false, length = SOURCE_KEY_MAX_LENGTH)
    private String sourceKey;

    /** 등록자. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registrant_id", nullable = false, updatable = false)
    private User registrant;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 필드 변경이 flush될 때(@PreUpdate) 자동 갱신. 생성 시에는 createdAt과 같은 값. */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 공연 생성. 길이·형식 검증과 sourceKey 계산은 서비스에서 먼저 수행해 400으로 응답해야 한다
     * (여기서의 IllegalArgumentException은 최후 방어선).
     */
    public static Performance create(String venueName, String title, String sourceUrl, String sourceKey,
                                     User registrant) {
        requireNonNull(registrant, "registrant");
        requireText(sourceUrl, SOURCE_URL_MAX_LENGTH, "sourceUrl");
        requireText(sourceKey, SOURCE_KEY_MAX_LENGTH, "sourceKey");

        Performance performance = new Performance();
        performance.venueName = cleanVenueName(venueName);
        performance.title = cleanTitle(title);
        performance.sourceUrl = sourceUrl.strip();
        performance.sourceKey = sourceKey;
        performance.registrant = registrant;
        return performance;
    }

    /**
     * 관리자가 승인한 수정 제안 반영 — 이 엔티티의 유일한 수정 경로. null 인 값은 바꾸지 않는다(둘 다 null 이면 오류).
     * 호출하는 서비스가 공연 행을 FOR UPDATE 로 잠근 트랜잭션 안이어야 한다.
     */
    public void applyApprovedEdit(String newTitle, String newVenueName) {
        if (newTitle == null && newVenueName == null) {
            throw new IllegalArgumentException("새 제목과 새 공연장 이름 중 하나 이상이 필요합니다.");
        }
        String title = newTitle == null ? this.title : cleanTitle(newTitle);
        String venue = newVenueName == null ? this.venueName : cleanVenueName(newVenueName);
        this.title = title;
        this.venueName = venue;
    }

    /** 등록자 여부. LAZY 프록시의 id만 읽으므로 추가 쿼리가 나가지 않는다. */
    public boolean isRegisteredBy(Long userId) {
        return userId != null && registrant != null && userId.equals(registrant.getId());
    }

    private static String cleanTitle(String title) {
        String cleaned = cleanDisplayText(title);
        if (cleaned == null || cleaned.isEmpty() || cleaned.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("공연 제목은 1~" + TITLE_MAX_LENGTH + "자여야 합니다.");
        }
        return cleaned;
    }

    private static String cleanVenueName(String venueName) {
        String cleaned = cleanDisplayText(venueName);
        if (cleaned == null || cleaned.isEmpty() || cleaned.length() > VENUE_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("공연장 이름은 1~" + VENUE_NAME_MAX_LENGTH + "자여야 합니다.");
        }
        return cleaned;
    }

    /**
     * 표시용 텍스트 정리: 앞뒤 공백 제거 + 연속 공백 한 칸. null은 null.
     * 서비스 계층 입력 검증(길이 판정)도 같은 규칙을 써야 하므로 public.
     */
    public static String cleanDisplayText(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\s\\p{Z}\\uFEFF]+", " ").strip();
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new IllegalArgumentException(field + "는 1~" + maxLength + "자여야 합니다.");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
    }
}
