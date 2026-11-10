package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 金蝶生产订单快照（sn_prd_mo）、同步状态与物料行附表（附件项次、米数、产品单位、计数）。 */
@Mapper
public interface OrderMapper {
    int deleteBill(@Param("billNo") String billNo);

    int insertRows(@Param("rows") List<Map<String, Object>> rows, @Param("ts") LocalDateTime ts);

    int insertRowsNew(@Param("rows") List<Map<String, Object>> rows, @Param("ts") LocalDateTime ts);

    int removeBills(@Param("nos") Collection<String> nos);

    List<Map<String, Object>> localHeads();

    Map<String, Object> syncState();

    int finishOk(@Param("ts") LocalDateTime ts, @Param("bills") Integer bills);

    int finishFail(@Param("ts") LocalDateTime ts, @Param("message") String message);

    int setLastFull(@Param("ts") LocalDateTime ts);

    boolean existsBill(@Param("billNo") String billNo);

    long countRows();

    int dropNew();

    int dropOld();

    int createNew();

    int swapNew();

    Map<String, Object> line(@Param("billNo") String billNo, @Param("seq") Integer seq);

    List<Map<String, Object>> lines(@Param("billNo") String billNo);

    List<Map<String, Object>> linesOfPi(@Param("pi") String pi);

    Map<String, Object> lineCount(@Param("billNo") String billNo, @Param("seq") int seq);

    Map<String, Object> lockAttach(@Param("billNo") String billNo, @Param("seq") int seq);

    Map<String, Object> lockLength(@Param("billNo") String billNo, @Param("seq") int seq);

    Map<String, Object> lockUnit(@Param("billNo") String billNo, @Param("seq") int seq);

    int deleteAttach(@Param("billNo") String billNo, @Param("seq") int seq);

    int deleteLength(@Param("billNo") String billNo, @Param("seq") int seq);

    int deleteUnit(@Param("billNo") String billNo, @Param("seq") int seq);

    int deleteLineRule(@Param("billNo") String billNo, @Param("seq") int seq);

    int upsertAttach(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("value") String value,
            @Param("materialCode") String materialCode,
            @Param("k3Value") String k3Value,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int upsertLength(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("value") String value,
            @Param("materialCode") String materialCode,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int upsertUnit(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("value") String value,
            @Param("materialCode") String materialCode,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> countsOf(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> attachesOf(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> lengthsOf(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> unitsOf(@Param("bills") Collection<String> bills);

    long countBills(
            @Param("like") String like,
            @Param("customer") String customer,
            @Param("pi") String pi,
            @Param("scope") List<String> scope);

    List<Map<String, Object>> pageBills(
            @Param("like") String like,
            @Param("customer") String customer,
            @Param("pi") String pi,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> departments(@Param("scope") List<String> scope);

    Map<String, Object> meta(@Param("scope") List<String> scope);

    long pendingAttach(@Param("scope") List<String> scope);

    List<String> customers(@Param("like") String like, @Param("scope") List<String> scope);

    Map<String, Object> snapshotOrg(@Param("billNo") String billNo);

    List<Map<String, Object>> billOrgsSnap(@Param("nos") Collection<String> nos);

    List<Map<String, Object>> billOrgsLedger(@Param("nos") Collection<String> nos);

    String customerOfPi(@Param("pi") String pi);

    List<Map<String, Object>> targets(
            @Param("pi") String pi, @Param("like") String like, @Param("limit") int limit);
}
