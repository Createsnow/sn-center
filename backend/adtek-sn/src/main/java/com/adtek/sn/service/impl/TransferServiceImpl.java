package com.adtek.sn.service.impl;

import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.TransferQuery;
import com.adtek.sn.service.TransferService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

@Service public class TransferServiceImpl implements TransferService {
    private final TransferEngine e;
    private final DurableTaskService tasks;
    private final CommandValidator validator;
    private final SnCursor cursors;
    public TransferServiceImpl(TransferEngine e,DurableTaskService tasks,CommandValidator validator,SnCursor cursors){this.e=e;this.tasks=tasks;this.validator=validator;this.cursors=cursors;}
    private String value(String input,int limit,boolean required) {
        String text=SnPolicy.text(input);
        if(text.length()>limit||(required&&text.isEmpty())||text.codePoints().anyMatch(Character::isISOControl))throw SnPolicy.invalid();
        return text;
    }
    private TransferInput source(String factory,String pi,String scope,String material,String sn) {
        if(!Set.of("PI","MATERIAL","SINGLE").contains(scope))throw SnPolicy.invalid();
        if("MATERIAL".equals(scope)&&material==null)throw SnPolicy.invalid();
        return new TransferInput(SnPolicy.id(factory),AcquirePolicy.pi(pi),scope,"MATERIAL".equals(scope)?value(material,128,false):null,
            "SINGLE".equals(scope)?value(sn,64,true):null,0,"","","","MANUAL","",null,null,"");
    }
    @Override @Transactional(readOnly=true)
    public TransferCandidateVO candidates(String factory,String pi,String scope,String material,String sn) {
        var input=source(factory,pi,scope,material,sn);
        e.policy.require(e.policy.read(TransferPolicy.VIEW),TransferPolicy.VIEW,input.factoryId());
        return e.convert.candidate(e.rows.candidates(input));
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<TransferTargetVO> targets(boolean factories,String query,int page,int size) {
        e.policy.read(TransferPolicy.TARGETS);if(page<1||size<1||size>100)throw SnPolicy.invalid();
        String q=SnPolicy.like(value(query,128,false));long offset=(long)(page-1)*size;
        var items=factories?e.rows.factories(q,offset,size):e.rows.targets(q,offset,size);
        return new PageResult<>(items.stream().map(e.convert::target).toList(),Long.toString(factories?e.rows.factoryCount(q):e.rows.targetCount(q)),page,size);
    }
    @Override @Idempotent
    public TaskVO create(String mode,TransferCreateDTO command) {
        if(!Set.of("APPLY","DIRECT").contains(mode))throw SnPolicy.invalid();validator.requireValid(command);
        var source=source(command.factoryId(),command.pi(),command.scope(),command.material(),command.sn());
        if(!Set.of("MANUAL","SNAPSHOT").contains(command.targetSource()))throw SnPolicy.invalid();
        var input=new TransferInput(source.factoryId(),source.pi(),source.scope(),source.material(),source.sn(),SnPolicy.id(command.toFactoryId()),AcquirePolicy.pi(command.toPi()),
            value(command.toCustomer(),64,false),value(command.toMaterial(),128,false),command.targetSource(),value(command.reason(),500,true),null,null,"");
        return submit(mode,command.operationId(),input);
    }
    @Override @Idempotent
    public TaskVO decide(String id,String action,TransferDecisionDTO command) {
        if(!Set.of("APPROVE","REJECT","WITHDRAW").contains(action))throw SnPolicy.invalid();validator.requireValid(command);
        // 服务先校验动作资格再查询头；版本/状态校验放入提交回调以保留原操作重放。
        e.policy.read(TransferPolicy.permission(action));var transfer=e.rows.get(TaskAccess.uuid(id));if(transfer==null)throw SnPolicy.missing();
        return submit(action,command.operationId(),TransferEngine.from(transfer,SnPolicy.id(command.version()),value(command.note(),500,false)));
    }
    private TaskVO submit(String kind,String operation,TransferInput input) {
        String permission=TransferPolicy.permission(kind);var actor=e.policy.require(e.policy.read(permission),permission,input.factoryId());
        String payload=e.acquire.json.writeValueAsString(input);
        String canonical=e.acquire.json.writeValueAsString(new TreeMap<>(Map.of("v",1,"payloadDigest",TaskDigests.sha256(payload))));
        return tasks.submit(TransferEngine.type(kind),operation,canonical,TaskDigests.sha256(actor.userId()+":"+input.factoryId()),task->{
            e.policy.lock(actor,permission,input.factoryId());e.reserve(input,task.id());
            e.acquire.work.factory(input.factoryId());
            if(input.transferId()==null)e.acquire.work.factory(input.toFactoryId());
            else {var current=e.rows.get(input.transferId());if(current==null||!"PENDING".equals(current.status())||current.version()!=input.version())throw new BusinessException(TRANSFER_CHANGED);}
            e.rows.work(new TransferWorkEntity(task.id(),kind,input.transferId()==null?task.id():input.transferId(),input.factoryId(),input.pi(),input.toPi(),payload,"PENDING",null,null,null));
        });
    }
    @Override public TaskVO operation(String kind,String operation){return tasks.operation(TransferEngine.type(kind),operation);}
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<TransferVO> list(TransferQuery query) {
        validator.requireValid(query);String state=SnPolicy.optional(query.status());
        if(state!=null&&!Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN").contains(state))throw SnPolicy.invalid();
        var actor=e.policy.read(TransferPolicy.VIEW);Long factory=SnPolicy.optional(query.factoryId())==null?null:SnPolicy.id(query.factoryId());
        var scope=e.policy.scope(actor,factory);String pi=SnPolicy.optional(query.pi());
        return new PageResult<>(e.rows.list(scope,pi,state,(long)(query.page()-1)*query.size(),query.size()).stream().map(row->e.convert.transfer(row,e.policy.direction(actor,row,factory))).toList(),Long.toString(e.rows.count(scope,pi,state)),query.page(),query.size());
    }
    private TransferEntity visible(Actor actor,String id) {
        var transfer=e.rows.get(TaskAccess.uuid(id));if(transfer==null)throw SnPolicy.missing();e.policy.visible(actor,transfer);return transfer;
    }
    @Override @Transactional(readOnly=true)
    public TransferVO get(String id){var actor=e.policy.read(TransferPolicy.VIEW);var row=visible(actor,id);return e.convert.transfer(row,e.policy.direction(actor,row,null));}
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CursorResult<TransferItemVO> items(String id,String cursor,int size) {
        if(size<1||size>1000)throw SnPolicy.invalid();var actor=e.policy.read(TransferPolicy.VIEW);var transfer=visible(actor,id);
        String binding=cursors.binding(actor,"transfer:"+transfer.id());var position=cursors.read(cursor,binding);
        var records=e.rows.page(transfer.id(),position.ordinal(),size+1);boolean more=records.size()>size;var page=records.stream().limit(size).toList();
        Map<String,SnCurrentEntity> current=new HashMap<>();if(!page.isEmpty())e.rows.current(transfer.id(),position.ordinal(),page.getLast().ordinal()).forEach(row->current.put(row.id(),row));
        var items=page.stream().map(member->{var row=current.get(member.itemId());return new TransferItemVO(Long.toString(member.ordinal()),e.acquire.json.readValue(member.snapshot(),AcquireSnapshot.class),member.fromStatus(),member.fromBatchId(),member.fromPrintId(),
            row==null?null:row.status(),row==null||row.factoryId()==null?null:row.factoryId().toString(),row==null?null:row.pi(),row==null?null:row.currentBatchId());}).toList();
        return new CursorResult<>(items,more?cursors.next(binding,page.getLast().ordinal(),null,null):null);
    }
}
