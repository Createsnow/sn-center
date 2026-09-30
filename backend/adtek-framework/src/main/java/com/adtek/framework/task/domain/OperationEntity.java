package com.adtek.framework.task.domain;
import com.adtek.framework.task.OperationState;
/** 主体+动作+操作UUID不可重复；inputDigest包含规范化版本，scopeDigest记录提交范围。 */
public record OperationEntity(String id,long principalId,String action,String operationId,int normalizationVersion,
 String inputDigest,String scopeDigest,OperationState state,String taskId,String resultRef,Integer errorCode) {}
