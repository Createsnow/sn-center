package com.adtek.system.query;
import jakarta.validation.constraints.*;
/** 默认最近7天；显式起止使用UTC Instant，半开区间[from,to)，最多31天，排序固定时间/ID倒序。 */
public class AuditQuery {
    @Min(1) @Max(10000) private int page=1;
    public int getPage() { return page; }
    public void setPage(int value) { page=value; }
    @Min(1) @Max(100) private int size=20;
    public int getSize() { return size; }
    public void setSize(int value) { size=value; }
    @Size(max=35) private String from;
    public String getFrom() { return from; }
    public void setFrom(String value) { from=value; }
    @Size(max=35) private String to;
    public String getTo() { return to; }
    public void setTo(String value) { to=value; }
    @Size(max=100) private String action;
    public String getAction() { return action; }
    public void setAction(String value) { action=value; }
    @Size(max=60) private String module;
    public String getModule() { return module; }
    public void setModule(String value) { module=value; }
    @Size(max=64) private String actorUsername;
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { actorUsername=value; }
    @Pattern(regexp="[1-9][0-9]{0,18}") private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") private String traceId;
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId=value; }
     private Boolean success;
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean value) { success=value; }
}
