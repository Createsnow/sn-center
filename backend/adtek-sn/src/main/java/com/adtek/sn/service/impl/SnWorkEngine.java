package com.adtek.sn.service.impl;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.security.AuthorizationMutationPort;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.service.TaskProgressService;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.*;
import com.adtek.sn.service.support.SnWorkInput.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

/** 持久任务的事务内执行器，不另开业务事务；任务代次由framework持锁后调用，成功审计失败会回滚整批。 */
@Component
public class SnWorkEngine {
 final SnWorkMapper rows;final RuleMapper rules;final RuleVersionMapper versions;final OrderBillMapper bills;final FactoryMapper factories;
 final SourceQuotaMapper quotas;final SnControlMapper control;final SnPolicy policy;final ObjectMapper json;final SnWorkSettings settings;
 private final AuthorizationMutationPort authorization;private final ManagementAuditPort audit;private final TaskProgressService progress;
 public SnWorkEngine(SnWorkMapper rows,RuleMapper rules,RuleVersionMapper versions,OrderBillMapper bills,FactoryMapper factories,SourceQuotaMapper quotas,
  SnControlMapper control,SnPolicy policy,ObjectMapper json,SnWorkSettings settings,AuthorizationMutationPort authorization,ManagementAuditPort audit,TaskProgressService progress){
  this.rows=rows;this.rules=rules;this.versions=versions;this.bills=bills;this.factories=factories;this.quotas=quotas;this.control=control;this.policy=policy;this.json=json;this.settings=settings;this.authorization=authorization;this.audit=audit;this.progress=progress;
 }
 static String permission(String kind){return switch(kind){case "PI_INIT"->"sn:pi-init:execute";case "GENERATE"->"sn:generation:execute";case "ALLOCATE"->"sn:allocation:execute";default->throw SnPolicy.invalid();};}
 static String type(String kind){permission(kind);return "SN_"+kind;}
 String encode(Object value){return json.writeValueAsString(value);}
 SnWorkInput input(SnWorkEntity row){return json.readValue(row.payload(),SnWorkInput.class);}
 Plan plan(SnPreviewEntity row){return json.readValue(row.payload(),Plan.class);}
 PiCounterEntity counter(String pi){var value=rows.counter(pi);return value==null?new PiCounterEntity(pi,0,0,0,null,false,null,null,null):value;}
 RuleVersionEntity effective(String pi,String customer){var rule=rules.effective(pi,customer);return rule==null?null:versions.current(List.of(rule.getId())).getFirst();}
 static SequenceSpec spec(RuleVersionEntity rule){return new SequenceSpec(rule.getPrefix(),rule.getSuffix(),rule.getBase(),rule.getSeqLength(),rule.getAlphabet());}
 String customer(String pi,String explicit){return explicit.isEmpty()?SnPolicy.text(bills.effectiveCustomer(pi)):explicit;}
 void factory(Long id){if(id!=null){var value=factories.selectById(id);if(value==null||!value.getEnabled())throw SnPolicy.missing();}}
 void resource(long qty){if(qty>settings.getMaxBatchQuantity())throw new BusinessException(RESOURCE_LIMIT);}
 void authorize(Actor actor,String kind){policy.require(actor,permission(kind),true);}
 PiCounterEntity lockPi(String pi){control.lock();rows.ensure(pi);return rows.lock(pi);}
 void occupy(String pi,String task){if(rows.occupy(pi,task)!=1)throw new BusinessException(PI_BUSY);}
 void audit(long actor,String action,String id){audit.success(new ManagementAuditPort.Event(actor,"SN初始化生成分配",action,"OTHER",id,Map.of(),org.slf4j.MDC.get("traceId")));}

