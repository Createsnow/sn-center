package com.adtek.sn.service.impl;

import com.adtek.common.core.Actor;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.security.AuthorizationMutationPort;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.framework.task.service.TaskProgressService;
import com.adtek.sn.mapper.SnAuditMapper;
import com.adtek.sn.service.support.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 固定审计文件只授予原提交者；逐权限核对每条原工厂或转入厂，不依赖当前SN的归属。 */
@Component
public class SnAuditAccess {
    public static final String VIEW = "sn:audit:view", EXPORT = "sn:audit:export";
    final SnAuditMapper rows;
    final SnQueryPolicy policy;
    final AuthorizationMutationPort authorization;
    final DurableFileMapper files;
    final ObjectMapper json;
    final TaskProgressService progress;
    final ManagementAuditPort audit;

    public SnAuditAccess(
            SnAuditMapper rows,
            SnQueryPolicy policy,
            AuthorizationMutationPort authorization,
            DurableFileMapper files,
            ObjectMapper json,
            TaskProgressService progress,
            ManagementAuditPort audit) {
        this.rows = rows;
        this.policy = policy;
        this.authorization = authorization;
        this.files = files;
        this.json = json;
        this.progress = progress;
        this.audit = audit;
    }

    void authorize(Actor actor, String id) {
        var export = rows.exportById(id);
        if (export == null || export.actorId() != actor.userId()) throw SnPolicy.missing();
        for (String permission : List.of(VIEW, EXPORT))
            if (rows.exportVisible(policy.basic(actor, permission), id) != export.quantity())
                throw AcquirePolicy.forbidden();
    }
}
