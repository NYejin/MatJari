package com.matjari.controller;

import com.matjari.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 대시보드 (ADMIN 전용, SecurityConfig /api/admin/**). 읽기 전용 집계. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @GetMapping("/dashboard")
    public AdminDashboardService.Response dashboard() {
        return dashboardService.dashboard();
    }
}
