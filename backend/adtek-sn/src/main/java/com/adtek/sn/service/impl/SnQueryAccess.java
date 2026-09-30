package com.adtek.sn.service.impl;
import com.adtek.common.core.Actor;
import com.adtek.framework.security.AuthorizationMutationPort;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.sn.mapper.SnQueryMapper;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.service.support.*;
import java.util.List;
import org.springframework.stereotype.Component;

/** 不依赖任务提交服务，供处理器/下载和查询用例复用全部原成员的当前授权检查。 */
@Component public class SnQueryAccess {
    final SnQueryMapper rows;final SnQueryPolicy policy;final AcquireEngine acquire;final DurableFileMapper files;final AuthorizationMutationPort authorization;
    public SnQueryAccess(SnQueryMapper rows,SnQueryPolicy policy,AcquireEngine acquire,DurableFileMapper files,AuthorizationMutationPort authorization){this.rows=rows;this.policy=policy;this.acquire=acquire;this.files=files;this.authorization=authorization;}
    void authorize(Actor actor,String id) {
        var export=rows.exportById(id);if(export==null||export.actorId()!=actor.userId())throw SnPolicy.missing();var query=acquire.json.readValue(export.filters(),SnItemQuery.class);
        for(String permission:List.of(SnQueryPolicy.VIEW,SnQueryPolicy.EXPORT)) {
            var scope=policy.scope(actor,permission,query);if(rows.exportVisible(scope,id,query.pi())!=export.quantity())throw AcquirePolicy.forbidden();
        }
    }
}
