package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 生成页与分配页共用的 PI 工作台统计。 */
@Mapper
public interface WorkbenchMapper {
    List<Map<String, Object>> piRows(
            @Param("like") String like,
            @Param("byModify") boolean byModify,
            @Param("scope") List<String> scope,
            @Param("limit") int limit);

    List<Map<String, Object>> itemStats(
            @Param("pis") Collection<String> pis,
            @Param("scope") List<String> scope,
            @Param("genOnly") boolean genOnly);

    List<Map<String, Object>> interrupted(
            @Param("pis") Collection<String> pis, @Param("scope") List<String> scope);

    List<Map<String, Object>> activeTasks(
            @Param("pis") Collection<String> pis, @Param("scope") List<String> scope);

    List<Map<String, Object>> genOwn(
            @Param("pis") Collection<String> pis, @Param("scope") List<String> scope);

    List<Map<String, Object>> runningJobs(@Param("pis") Collection<String> pis);

    List<Map<String, Object>> frozen(@Param("scope") List<String> scope);

    LocalDateTime maxSynced();

    List<Map<String, Object>> linesOfPi(@Param("pi") String pi, @Param("scope") List<String> scope);

    List<Map<String, Object>> itemLines(@Param("pi") String pi, @Param("scope") List<String> scope);

    Map<String, Object> runningOfPi(@Param("pi") String pi);
}
