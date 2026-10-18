package com.matjari.service.aiextract;

import java.time.LocalDate;
import java.time.LocalTime;

/** 본문 텍스트에서 읽은 회차 한 건(날짜 + 시각). */
public record ExtractedSession(LocalDate date, LocalTime time) {
}
