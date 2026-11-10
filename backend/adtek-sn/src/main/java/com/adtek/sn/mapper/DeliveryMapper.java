package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 交付批次、回执、推送任务、MES 拉取与交付告警（sn_delivery_*、sn_push_task、sn_rate_counter）。 */
@Mapper
public interface DeliveryMapper {
    // ---------------------------------------------------------------- 批次
    Map<String, Object> batch(@Param("batchNo") String batchNo);

    Map<String, Object> batchLock(@Param("batchNo") String batchNo);

    Map<String, Object> batchById(@Param("id") long id);

    boolean seqExists(@Param("name") String name);

    int seqInsert(@Param("name") String name);

    int seqBump(@Param("name") String name);

    Long seqValue(@Param("name") String name);

    Long seqLock(@Param("name") String name);

    int insertBatch(@Param("entity") Map<String, Object> entity);

    int insertItems(@Param("batchId") long batchId, @Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> claim(
            @Param("org") String org,
            @Param("pi") String pi,
            @Param("limit") int limit,
            @Param("bill") String bill,
            @Param("btype") String btype,
            @Param("imported") Boolean imported);

    List<Map<String, Object>> poolGroups(
            @Param("org") String org, @Param("pi") String pi, @Param("bill") String bill);

    int assignBatch(
            @Param("batchNo") String batchNo,
            @Param("now") LocalDateTime now,
            @Param("ids") Collection<Long> ids);

    List<Map<String, Object>> lockPoolIds(
            @Param("ids") Collection<Long> ids, @Param("org") String org, @Param("pi") String pi);

    List<Map<String, Object>> importDetails(@Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- 回执
    int insertReceipt(
            @Param("batchId") long batchId,
            @Param("method") String method,
            @Param("outcome") String outcome,
            @Param("verdict") String verdict,
            @Param("detail") String detail,
            @Param("body") byte[] body,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int markFailed(
            @Param("id") long id,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("voidable") boolean voidable,
            @Param("now") LocalDateTime now);

    int acceptItems(@Param("batchId") long batchId);

    int confirmBatch(
            @Param("id") long id,
            @Param("method") String method,
            @Param("accepted") long accepted,
            @Param("now") LocalDateTime now);

    int itemsPushed(
            @Param("batchNo") String batchNo,
            @Param("org") String org,
            @Param("mode") String mode,
            @Param("now") LocalDateTime now);

    int clearHolder(
            @Param("batchId") long batchId,
            @Param("org") String org,
            @Param("now") LocalDateTime now);

    int wakeTask(
            @Param("org") String org,
            @Param("key") String key,
            @Param("batchNo") String batchNo,
            @Param("resume") boolean resume,
            @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- 人工处理
    int setManual(
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("basis") String basis,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int voidBatch(
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("basis") String basis,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    List<String> billsOfBatch(@Param("batchNo") String batchNo);

    List<Long> lockBatchIds(@Param("batchNo") String batchNo);

    int unbatch(@Param("batchNo") String batchNo, @Param("now") LocalDateTime now);

    int setRebuiltTo(@Param("id") long id, @Param("to") String to);

    List<Map<String, Object>> outEntries(@Param("batchId") long batchId);

    int moveOutBatch(@Param("from") String from, @Param("to") String to);

    Map<String, Object> alertLock(@Param("id") long id);

    int closeAlert(
            @Param("id") long id,
            @Param("by") String by,
            @Param("now") LocalDateTime now,
            @Param("note") String note);

    List<Long> frozenByTransferOut(@Param("ids") Collection<Long> ids);

    long countAlerts(@Param("status") String status, @Param("scope") List<String> scope);

    List<Map<String, Object>> pageAlerts(
            @Param("status") String status,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    // ---------------------------------------------------------------- 推送任务
    boolean taskExists(@Param("org") String org, @Param("key") String key);

    int taskInsertIgnore(
            @Param("org") String org,
            @Param("key") String key,
            @Param("resend") boolean resend,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int taskInsert(
            @Param("org") String org,
            @Param("key") String key,
            @Param("resend") boolean resend,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    Map<String, Object> taskLock(@Param("org") String org, @Param("key") String key);

    Map<String, Object> task(@Param("org") String org, @Param("key") String key);

    int taskRequeue(
            @Param("org") String org,
            @Param("key") String key,
            @Param("resend") boolean resend,
            @Param("now") LocalDateTime now);

    int taskQueue(
            @Param("org") String org,
            @Param("key") String key,
            @Param("resend") boolean resend,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    int resetForResume(
            @Param("org") String org, @Param("key") String key, @Param("now") LocalDateTime now);

    long openBatchCount(@Param("org") String org, @Param("key") String key);

    List<Map<String, Object>> kickTargets(@Param("billNo") String billNo);

    List<Map<String, Object>> runnableTasks(@Param("now") LocalDateTime now);

    long busyOrg(@Param("org") String org, @Param("now") LocalDateTime now);

    boolean keyRunning(@Param("key") String key, @Param("now") LocalDateTime now);

    int claimTask(
            @Param("org") String org,
            @Param("key") String key,
            @Param("state") String state,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("lease") LocalDateTime lease,
            @Param("runner") String runner,
            @Param("now") LocalDateTime now);

    int renewTask(
            @Param("org") String org,
            @Param("key") String key,
            @Param("batchNo") String batchNo,
            @Param("lease") LocalDateTime lease,
            @Param("now") LocalDateTime now);

    int extendLease(
            @Param("org") String org,
            @Param("key") String key,
            @Param("runner") String runner,
            @Param("lease") LocalDateTime lease);

    Map<String, Object> taskRequeueFlag(@Param("org") String org, @Param("key") String key);

    int endTask(
            @Param("org") String org,
            @Param("key") String key,
            @Param("state") String state,
            @Param("runAfter") LocalDateTime runAfter,
            @Param("batchNo") String batchNo,
            @Param("message") String message,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> pendingBatches(@Param("org") String org, @Param("key") String key);

    int receiptTimeoutResend(
            @Param("id") long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    int receiptTimeoutExhausted(
            @Param("id") long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    long otherDelivered(@Param("org") String org, @Param("pi") String pi);

    long sentBatches(@Param("org") String org, @Param("key") String key);

    long lastReceiptId(@Param("batchId") long batchId);

    int countPush(@Param("id") long id, @Param("now") LocalDateTime now);

    boolean receiptAfter(@Param("batchId") long batchId, @Param("seen") long seen);

    int waitReceipt(
            @Param("id") long id,
            @Param("next") LocalDateTime next,
            @Param("now") LocalDateTime now);

    int factoryCfgOk(@Param("org") String org);

    int factoryCfgFailed(@Param("org") String org, @Param("message") String message);

    int waitRetry(
            @Param("id") long id,
            @Param("next") LocalDateTime next,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    int retryExhausted(
            @Param("id") long id,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- MES 拉取与只读
    int rateBump(@Param("key") String key, @Param("win") LocalDateTime win);

    Long rateValue(@Param("key") String key, @Param("win") LocalDateTime win);

    Map<String, Object> firstInScope(
            @Param("org") String org,
            @Param("mes") boolean mes,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("cond") String cond,
            @Param("exclude") Long exclude,
            @Param("byPulled") boolean byPulled);

    long qtyInScope(
            @Param("org") String org,
            @Param("mes") boolean mes,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("cond") String cond,
            @Param("exclude") Long exclude);

    long unbatchedCount(
            @Param("org") String org,
            @Param("pi") String pi,
            @Param("bill") String bill,
            @Param("firstTry") long firstTry);

    long pausedCount(@Param("org") String org, @Param("pi") String pi, @Param("bill") String bill);

    boolean piHasItems(@Param("org") String org, @Param("pi") String pi);

    boolean piHasTransferOut(@Param("org") String org, @Param("pi") String pi);

    String firstPoolPi(@Param("org") String org, @Param("bill") String bill);

    Map<String, Object> blockingFailed(@Param("org") String org, @Param("pi") String pi);

    int markPulled(@Param("id") long id, @Param("now") LocalDateTime now);

    List<Map<String, Object>> readDelivered(
            @Param("org") String org,
            @Param("holder") boolean holder,
            @Param("pi") String pi,
            @Param("cursor") long cursor,
            @Param("limit") int limit);

    List<Map<String, Object>> readBatchItems(
            @Param("batchNo") String batchNo,
            @Param("org") String org,
            @Param("cursor") long cursor,
            @Param("limit") int limit);

    // ---------------------------------------------------------------- 页面查询
    long countBatches(
            @Param("scope") List<String> scope,
            @Param("delivery") boolean delivery,
            @Param("pi") String pi,
            @Param("batchNo") String batchNo,
            @Param("source") String source,
            @Param("batchType") String batchType,
            @Param("result") String result,
            @Param("transferNo") String transferNo);

    List<Map<String, Object>> pageBatches(
            @Param("scope") List<String> scope,
            @Param("delivery") boolean delivery,
            @Param("pi") String pi,
            @Param("batchNo") String batchNo,
            @Param("source") String source,
            @Param("batchType") String batchType,
            @Param("result") String result,
            @Param("transferNo") String transferNo,
            @Param("asc") boolean asc,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> receiptsOf(@Param("batchId") long batchId);

    List<Map<String, Object>> segmentsOf(@Param("batchId") long batchId);

    List<String> billsOfBatchItems(@Param("batchId") long batchId);

    List<String> materialsOfBatchItems(@Param("batchId") long batchId);

    List<Map<String, Object>> batchItemsPage(
            @Param("batchId") long batchId, @Param("offset") int offset, @Param("size") int size);

    List<Map<String, Object>> exportChunk(@Param("batchId") long batchId, @Param("last") long last);

    // ---------------------------------------------------------------- 每日自检
    List<Map<String, Object>> checkAccepted(@Param("since") LocalDateTime since);

    List<Map<String, Object>> checkDuplicates(@Param("since") LocalDateTime since);

    List<Map<String, Object>> checkUnconfirmed(@Param("cutoff") LocalDateTime cutoff);

    long manualCount(@Param("since") LocalDateTime since);

    boolean openCheckAlert(@Param("batchNo") String batchNo, @Param("sn") String sn);

    int insertCheckAlert(
            @Param("org") String org,
            @Param("batchNo") String batchNo,
            @Param("sn") String sn,
            @Param("detail") String detail,
            @Param("now") LocalDateTime now);
}
