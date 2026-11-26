package com.matjari.repository;

import com.matjari.dto.response.ListingImageRef;

import java.util.List;
import java.util.Map;

/**
 * 한 응답 페이지에 필요한 사진 참조 묶음(V33): 티켓 id 별·요청 id 별 사진 목록. 후보·매칭 목록이 id 를 모아 한 번에 읽어 만든다(N+1 방지).
 * 없는 id 는 빈 목록이다.
 */
public record ListingImageIndex(Map<Long, List<ListingImageRef>> byTicket, Map<Long, List<ListingImageRef>> byRequest) {

    public static final ListingImageIndex EMPTY = new ListingImageIndex(Map.of(), Map.of());

    public List<ListingImageRef> ticket(Long ticketId) {
        return byTicket.getOrDefault(ticketId, List.of());
    }

    public List<ListingImageRef> request(Long requestId) {
        return byRequest.getOrDefault(requestId, List.of());
    }
}
