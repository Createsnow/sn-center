package com.adtek.sn.mapper;

import com.adtek.sn.domain.GenJobEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SN 生成：PI 流水（sn_pi_counter）、预演（sn_gen_preview）、生成任务（sn_gen_job）、写号池与计数。 */
@Mapper
public interface GenerateMapper {
    long lastSeq(@Param("pi") String pi);

    List<Map<String, Object>> gapRows(@Param("pi") String pi, @Param("top") long top);

    long usedTop(@Param("pi") String pi, @Param("top") long top);

    List<Map<String, Object>> deletedMarks(@Param("pi") String pi, @Param("top") long top);

    List<String> billsOfPi(@Param("pi") String pi);

    List<Map<String, Object>> billSegmentCounts(
            @Param("pi") String pi,
            @Param("bills") Collection<String> bills,
            @Param("scope") List<String> scope);

    List<Map<String, Object>> billSegments(
            @Param("pi") String pi, @Param("bill") String bill, @Param("scope") List<String> scope);

    long segmentSnCount(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("seqPi") String seqPi,
            @Param("start") long start,
            @Param("end") long end,
            @Param("billSeq") Integer billSeq,
            @Param("scope") List<String> scope);

    List<String> segmentSns(
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("seqPi") String seqPi,
            @Param("start") long start,
            @Param("end") long end,
            @Param("billSeq") Integer billSeq,
            @Param("scope") List<String> scope,
            @Param("size") int size,
            @Param("offset") int offset);

    List<Map<String, Object>> prdLinesOf(@Param("bills") Collection<String> bills);

    int insertPreview(
            @Param("token") String token,
            @Param("pi") String pi,
            @Param("qty") long qty,
            @Param("base") long base,
            @Param("plan") String plan,
            @Param("by") String by,
            @Param("now") LocalDateTime now,
            @Param("expires") LocalDateTime expires);

    Map<String, Object> preview(@Param("token") String token);

    boolean previewUsed(@Param("token") String token);

    int insertJob(@Param("entity") GenJobEntity entity);

    Map<String, Object> job(@Param("id") long id);

    Map<String, Object> jobByBatch(@Param("batch") String batch);

    Map<String, Object> jobByBatchLock(@Param("batch") String batch);

    int jobStarted(@Param("id") long id, @Param("now") LocalDateTime now);

    int jobSuccess(@Param("id") long id, @Param("now") LocalDateTime now);

    int jobFailed(
            @Param("id") long id,
            @Param("status") String status,
            @Param("code") String code,
            @Param("msg") String msg,
            @Param("now") LocalDateTime now);

    boolean counterExists(@Param("pi") String pi);

    int insertCounter(@Param("pi") String pi, @Param("now") LocalDateTime now);

    Map<String, Object> lockCounter(@Param("pi") String pi);

    String jobStatus(@Param("id") long id);

    LocalDateTime cancelAt(@Param("id") long id);

    boolean lineCountExists(@Param("billNo") String billNo, @Param("seq") int seq);

    int insertLineCount(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("materialCode") String materialCode,
            @Param("now") LocalDateTime now);

    Map<String, Object> lockLineCount(@Param("billNo") String billNo, @Param("seq") int seq);

    Map<String, Object> lineQty(@Param("billNo") String billNo, @Param("seq") int seq);

    int clearLineStart(@Param("billNo") String billNo, @Param("seq") int seq);

    int insertItems(
            @Param("rows") List<Map<String, Object>> rows,
            @Param("x") Map<String, Object> x,
            @Param("pi") String pi,
            @Param("versionId") long versionId,
            @Param("batch") String batch,
            @Param("now") LocalDateTime now);

    Map<String, Object> duplicateHit(@Param("sns") List<String> sns);

    int continueDeletedRev(@Param("sns") List<String> sns);

    int addLineCount(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("materialCode") String materialCode,
            @Param("qty") long qty,
            @Param("now") LocalDateTime now);

    int advanceCounter(
            @Param("pi") String pi,
            @Param("last") long last,
            @Param("qty") long qty,
            @Param("now") LocalDateTime now);

    Map<String, Object> ruleLock(@Param("id") long id);

    List<Map<String, Object>> runningJobs();

    Map<String, Object> jobStatusNowait(@Param("id") long id);

    Map<String, Object> counterNowait(@Param("pi") String pi);

    int markInterrupted(
            @Param("id") long id, @Param("msg") String msg, @Param("now") LocalDateTime now);

    int jobProgress(
            @Param("id") long id, @Param("done") long done, @Param("current") String current);

    int requestCancel(
            @Param("id") long id, @Param("now") LocalDateTime now, @Param("by") String by);

    List<Map<String, Object>> jobsOfPi(@Param("pi") String pi, @Param("limit") int limit);
}
