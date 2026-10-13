package com.seatswap.dto.response;

import java.util.List;

/**
 * 후보 목록의 keyset(커서) 한 조각. 전체 개수를 세지 않고 offset 을 쓰지 않으므로 후보가 많아도 뒤쪽 조각 비용이 앞쪽과 같다.
 * {@code nextCursor} 는 다음 조각을 읽을 때 그대로 돌려주는 불투명 문자열이며 더 없으면 null({@code hasNext=false}).
 */
public record CandidateSliceResponse(List<ExchangeCandidateResponse> content, int size, boolean hasNext, String nextCursor) {}
