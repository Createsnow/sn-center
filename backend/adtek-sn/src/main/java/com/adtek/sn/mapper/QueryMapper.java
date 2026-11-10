package com.adtek.sn.mapper;

import com.adtek.sn.query.SnItemQuery;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SN 查询与导出（只读）。 */
@Mapper
public interface QueryMapper {
    boolean piOwned(@Param("pi") String pi, @Param("scope") List<String> scope);

    List<Long> piVersions(@Param("pi") String pi);

    long count(@Param("f") SnItemQuery f, @Param("cap") long cap);

    List<Map<String, Object>> page(
            @Param("f") SnItemQuery f, @Param("offset") int offset, @Param("size") int size);

    List<Map<String, Object>> rangeSummary(@Param("f") SnItemQuery f);

    List<Map<String, Object>> ledger(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> snapshotPis(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> exportChunk(
            @Param("f") SnItemQuery f, @Param("last") long last, @Param("limit") int limit);
}
