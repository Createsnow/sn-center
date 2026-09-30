package com.adtek.sn.service.impl;

import com.adtek.common.core.*;
import com.adtek.common.annotation.Idempotent;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.AcquireQuery;
import com.adtek.sn.service.AcquireService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import java.math.BigInteger;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

@Service public class AcquireServiceImpl implements AcquireService {
 private final AcquireEngine e;private final DurableTaskService tasks;private final CommandValidator validator;private final SnCursor cursors;
 public AcquireServiceImpl(AcquireEngine e,DurableTaskService tasks,CommandValidator validator,SnCursor cursors){this.e=e;this.tasks=tasks;this.validator=validator;this.cursors=cursors;}
 private void query(AcquireQuery q){validator.requireValid(q);e.policy.material(q.materialMode(),q.material());if(q.status()!=null&&!q.status().isEmpty()&&!Set.of("TO_ACQUIRE","TO_PRINT","APPLYING").contains(q.status()))throw SnPolicy.invalid();}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<AcquireSummaryVO> summaries(AcquireQuery q){query(q);var s=e.policy.scope(e.policy.read(AcquirePolicy.VIEW),AcquirePolicy.VIEW,q,true);return new PageResult<>(e.rows.summaries(s,(long)(q.page()-1)*q.size(),q.size()).stream().map(e.convert::summary).toList(),Long.toString(e.rows.summaryCount(s)),q.page(),q.size());}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<AcquireSegmentVO> segments(AcquireQuery q){query(q);var s=e.policy.scope(e.policy.read(AcquirePolicy.VIEW),AcquirePolicy.VIEW,q,true);return new PageResult<>(e.rows.segments(s,q,(long)(q.page()-1)*q.size(),q.size()).stream().map(e.convert::segment).toList(),Long.toString(e.rows.segmentCount(s,q)),q.page(),q.size());}
 @Override @Idempotent public TaskVO take(AcquisitionDTO command){validator.requireValid(command);long factory=SnPolicy.id(command.factoryId());String pi=AcquirePolicy.pi(command.pi());var actor=e.policy.require(e.policy.read(AcquirePolicy.TAKE),AcquirePolicy.TAKE,factory);
  return submit("TAKE",command.operationId(),actor,new AcquireInput(factory,pi,null,List.of()));}
 private TaskVO submit(String kind,String operationId,Actor actor,AcquireInput input){String payload=e.json.writeValueAsString(input);String canonical=e.json.writeValueAsString(new TreeMap<>(Map.of("v",1,"factoryId",Long.toString(input.factoryId()),"pi",input.pi(),"payloadDigest",TaskDigests.sha256(payload))));
  try{return tasks.submit(AcquireEngine.type(kind),operationId,canonical,TaskDigests.sha256(actor.principalType()+":"+actor.userId()+":"+input.factoryId()),task->{
   var fresh=e.policy.lock(actor,AcquireEngine.permission(kind),input.factoryId());var counter=e.work.lockPi(input.pi());if(counter.activeTaskId()!=null)throw new BusinessException(PI_BUSY);e.work.factory(input.factoryId());e.work.occupy(input.pi(),task.id());
   e.rows.work(new AcquireWorkEntity(task.id(),kind,input.factoryId(),input.pi(),fresh.userId(),"SERVICE".equals(fresh.principalType())?"API":"PAGE",TaskAccess.uuid(operationId),payload,"PENDING",0,0,0,0,0,input.batchId(),null,null,null));
   e.fact(fresh,AcquireEngine.permission(kind)+":submit",task.id(),Map.of("pi",input.pi(),"factoryId",Long.toString(input.factoryId())));
  });}catch(BusinessException error){
   // 只带当前已授权请求的范围；不读取或披露同号旧操作属于哪个其他工厂/PI。
   if(error.getError().equals(com.adtek.framework.constant.ErrorCodeConstants.OPERATION_CONFLICT))throw new BusinessException(error.getError(),Map.of("operationId",TaskAccess.uuid(operationId),"action",AcquireEngine.permission(kind),"factoryId",Long.toString(input.factoryId()),"pi",input.pi()));
   throw error;
  }}
 @Override @Idempotent public TaskVO confirm(PrintConfirmationDTO command){validator.requireValid(command);if(!"PRINTED".equals(command.targetStatus()))throw SnPolicy.invalid();String batchId=TaskAccess.uuid(command.batchId());var actor=e.policy.read(AcquirePolicy.CALLBACK);var batch=e.rows.batchById(batchId);if(batch==null)throw SnPolicy.missing();e.policy.require(actor,AcquirePolicy.CALLBACK,batch.factoryId());
  boolean list=command.sns()!=null&&!command.sns().isEmpty(),range=command.ranges()!=null&&!command.ranges().isEmpty();if(list==range)throw SnPolicy.invalid();TreeSet<String> wanted=new TreeSet<>();
  if(list){for(String value:command.sns()){String sn=SnPolicy.text(value);if(sn.isEmpty()||sn.codePointCount(0,sn.length())>64||sn.codePoints().anyMatch(Character::isISOControl))throw SnPolicy.invalid();wanted.add(sn);}}
  else for(var r:command.ranges()){
   var start=e.rows.memberBySn(batchId,SnPolicy.text(r.startSn()));var end=e.rows.memberBySn(batchId,SnPolicy.text(r.endSn()));
   if(start==null||end==null||start.seqDec()==null||end.seqDec()==null||!Objects.equals(start.seqPi(),end.seqPi()))throw new BusinessException(CALLBACK_RANGE_INVALID);
   long lo=Math.min(start.seqDec(),end.seqDec()),hi=Math.max(start.seqDec(),end.seqDec());BigInteger length=BigInteger.valueOf(hi).subtract(BigInteger.valueOf(lo)).add(BigInteger.ONE);
   if(length.compareTo(BigInteger.valueOf(e.work.settings.getMaxBatchQuantity()))>0)throw new BusinessException(RESOURCE_LIMIT);
   var members=e.rows.range(batchId,start.seqPi(),lo,hi,length.intValueExact()+1);if(members.size()!=length.intValueExact())throw new BusinessException(CALLBACK_RANGE_INVALID);
   BigInteger expected=BigInteger.valueOf(lo);for(var member:members){if(!BigInteger.valueOf(member.seqDec()).equals(expected))throw new BusinessException(CALLBACK_RANGE_INVALID);wanted.add(member.sn());expected=expected.add(BigInteger.ONE);}e.work.resource(wanted.size());
  }
  e.work.resource(wanted.size());return submit("CALLBACK",command.operationId(),actor,new AcquireInput(batch.factoryId(),batch.pi(),batchId,List.copyOf(wanted)));
 }
 @Override public TaskVO operation(String kind,String id){return tasks.operation(AcquireEngine.type(kind),id);}
 private AcquireWorkEntity own(String id){String task=TaskAccess.uuid(id);tasks.get(task);var row=e.rows.workById(task);if(row==null)throw SnPolicy.missing();return row;}
 @Override @Transactional(readOnly=true) public AcquireWorkVO result(String id){return e.convert.work(own(id));}
 private void size(int size){if(size<1||size>1000)throw SnPolicy.invalid();}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public CursorResult<AcquireResultVO> results(String id,String cursor,int size){size(size);var work=own(id);var actor=e.policy.read(AcquireEngine.permission(work.kind()));String binding=cursors.binding(actor,"result:"+work.taskId());var position=cursors.read(cursor,binding);var rows=e.rows.results(work.taskId(),position.ordinal(),size+1);boolean more=rows.size()>size;var page=rows.stream().limit(size).toList();return new CursorResult<>(page.stream().map(e.convert::result).toList(),more?cursors.next(binding,page.getLast().ordinal(),null,null):null);}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<AcquireBatchVO> batches(AcquireQuery q){query(q);var s=e.policy.scope(e.policy.read(AcquirePolicy.VIEW),AcquirePolicy.VIEW,q,false);return new PageResult<>(e.rows.batches(s,(long)(q.page()-1)*q.size(),q.size()).stream().map(e.convert::batch).toList(),Long.toString(e.rows.batchCount(s)),q.page(),q.size());}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public CursorResult<AcquireItemVO> items(String id,String cursor,int size){size(size);String batchId=TaskAccess.uuid(id);var actor=e.policy.read(AcquirePolicy.VIEW);var batch=e.rows.batchById(batchId);if(batch==null)throw SnPolicy.missing();e.policy.require(actor,AcquirePolicy.VIEW,batch.factoryId());
  String binding=cursors.binding(actor,"batch:"+batchId);var position=cursors.read(cursor,binding);var members=e.rows.members(batchId,position.ordinal(),size+1);boolean more=members.size()>size;var page=members.stream().limit(size).toList();var current=new HashMap<String,SnCurrentEntity>();if(!page.isEmpty())e.rows.currentFor(batchId,page.stream().map(AcquireMemberEntity::sn).toList()).forEach(row->current.put(row.id(),row));
  var output=page.stream().map(member->{var row=current.get(member.itemId());return new AcquireItemVO(Long.toString(member.ordinal()),e.json.readValue(member.snapshot(),AcquireSnapshot.class),row==null?null:row.status(),row==null||row.factoryId()==null?null:row.factoryId().toString(),row==null?null:row.pi(),row==null?null:row.currentBatchId(),row!=null&&batchId.equals(row.currentBatchId())&&Objects.equals(row.factoryId(),batch.factoryId())&&row.pi().equals(batch.pi()));}).toList();
  return new CursorResult<>(output,more?cursors.next(binding,page.getLast().ordinal(),null,null):null);
 }
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public CursorResult<SnReadItemVO> readItems(String factory,String pi,String customer,String mode,String material,String cursor,int size){size(size);e.policy.material(mode,material);var actor=e.policy.read(AcquirePolicy.ITEMS);var query=new AcquireQuery(Long.toString(SnPolicy.id(factory)),AcquirePolicy.pi(pi),mode,material,null,false,1,1);var scope=e.policy.scope(actor,AcquirePolicy.ITEMS,query,false);
  String normalizedCustomer=SnPolicy.optional(customer);if(normalizedCustomer!=null&&normalizedCustomer.length()>64)throw SnPolicy.invalid();if(material!=null&&material.length()>128)throw SnPolicy.invalid();
  String binding=cursors.binding(actor,"items:"+e.json.writeValueAsString(List.of(factory,query.pi(),normalizedCustomer==null?"":normalizedCustomer,mode,material==null?"":material)));var position=cursors.read(cursor,binding);var rows=e.rows.items(scope,normalizedCustomer,mode,material,position.month(),position.id(),size+1);boolean more=rows.size()>size;var page=rows.stream().limit(size).toList();Map<Long,RuleVersionEntity> cache=new HashMap<>();return new CursorResult<>(page.stream().map(row->e.convert.item(row,e.seqText(row,cache))).toList(),more?cursors.next(binding,0,page.getLast().genMonth(),page.getLast().id()):null);
 }
}
