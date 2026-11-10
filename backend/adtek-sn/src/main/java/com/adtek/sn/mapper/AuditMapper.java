package com.adtek.sn.mapper;

import com.adtek.sn.dto.AuditEntryDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 操作痕迹写入（sn_audit，按月分区）。 */
@Mapper
public interface AuditMapper {
    int insert(
            @Param("at") LocalDateTime at,
            @Param("operator") String operator,
            @Param("operatorName") String operatorName,
            @Param("role") String role,
            @Param("source") String source,
            @Param("traceId") String traceId,
            @Param("e") AuditEntryDTO e);
}
