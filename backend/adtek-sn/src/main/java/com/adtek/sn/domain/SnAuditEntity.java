package com.adtek.sn.domain;
import java.time.Instant;
/** 同事务固定的SN操作事实；无通用更新/删除接口，归档保留原键及内容。 */
public class SnAuditEntity {
 private String id;
 public String getId(){return id;}
 public void setId(String value){this.id=value;}
 private Instant createdAt;
 public Instant getCreatedAt(){return createdAt;}
 public void setCreatedAt(Instant value){this.createdAt=value;}
 private Long actorId;
 public Long getActorId(){return actorId;}
 public void setActorId(Long value){this.actorId=value;}
 private String operator;
 public String getOperator(){return operator;}
 public void setOperator(String value){this.operator=value;}
 private String operatorName;
 public String getOperatorName(){return operatorName;}
 public void setOperatorName(String value){this.operatorName=value;}
 private String roles;
 public String getRoles(){return roles;}
 public void setRoles(String value){this.roles=value;}
 private String source;
 public String getSource(){return source;}
 public void setSource(String value){this.source=value;}
 private String action;
 public String getAction(){return action;}
 public void setAction(String value){this.action=value;}
 private String result;
 public String getResult(){return result;}
 public void setResult(String value){this.result=value;}
 private Long factoryId;
 public Long getFactoryId(){return factoryId;}
 public void setFactoryId(Long value){this.factoryId=value;}
 private String factoryCode;
 public String getFactoryCode(){return factoryCode;}
 public void setFactoryCode(String value){this.factoryCode=value;}
 private String factoryName;
 public String getFactoryName(){return factoryName;}
 public void setFactoryName(String value){this.factoryName=value;}
 private String pi;
 public String getPi(){return pi;}
 public void setPi(String value){this.pi=value;}
 private Long toFactoryId;
 public Long getToFactoryId(){return toFactoryId;}
 public void setToFactoryId(Long value){this.toFactoryId=value;}
 private String toFactoryCode;
 public String getToFactoryCode(){return toFactoryCode;}
 public void setToFactoryCode(String value){this.toFactoryCode=value;}
 private String toPi;
 public String getToPi(){return toPi;}
 public void setToPi(String value){this.toPi=value;}
 private String customer;
 public String getCustomer(){return customer;}
 public void setCustomer(String value){this.customer=value;}
 private String material;
 public String getMaterial(){return material;}
 public void setMaterial(String value){this.material=value;}
 private String billNo;
 public String getBillNo(){return billNo;}
 public void setBillNo(String value){this.billNo=value;}
 private String startSn;
 public String getStartSn(){return startSn;}
 public void setStartSn(String value){this.startSn=value;}
 private String endSn;
 public String getEndSn(){return endSn;}
 public void setEndSn(String value){this.endSn=value;}
 private Long quantity;
 public Long getQuantity(){return quantity;}
 public void setQuantity(Long value){this.quantity=value;}
 private String beforeStatus;
 public String getBeforeStatus(){return beforeStatus;}
 public void setBeforeStatus(String value){this.beforeStatus=value;}
 private String afterStatus;
 public String getAfterStatus(){return afterStatus;}
 public void setAfterStatus(String value){this.afterStatus=value;}
 private String batchId;
 public String getBatchId(){return batchId;}
 public void setBatchId(String value){this.batchId=value;}
 private String operationId;
 public String getOperationId(){return operationId;}
 public void setOperationId(String value){this.operationId=value;}
 private String transferId;
 public String getTransferId(){return transferId;}
 public void setTransferId(String value){this.transferId=value;}
 private String reason;
 public String getReason(){return reason;}
 public void setReason(String value){this.reason=value;}
 private String errorCode;
 public String getErrorCode(){return errorCode;}
 public void setErrorCode(String value){this.errorCode=value;}
 private String traceId;
 public String getTraceId(){return traceId;}
 public void setTraceId(String value){this.traceId=value;}
 private String detail;
 public String getDetail(){return detail;}
 public void setDetail(String value){this.detail=value;}
}
