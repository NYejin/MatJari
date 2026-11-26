package com.matjari.repository;

import com.matjari.domain.ListingImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 티켓·교환 희망 조건 사진 (V33). 목록은 파일이 지워지지 않은 행만(deleted_at IS NULL) 본다. */
public interface ListingImageRepository extends JpaRepository<ListingImage, Long> {

    long countByTicketIdAndDeletedAtIsNull(Long ticketId);

    long countByRequestIdAndDeletedAtIsNull(Long requestId);

    /** 쓰기 경로(잠금을 잡은 뒤): 살아 있는 사진 행. 빈 순서 칸(0~2)을 고르고 개수를 센다. */
    List<ListingImage> findByTicketIdAndDeletedAtIsNull(Long ticketId);

    /** 후보·매칭·내 목록 응답용 한 번에 읽기(N+1 없음). 티켓 id 별로 순서대로. */
    List<ListingImage> findByTicketIdInAndDeletedAtIsNullOrderByTicketIdAscSortOrderAscIdAsc(Collection<Long> ticketIds);

    List<ListingImage> findByRequestIdInAndDeletedAtIsNullOrderByRequestIdAscSortOrderAscIdAsc(Collection<Long> requestIds);

    List<ListingImage> findByRequestIdAndDeletedAtIsNull(Long requestId);

    List<ListingImage> findByOwnerIdAndDeletedAtIsNull(Long ownerId);
}
