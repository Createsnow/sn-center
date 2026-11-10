package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 订单变更提醒（sn_order_alert）、已出号单据台账（sn_bill_ledger）与暂停交付标记。 */
@Mapper
public interface ChangeMapper {
    int countDaily(
            @Param("day") LocalDate day,
            @Param("kind") String kind,
            @Param("factory") String factory);

    List<Map<String, Object>> openAlertsByBill(@Param("bills") Collection<String> bills);

    List<Map<String, Object>> ledgersOf(@Param("bills") Collection<String> bills);

    Map<String, Object> ledger(@Param("billNo") String billNo);

    Map<String, Object> ledgerLock(@Param("billNo") String billNo);

    boolean openAlertOfType(@Param("billNo") String billNo, @Param("type") String type);

    long openAlertCount(@Param("scope") List<String> scope);

    int insertLedger(
            @Param("billNo") String billNo,
            @Param("pi") String pi,
            @Param("customerCode") String customerCode,
            @Param("orgCode") String orgCode,
            @Param("orgName") String orgName,
            @Param("now") LocalDateTime now);

    int insertLedgerLines(@Param("rows") List<Map<String, Object>> rows);

    int upsertLedgerLines(@Param("rows") List<Map<String, Object>> rows);

    int setItemsSuspend(
            @Param("billNo") String billNo,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    int clearDeliveredSuspend(@Param("billNo") String billNo);

    boolean anySuspended(@Param("billNo") String billNo);

    boolean ledgerExists(@Param("billNo") String billNo);

    String ledgerSuspendShare(@Param("billNo") String billNo);

    int setLedgerSuspend(
            @Param("billNo") String billNo,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    List<String> openTypes(@Param("billNo") String billNo);

    int setPiFrozen(@Param("billNo") String billNo, @Param("frozen") int frozen);

    Map<String, Object> openAlertLock(
            @Param("billNo") String billNo, @Param("type") String type, @Param("seq") Integer seq);

    int updateAlertAfter(
            @Param("id") long id, @Param("after") String after, @Param("now") LocalDateTime now);

    int insertAlert(
            @Param("billNo") String billNo,
            @Param("type") String type,
            @Param("seq") Integer seq,
            @Param("before") String before,
            @Param("after") String after,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> openAlertsOfTypes(
            @Param("billNo") String billNo, @Param("types") Collection<String> types);

    int closeAlert(
            @Param("id") long id,
            @Param("by") String by,
            @Param("now") LocalDateTime now,
            @Param("action") String action,
            @Param("note") String note);

    List<Map<String, Object>> attachK3Rows(@Param("billNo") String billNo);

    int updateAttachK3(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("value") String value,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> countsOfBill(@Param("billNo") String billNo);

    List<Map<String, Object>> ledgerLines(@Param("billNo") String billNo);

    int clearLeave(@Param("billNo") String billNo, @Param("now") LocalDateTime now);

    int setLeftAt(@Param("billNo") String billNo, @Param("now") LocalDateTime now);

    int setLeaveState(@Param("billNo") String billNo, @Param("state") String state);

    List<String> reversedBills();

    List<String> snapBills();

    List<Map<String, Object>> allLedgers();

    long countAlerts(
            @Param("status") String status,
            @Param("billNo") String billNo,
            @Param("type") String type,
            @Param("scope") List<String> scope);

    List<Map<String, Object>> pageAlerts(
            @Param("status") String status,
            @Param("billNo") String billNo,
            @Param("type") String type,
            @Param("scope") List<String> scope,
            @Param("offset") int offset,
            @Param("size") int size);

    List<Map<String, Object>> billItemCounts(@Param("bills") Collection<String> bills);

    List<String> itemFactoriesOfBill(@Param("billNo") String billNo);

    Map<String, Object> alert(@Param("id") long id);

    Map<String, Object> alertLock(@Param("id") long id);

    Map<String, Object> snapFirst(@Param("billNo") String billNo);

    int updateLedgerPi(
            @Param("billNo") String billNo,
            @Param("value") String value,
            @Param("now") LocalDateTime now);

    int updateLedgerCustomer(
            @Param("billNo") String billNo,
            @Param("value") String value,
            @Param("now") LocalDateTime now);

    int updateLedgerOrg(
            @Param("billNo") String billNo,
            @Param("orgCode") String orgCode,
            @Param("orgName") String orgName,
            @Param("now") LocalDateTime now);

    int setVoidConfirmed(@Param("billNo") String billNo, @Param("now") LocalDateTime now);
}
