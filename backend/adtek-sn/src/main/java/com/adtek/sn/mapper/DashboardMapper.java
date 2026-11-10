package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 首页看板（只读汇总）。 */
@Mapper
public interface DashboardMapper {
    List<Map<String, Object>> statusCounts(@Param("scope") List<String> scope);

    long suspended(@Param("scope") List<String> scope);

    long interrupted(@Param("scope") List<String> scope);

    long stalePull(@Param("scope") List<String> scope, @Param("cutoff") LocalDateTime cutoff);

    List<Map<String, Object>> alerts(@Param("scope") List<String> scope);

    List<Map<String, Object>> todayAudit(
            @Param("scope") List<String> scope, @Param("start") LocalDateTime start);

    List<Map<String, Object>> todayConfirm(
            @Param("scope") List<String> scope, @Param("start") LocalDateTime start);

    long totalGenerated();

    long runningJobs();

    List<String> runningPlans();

    List<Map<String, Object>> recentJobs(@Param("limit") int limit);

    List<Map<String, Object>> recentFailures(
            @Param("scope") List<String> scope, @Param("cutoff") LocalDateTime cutoff);

    List<Map<String, Object>> openAlerts(@Param("scope") List<String> scope);
}
