package com.adtek.sn.service.impl;

import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.AuditMapper;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 操作痕迹写入：成功操作与业务写入同一事务（回滚则痕迹一起回滚，审计失败业务回滚）；被拒绝的操作用独立事务记一条 FAIL，不受业务回滚影响。 */
@Service
public class AuditServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);
    private final AuditMapper mapper;
    private final Tx tx;

    public AuditServiceImpl(AuditMapper mapper, Tx tx) {
        this.mapper = mapper;
        this.tx = tx;
    }

    /** 在当前事务中记一条。 */
    public void record(CurrentUser u, AuditEntryDTO e) {
        mapper.insert(
                Util.nowMillis(),
                u.empNo(),
                u.name() == null ? "" : u.name(),
                u.role() == null ? "" : u.role(),
                u.source(),
                u.traceId(),
                e);
    }

    /** 独立事务记一条（失败痕迹、后台任务）；写痕迹本身失败只打日志，不影响调用方。 */
    public void recordIsolated(CurrentUser u, AuditEntryDTO e) {
        try {
            tx.execIsolated(() -> record(u, e));
        } catch (RuntimeException ex) {
            log.warn("audit_write_failed action={} err={}", e.action, ex.toString());
        }
    }
}
