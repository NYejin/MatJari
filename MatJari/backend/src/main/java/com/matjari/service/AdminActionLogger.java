package com.matjari.service;

import com.matjari.domain.AdminActionLog;
import com.matjari.repository.AdminActionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 조치 기록 쓰기. 호출한 조치 트랜잭션에 참여한다(조치가 롤백되면 기록도 없다). 반드시 조치 트랜잭션의 맨 끝에서 부른다
 * (기록 행 INSERT 뒤에 티켓·요청·매칭 잠금을 잡지 않기 위해). reason 은 앞뒤 공백을 정리해 500자로 자른다.
 */
@Service
public class AdminActionLogger {

    private final AdminActionLogRepository repository;

    public AdminActionLogger(AdminActionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(Long adminId, AdminActionLog.Action action, AdminActionLog.TargetType targetType, Long targetId,
                    String reason, String detailJson) {
        String r = reason == null ? null : reason.trim();
        if (r != null && r.length() > AdminActionLog.REASON_MAX_LENGTH) {
            r = r.substring(0, AdminActionLog.REASON_MAX_LENGTH);
        }
        repository.save(AdminActionLog.of(adminId, action, targetType, targetId, r == null || r.isEmpty() ? null : r, detailJson));
    }
}
