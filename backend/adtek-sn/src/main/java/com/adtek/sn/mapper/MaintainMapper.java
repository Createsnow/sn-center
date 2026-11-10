package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 每日维护：痕迹归档与清理、过期预演与限流计数清理、维护完成标记。 */
@Mapper
public interface MaintainMapper {
    Long lastDaily(@Param("name") String name);

    int markDaily(@Param("name") String name, @Param("val") long val);

    int archiveRange(@Param("lower") LocalDateTime lower, @Param("upper") LocalDateTime upper);

    int purgeArchive(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    int purgePreviews();

    int purgeRateCounters();
}
