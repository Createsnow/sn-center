package com.adtek.sn.mapper;

import com.adtek.sn.query.SnBillQuery;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** SN 导出（已分配的号按「生产组织 + PI + 单据」查看与下载）。 */
@Mapper
public interface SnExportMapper {
    long countBills(
            @Param("scope") List<String> scope,
            @Param("pi") String pi,
            @Param("q") String q,
            @Param("mes") Integer mes);

    List<Map<String, Object>> pageBills(
            @Param("scope") List<String> scope,
            @Param("pi") String pi,
            @Param("q") String q,
            @Param("mes") Integer mes,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> lines(@Param("f") SnBillQuery f);

    long countSegments(@Param("f") SnBillQuery f);

    List<Map<String, Object>> pageSegments(
            @Param("f") SnBillQuery f, @Param("offset") int offset, @Param("size") int size);

    Map<String, Object> snAt(@Param("f") SnBillQuery f, @Param("sn") String sn);

    long countItems(@Param("f") SnBillQuery f);

    boolean exists(@Param("f") SnBillQuery f);

    List<String> attaches(@Param("f") SnBillQuery f);

    List<Map<String, Object>> pageItems(
            @Param("f") SnBillQuery f,
            @Param("rank") List<String> rank,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> fileChunk(
            @Param("f") SnBillQuery f,
            @Param("afterSeq") Long afterSeq,
            @Param("afterPi") String afterPi,
            @Param("afterDec") Long afterDec);
}