 List<String> issues(OrderBillEntity bill,RuleVersionEntity rule){
  List<String> out=new ArrayList<>();if(!bill.getActive())out.add("NOT_IN_SNAPSHOT");if(bill.getCustomer().isEmpty())out.add("NO_CUSTOMER");if(bill.getPi().isEmpty())out.add("NO_PI");if(bill.getFactoryId()==null)out.add("ORG_UNMAPPED");if(rule==null)out.add("RULE_MISSING");
  var historical=quotaMap(bill.getId());long remaining=0;
  for(var line:rows.lines(bill.getId())){long consumed=historical.getOrDefault(line.getSourceEntryId(),new SourceQuotaMapper.Quota(bill.getId(),line.getSourceEntryId(),0,0)).generatedQty();if(java.math.BigDecimal.valueOf(consumed).compareTo(line.getQty())>0&&!out.contains("QUOTA_ANOMALY"))out.add("QUOTA_ANOMALY");remaining=Math.addExact(remaining,PlannedQuantity.remaining(line.getQty(),consumed));}
  if(!Boolean.TRUE.equals(bill.getQuantityReady()))out.add("NON_INTEGER_QUANTITY");if(remaining==0)out.add("QUOTA_EMPTY");if(rows.pendingMismatch(bill.getId(),bill.getPi(),bill.getOrgId(),bill.getFactoryId())>0)out.add("ALLOCATION_SOURCE_CHANGED");return List.copyOf(out);
 }
 Map<String,SourceQuotaMapper.Quota> quotaMap(long bill){Map<String,SourceQuotaMapper.Quota> map=new LinkedHashMap<>();for(var q:quotas.forBills(List.of(bill)))map.put(q.sourceEntryId(),q);return map;}
 long remaining(OrderBillEntity bill){if(!bill.getActive()||!Boolean.TRUE.equals(bill.getQuantityReady()))return 0;var history=quotaMap(bill.getId());long qty=0;for(var line:rows.lines(bill.getId()))qty=Math.addExact(qty,PlannedQuantity.remaining(line.getQty(),history.getOrDefault(line.getSourceEntryId(),new SourceQuotaMapper.Quota(bill.getId(),line.getSourceEntryId(),0,0)).generatedQty()));return qty;}
 OrderBillEntity bill(long id){var bill=rows.bill(id);if(bill==null)throw SnPolicy.missing();return bill;}
 Plan calculate(long billId,long quantity,Long start){
  resource(quantity);var bill=bill(billId);var rule=effective(bill.getPi(),bill.getCustomer());if(!issues(bill,rule).isEmpty())throw new BusinessException(GENERATION_NOT_READY);
  factory(bill.getFactoryId());var counter=counter(bill.getPi());if(start!=null&&counter.startLocked())throw new BusinessException(PI_INIT_LOCKED);
  var spec=spec(rule);long first=start==null?Math.addExact(counter.lastSeq(),1):start;long end=SequenceCodec.lastSequence(first-1,quantity,spec);long todo=quantity,next=first;
  var history=quotaMap(billId);List<Segment> segments=new ArrayList<>();
  for(var line:rows.lines(billId)){
   if(todo==0)break;var q=history.get(line.getSourceEntryId());if(q==null)throw new IllegalStateException("Missing source quota");long take=Math.min(todo,PlannedQuantity.remaining(line.getQty(),q.generatedQty()));
   if(take>0){segments.add(new Segment(line.getSourceEntryId(),line.getOrdinal(),line.getMaterial(),take,next,Math.addExact(next,take-1),line.getSnapshotVersion(),q.generatedQty()));todo-=take;next+=take;}
  }
  if(todo!=0)throw new BusinessException(QUOTA_EXCEEDED);
  return new Plan(bill,List.copyOf(segments),counter.lastSeq(),counter.startLocked(),start,quantity,first,end,rule.getRuleId(),rule.getVersion(),rule.getId(),rule.getContentDigest(),spec);
 }
 void samePlan(Plan expected){var actual=calculate(expected.bill().getId(),expected.quantity(),expected.requestedStart());if(!json.readTree(encode(actual)).equals(json.readTree(encode(expected))))throw new BusinessException(PREVIEW_CHANGED);}
 RuleVersionEntity lockRule(Long id,Long number,String digest){if(id==null)return null;var version=versions.lock(id,number);if(version==null||!version.getContentDigest().equals(digest))throw new BusinessException(PREVIEW_CHANGED);return version;}
 void used(RuleVersionEntity rule){if(rule!=null&&!rule.getUsed()){rule.setUsed(true);rule.setUpdatedAt(Instant.now());versions.updateById(rule);}}

