package com.adtek.sn.mapper;

import com.adtek.sn.query.AuditQuery;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 痕迹查询（sn_audit 与归档表 sn_audit_archive）。 */
@Mapper
public interface AuditQueryMapper {
    Map<String, Object> archiveRange();

    long countLive(@Param("f") AuditQuery f, @Param("beforeId") Long beforeId);

    long countArchive(@Param("f") AuditQuery f, @Param("beforeId") Long beforeId);

    List<Map<String, Object>> page(
            @Param("f") AuditQuery f,
            @Param("archive") boolean archive,
            @Param("beforeId") Long beforeId,
            @Param("byId") boolean byId,
            @Param("offset") int offset,
            @Param("size") int size);
}
