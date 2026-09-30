package com.adtek.sn.domain;
import java.time.LocalDate;
/** 不可变成员原状态和指针；撤回/驳回必须完整恢复，不能从当前状态猜测。 */
public record TransferMemberEntity(String transferId,long ordinal,LocalDate genMonth,String itemId,String fromStatus,String fromBatchId,String fromPrintId,String snapshot) {}
