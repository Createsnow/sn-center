package com.adtek.sn.mapper;

import com.adtek.sn.dto.LineRefDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SN 分配与分配页（sn_item、sn_line_count、sn_bill_stat）。 */
@Mapper
public interface AllocationMapper {
    List<Map<String, Object>> groups(
            @Param("pi") String pi,
            @Param("bills") List<String> bills,
            @Param("lines") List<LineRefDTO> lines);

    List<Map<String, Object>> idle(
            @Param("pi") String pi,
            @Param("bills") List<String> bills,
            @Param("lines") List<LineRefDTO> lines);

    List<Map<String, Object>> pendingMaterials(
            @Param("pi") String pi,
            @Param("bills") List<String> bills,
            @Param("lines") List<LineRefDTO> lines);

    List<Map<String, Object>> perLine(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("genOrg") String genOrg,
            @Param("bills") List<String> bills,
            @Param("lines") List<LineRefDTO> lines);

    int allocate(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("genOrg") String genOrg,
            @Param("status") String status,
            @Param("org") String org,
            @Param("now") LocalDateTime now,
            @Param("bills") List<String> bills,
            @Param("lines") List<LineRefDTO> lines);

    int addAllocCount(
            @Param("bill") String bill,
            @Param("seq") long seq,
            @Param("n") long n,
            @Param("now") LocalDateTime now);

    long countBills(
            @Param("scope") List<String> scope, @Param("q") String q, @Param("tab") String tab);

    List<Map<String, Object>> pageBills(
            @Param("scope") List<String> scope,
            @Param("q") String q,
            @Param("tab") String tab,
            @Param("offset") int offset,
            @Param("size") int size);

    Map<String, Object> summary(@Param("scope") List<String> scope);

    List<Map<String, Object>> contextRows(
            @Param("pi") String pi, @Param("scope") List<String> scope);

    List<Map<String, Object>> orderLines(@Param("bills") Collection<String> bills);

    BigDecimal quota(@Param("pi") String pi, @Param("scope") List<String> scope);

    List<String> pushOrgs(@Param("pi") String pi);

    boolean hasBatch(@Param("org") String org, @Param("pi") String pi);

    long pushedQty(@Param("org") String org, @Param("pi") String pi);

    long doneBatches(@Param("org") String org, @Param("pi") String pi);

    Map<String, Object> lastProblem(@Param("org") String org, @Param("pi") String pi);

    String currentPushState(@Param("batchNo") String batchNo);
}
