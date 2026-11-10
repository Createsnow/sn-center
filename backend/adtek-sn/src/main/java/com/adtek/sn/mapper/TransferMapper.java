package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 转单、转组织与物料确认对应（sn_transfer、sn_bill_transfer、sn_transfer_item 与号池改挂）。 */
@Mapper
public interface TransferMapper {
    List<Map<String, Object>> sourceItems(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("factory") String factory,
            @Param("mats") Collection<String> mats,
            @Param("lock") boolean lock);

    int moveItems(
            @Param("g") Object group,
            @Param("tkind") String tkind,
            @Param("docNo") String docNo,
            @Param("now") LocalDateTime now,
            @Param("ids") Collection<Long> ids);

    int importSeq(@Param("seq") long seq, @Param("ids") Collection<Long> ids);

    int insertTransferItems(@Param("rows") List<?> rows);

    int ensureLineCount(
            @Param("bill") String bill, @Param("seq") long seq, @Param("now") LocalDateTime now);

    int addLineCount(
            @Param("bill") String bill,
            @Param("seq") long seq,
            @Param("d") long d,
            @Param("now") LocalDateTime now);

    int setOutBatch(
            @Param("batchId") long batchId,
            @Param("batchNo") String batchNo,
            @Param("kind") String kind,
            @Param("tid") long tid);

    int insertBillTransfer(@Param("e") Map<String, Object> e);

    int insertOrgTransfer(@Param("e") Map<String, Object> e);

    Map<String, Object> moLine(@Param("bill") String bill, @Param("seq") long seq);

    Map<String, Object> orderAlertLock(@Param("id") long id);

    List<Map<String, Object>> lineItems(@Param("bill") String bill, @Param("seq") long seq);

    int remapItems(
            @Param("seq") long seq,
            @Param("material") String material,
            @Param("mode") String mode,
            @Param("now") LocalDateTime now,
            @Param("ids") Collection<Long> ids);

    Map<String, Object> lineCountLock(@Param("bill") String bill, @Param("seq") long seq);

    int mergeLineCount(
            @Param("bill") String bill,
            @Param("seq") long seq,
            @Param("material") String material,
            @Param("own") long own,
            @Param("gen") long gen,
            @Param("alloc") long alloc,
            @Param("now") LocalDateTime now);

    int deleteLineCount(@Param("bill") String bill, @Param("seq") long seq);

    boolean lineRuleExists(@Param("bill") String bill, @Param("seq") long seq);

    int moveLineRule(@Param("bill") String bill, @Param("from") long from, @Param("to") long to);

    List<Map<String, Object>> sources(
            @Param("pi") String pi,
            @Param("factory") String factory,
            @Param("scope") List<String> scope);

    Map<String, Object> locateSn(@Param("q") String q, @Param("scope") List<String> scope);

    String locateBill(@Param("q") String q, @Param("scope") List<String> scope);

    boolean locatePi(@Param("q") String q, @Param("scope") List<String> scope);

    long countOrg(
            @Param("pi") String pi, @Param("bill") String bill, @Param("scope") List<String> scope);

    List<Map<String, Object>> pageOrg(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    long countBill(
            @Param("pi") String pi, @Param("bill") String bill, @Param("scope") List<String> scope);

    List<Map<String, Object>> pageBill(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    long countAll(
            @Param("org") boolean org,
            @Param("billKind") boolean billKind,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("scope") List<String> scope);

    List<Map<String, Object>> pageAll(
            @Param("org") boolean org,
            @Param("billKind") boolean billKind,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    Map<String, Object> orgTransfer(@Param("id") long id);

    Map<String, Object> billTransfer(@Param("id") long id);

    List<Map<String, Object>> outBatches(@Param("docNo") String docNo);

    List<Map<String, Object>> pushTargets(
            @Param("kind") String kind, @Param("tid") long tid, @Param("docNo") String docNo);

    Map<String, Object> billTransferOrgs(@Param("tid") long tid);

    long countItems(@Param("kind") String kind, @Param("tid") long tid);

    List<Map<String, Object>> pageItems(
            @Param("kind") String kind,
            @Param("tid") long tid,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> outListChunk(@Param("tid") long tid, @Param("last") long last);
}