 public String execute(TaskEntity task,Actor requested){
  var work=rows.find(task.id());if(work==null||!work.state().equals("PENDING"))throw new IllegalStateException("Missing pending SN work");
  var actor=authorization.lockCurrent(requested);authorize(actor,work.kind());var input=input(work);
  // 初始化的反解在PI锁之前完成，输入已受控；锁内再核对规则内容与一次性条件。
  RuleVersionEntity initRule=null;List<Long> decoded=new ArrayList<>();
  if(work.kind().equals("PI_INIT")){initRule=input.ruleId()==null?null:versions.current(List.of(input.ruleId())).getFirst();for(String sn:input.sns())decoded.add(initRule==null?null:SequenceCodec.parse(sn,spec(initRule)).stream().boxed().findFirst().orElse(null));}
  var counter=lockPi(work.pi());if(!task.id().equals(counter.activeTaskId()))throw new BusinessException(PI_BUSY);
  try{
   switch(work.kind()){
    case "PI_INIT"->initialize(task,actor,input,counter,decoded);
    case "GENERATE"->generate(task,actor,input,counter);
    case "ALLOCATE"->allocate(task,actor,input,counter);
    default->throw new IllegalStateException("Unknown SN work");
   }
  }catch(DuplicateKeyException conflict){throw new BusinessException(SN_CONFLICT);}
  if(rows.release(work.pi(),task.id())!=1)throw new IllegalStateException("Lost PI task ownership");audit(actor.userId(),permission(work.kind()),task.id());return task.id();
 }
 private void initialize(TaskEntity task,Actor actor,SnWorkInput input,PiCounterEntity counter,List<Long> decoded){
  if(counter.startLocked())throw new BusinessException(PI_INIT_LOCKED);factory(input.factoryId());var current=effective(input.pi(),input.customer());
  if(!Objects.equals(input.ruleId(),current==null?null:current.getRuleId())||!Objects.equals(input.ruleNumber(),current==null?null:current.getVersion())||!Objects.equals(input.ruleDigest(),current==null?null:current.getContentDigest()))throw new BusinessException(PREVIEW_CHANGED);
  var rule=lockRule(input.ruleId(),input.ruleNumber(),input.ruleDigest());if(input.requestedStart()!=null&&input.requestedStart()>(rule==null?Long.MAX_VALUE:SequenceCodec.capacity(spec(rule))))throw new BusinessException(SEQUENCE_OVERFLOW);
  long count=0,last=counter.lastSeq();Long max=null;if(input.requestedStart()!=null)last=Math.max(last,input.requestedStart()-1);
  List<SnItemEntity> batch=new ArrayList<>();for(int i=0;i<input.sns().size();i++){
   Long seq=decoded.get(i);if(seq!=null){count++;max=max==null?seq:Math.max(max,seq);last=Math.max(last,seq);}
   batch.add(item(task,input.pi(),input.sns().get(i),seq,input.customer(),input.material(),input.factoryId(),"IMPORT",seq==null?null:rule.getId(),null,null));
   if(batch.size()==500){write(task,batch,i+1);batch.clear();}
  }
  if(!batch.isEmpty())write(task,batch,input.sns().size());if(count>0)used(rule);
  rows.advance(input.pi(),last,0,input.sns().size(),input.requestedStart(),actor.userId());
  complete(task,input.requestedStart(),null,count,max,last,input.sns().isEmpty()?null:input.sns().getFirst(),input.sns().isEmpty()?null:input.sns().getLast());
 }
 private void generate(TaskEntity task,Actor actor,SnWorkInput input,PiCounterEntity counter){
  var preview=rows.previewForTask(task.id());if(preview==null||preview.actorId()!=actor.userId()||!preview.expiresAt().isAfter(Instant.now()))throw new BusinessException(PREVIEW_CHANGED);
  var p=input.plan();rows.lockQuota(p.bill().getId());samePlan(p);var rule=lockRule(p.ruleId(),p.ruleNumber(),p.ruleDigest());
  long processed=0;List<SnItemEntity> batch=new ArrayList<>();
  for(var s:p.segments()){
   for(long seq=s.start();seq<=s.end();seq++){batch.add(item(task,input.pi(),SequenceCodec.format(seq,p.spec()),seq,input.customer(),s.material(),null,"GEN",rule.getId(),p.bill(),s.entryId()));processed++;if(batch.size()==500){write(task,batch,processed);batch.clear();}}
   if(rows.quota(p.bill().getId(),s.entryId(),s.quantity(),0)!=1)throw new IllegalStateException("Missing source quota");
  }
  if(!batch.isEmpty())write(task,batch,processed);used(rule);rows.advance(input.pi(),p.end(),p.quantity(),0,p.requestedStart(),actor.userId());
  complete(task,p.start(),p.end(),null,null,p.end(),SequenceCodec.format(p.start(),p.spec()),SequenceCodec.format(p.end(),p.spec()));
 }
 private void allocate(TaskEntity task,Actor actor,SnWorkInput input,PiCounterEntity counter){
  rows.lockQuota(input.billId());factory(input.factoryId());var pending=rows.pending(input.billId());
  if(!pending.stream().map(i->new Member(i.genMonth(),i.id())).toList().equals(input.members()))throw new BusinessException(ALLOCATION_CHANGED);
  var current=bill(input.billId());if(!Objects.equals(current.getFactoryId(),input.factoryId())||!current.getPi().equals(input.pi())||pending.stream().anyMatch(i->!Objects.equals(i.sourceFactoryId(),input.factoryId())||!Objects.equals(i.sourceOrgId(),current.getOrgId())||!i.sourcePi().equals(input.pi())))throw new BusinessException(ALLOCATION_CHANGED);
  Map<String,Long> quantities=new TreeMap<>();for(int offset=0;offset<pending.size();offset+=500){var batch=pending.subList(offset,Math.min(pending.size(),offset+500));if(rows.allocate(task.id(),input.factoryId(),batch)!=batch.size())throw new BusinessException(ALLOCATION_CHANGED);for(var row:batch)quantities.merge(row.sourceEntryId(),1L,Math::addExact);progress.record(task.id(),task.executionEpoch(),offset+batch.size(),"WRITING");}
  quantities.forEach((entry,qty)->{if(rows.quota(input.billId(),entry,0,qty)!=1)throw new IllegalStateException("Missing allocation quota");});
  complete(task,pending.getFirst().seqDec(),pending.getLast().seqDec(),null,null,counter.lastSeq(),pending.getFirst().sn(),pending.getLast().sn());
 }
 private SnItemEntity item(TaskEntity task,String pi,String sn,Long sequence,String customer,String material,Long factory,String source,Long version,OrderBillEntity bill,String entry){
  return new SnItemEntity(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1),UUID.randomUUID().toString(),sn,pi,sequence==null?null:pi,sequence,customer,material,factory,source,(source.equals("IMPORT")?com.adtek.sn.constant.SnStatus.PRINTED:com.adtek.sn.constant.SnStatus.PENDING_ALLOC).name(),version,
   bill==null?null:bill.getSourceSystem(),bill==null?null:bill.getSourceAccount(),bill==null?null:bill.getSourceBillId(),entry,bill==null?null:bill.getId(),bill==null?"":bill.getBillNo(),pi,bill==null?null:bill.getOrgId(),bill==null?factory:bill.getFactoryId(),task.id());
 }
 private void write(TaskEntity task,List<SnItemEntity> batch,long processed){rows.items(batch);rows.keys(batch);progress.record(task.id(),task.executionEpoch(),processed,"WRITING");}
 private void complete(TaskEntity task,Long start,Long end,Long decoded,Long max,Long last,String first,String lastSn){if(rows.success(task.id(),start,end,decoded,max,last,first,lastSn)!=1)throw new IllegalStateException("Lost SN result");}
 public void ended(TaskEntity task,String state,Integer code){
  var work=rows.find(task.id());if(work==null)return;
  // 终态事实与PI占用释放加入framework的终态事务；独立失败审计不可使占用永久残留。
  control.lock();rows.lock(work.pi());rows.ended(task.id(),state,code);rows.release(work.pi(),task.id());
  if(state.equals("CANCELLED"))audit(task.userId(),permission(work.kind())+":cancel",task.id());
  else try{audit.failure(new ManagementAuditPort.Event(task.userId(),"SN初始化生成分配",permission(work.kind())+":failed","OTHER",task.id(),Map.of("errorCode",Integer.toString(code)),org.slf4j.MDC.get("traceId")));}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("SN terminal failure audit unavailable; durable task retains controlled error");}
 }
}
