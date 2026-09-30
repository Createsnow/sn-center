package com.adtek.framework.audit;

import java.util.Map;

/** 存储由 system 实现；成功必须加入当前事务，失败用独立事务且不覆盖原业务异常。 */
public interface ManagementAuditPort {
    void success(Event event);
    void failure(Event event);
    record Event(long actorId, String module, String action, String businessType, String objectId,
                 Map<String, Object> changes, String traceId) {}
}
