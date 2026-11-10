package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SN 导入（sn_import_item 与号池中导入的号）。 */
@Mapper
public interface SnImportMapper {
    List<Map<String, Object>> heads(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> lines(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> usedBills(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> poolSns(
            @Param("sns") Collection<String> sns, @Param("lock") boolean lock);

    List<Map<String, Object>> importedSns(
            @Param("sns") Collection<String> sns, @Param("lock") boolean lock);

    int insertImports(
            @Param("no") String no,
            @Param("rows") List<?> rows,
            @Param("fileName") String fileName,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> importIds(
            @Param("no") String no, @Param("sns") Collection<String> sns);

    int insertItems(
            @Param("no") String no, @Param("rows") List<?> rows, @Param("now") LocalDateTime now);

    String firstPoolSn(@Param("sns") Collection<String> sns);

    long countItems(
            @Param("scope") List<String> scope,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("importNo") String importNo,
            @Param("sn") String sn);

    List<Map<String, Object>> pageItems(
            @Param("scope") List<String> scope,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("importNo") String importNo,
            @Param("sn") String sn,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> details(@Param("ids") Collection<Long> ids);

    List<Map<String, Object>> records(@Param("no") String no);

    List<Map<String, Object>> pool(
            @Param("no") String no,
            @Param("sns") Collection<String> sns,
            @Param("lock") boolean lock);

    int deleteItems(@Param("ids") Collection<Long> ids);

    int markDeleted(
            @Param("no") String no, @Param("by") String by, @Param("now") LocalDateTime now);
}
