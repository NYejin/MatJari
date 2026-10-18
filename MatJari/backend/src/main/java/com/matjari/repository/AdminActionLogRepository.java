package com.matjari.repository;

import com.matjari.domain.AdminActionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 관리자 조치 기록 저장소. INSERT 와 읽기만 한다(수정·삭제 없음). */
public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

    @Query("select l from AdminActionLog l where (:action is null or l.action = :action) and (:targetType is null or l.targetType = :targetType) "
            + "and (:targetId is null or l.targetId = :targetId) order by l.id desc")
    Page<AdminActionLog> search(@Param("action") AdminActionLog.Action action, @Param("targetType") AdminActionLog.TargetType targetType,
                                @Param("targetId") Long targetId, Pageable pageable);
}
