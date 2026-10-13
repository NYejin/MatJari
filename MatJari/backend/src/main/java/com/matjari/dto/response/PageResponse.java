package com.matjari.dto.response;

import java.util.List;

/** 페이지 응답 (Spring Page를 그대로 직렬화하지 않기 위한 고정 포맷). page는 0부터. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
