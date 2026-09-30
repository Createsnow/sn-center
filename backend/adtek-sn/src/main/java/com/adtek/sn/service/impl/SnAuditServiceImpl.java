package com.adtek.sn.service.impl;
import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.convert.*;
import com.adtek.sn.domain.SnExportEntity;
import com.adtek.sn.dto.SnAuditExportDTO;
import com.adtek.sn.query.SnAuditQuery;
import com.adtek.sn.service.SnAuditService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service public class SnAuditServiceImpl implements SnAuditService {
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private final SnAuditAccess e;private final DurableTaskService tasks;private final SnAuditConvert convert;private final SnQueryConvert exports;private final SnCursor cursors;private final CommandValidator validator;private final SnWorkSettings settings;private final SnAuditDates dates;
 public SnAuditServiceImpl(SnAuditAccess e,DurableTaskService tasks,SnAuditConvert convert,SnQueryConvert exports,SnCursor cursors,CommandValidator validator,SnWorkSettings settings,SnAuditDates dates){this.e=e;this.tasks=tasks;this.convert=convert;this.exports=exports;this.cursors=cursors;this.validator=validator;this.settings=settings;this.dates=dates;}
 record Window(SnAuditQuery query,Instant from,Instant to,String keyword){}
 Window normalized(SnAuditQuery q){validator.requireValid(q);LocalDate to=q.dateTo()==null?dates.today():q.dateTo(),from=q.dateFrom()==null?to.minusDays(30):q.dateFrom();if(from.isAfter(to)||from.isBefore(LocalDate.of(1970,1,1))||to.isAfter(LocalDate.of(9998,12,30)))throw SnPolicy.invalid();
  String factory=SnPolicy.optional(q.factoryId()),result=SnPolicy.optional(q.result());if(factory!=null)SnPolicy.id(factory);if(result!=null&&!Set.of("SUCCESS","FAILED").contains(result))throw SnPolicy.invalid();
  var normalized=new SnAuditQuery(from,to,SnPolicy.optional(q.action()),SnPolicy.optional(q.operator()),factory,SnPolicy.optional(q.pi()),SnPolicy.optional(q.source()),result,SnPolicy.optional(q.batchId()),SnPolicy.optional(q.keyword()),q.storage());return new Window(normalized,from.atStartOfDay(ZONE).toInstant(),to.plusDays(1).atStartOfDay(ZONE).toInstant(),SnPolicy.like(q.keyword()));
 }
 private com.adtek.sn.mapper.SnQueryMapper.Scope scope(Actor actor,String permission,SnAuditQuery q){var scope=e.policy.basic(actor,permission);if(q.factoryId()!=null&&!actor.grants().get(permission).permitsFactory(SnPolicy.id(q.factoryId())))throw AcquirePolicy.forbidden();return scope;}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public CursorResult<SnAuditVO> items(SnAuditQuery query,String cursor,int size){if(size<1||size>1000)throw SnPolicy.invalid();var w=normalized(query);var actor=e.policy.read(SnAuditAccess.VIEW);var range=scope(actor,SnAuditAccess.VIEW,w.query());String binding=cursors.binding(actor,"sn-audit:"+e.json.writeValueAsString(w.query()));var position=cursors.read(cursor,binding);Instant time=null;String after=null;
  if(position.id()!=null){try{String[] parts=position.id().split("[|]",-1);if(parts.length!=2)throw SnPolicy.invalid();time=Instant.parse(parts[0]);after=TaskAccess.uuid(parts[1]);}catch(RuntimeException error){throw SnPolicy.invalid();}}
  var found=e.rows.page(range,w.query(),w.from(),w.to(),w.keyword(),time,after,size+1);boolean more=found.size()>size;var page=found.stream().limit(size).toList();var last=page.isEmpty()?null:page.getLast();return new CursorResult<>(page.stream().map(convert::view).toList(),more?cursors.next(binding,0,null,last.getCreatedAt()+"|"+last.getId()):null);
 }
 @Override @Transactional(readOnly=true) public SnCountVO count(SnAuditQuery query){var w=normalized(query);var actor=e.policy.read(SnAuditAccess.VIEW);return new SnCountVO("TOTAL",Long.toString(e.rows.count(scope(actor,SnAuditAccess.VIEW,w.query()),w.query(),w.from(),w.to(),w.keyword())));}
 @Override @Transactional(readOnly=true) public SnAuditVO get(String id,Instant createdAt){TaskAccess.uuid(id);if(createdAt==null)throw SnPolicy.invalid();LocalDate day=createdAt.atZone(ZONE).toLocalDate();var q=new SnAuditQuery(day,day,null,null,null,null,null,null,null,null,"ALL");var w=normalized(q);var actor=e.policy.read(SnAuditAccess.VIEW);var row=e.rows.get(scope(actor,SnAuditAccess.VIEW,q),q,w.from(),w.to(),null,createdAt,id);if(row==null)throw SnPolicy.missing();return convert.view(row);}
 @Override public SnAuditMetaVO meta(){e.policy.read(SnAuditAccess.VIEW);return new SnAuditMetaVO(true,Integer.toString(settings.getMaxBatchQuantity()),30,true);}
 static String type(String format){return "SN_AUDIT_"+SnQueryServiceImpl.format(format);}
 @Override @Idempotent public TaskVO export(SnAuditExportDTO command){validator.requireValid(command);String format=SnQueryServiceImpl.format(command.format());var w=normalized(command.filters());var actor=e.policy.read(SnAuditAccess.EXPORT);e.policy.require(actor,SnAuditAccess.VIEW);String payload=e.json.writeValueAsString(w.query());var q=w.query();
  // 相对日期是调用者意图；次日重试同号仍返回首次快照，不能把今天重新解析后的日期变成异载荷。
  var intent=new SnAuditQuery(command.filters().dateFrom(),command.filters().dateTo(),q.action(),q.operator(),q.factoryId(),q.pi(),q.source(),q.result(),q.batchId(),q.keyword(),q.storage());
  String canonical=e.json.writeValueAsString(new TreeMap<>(Map.of("v",1,"format",format,"filtersDigest",TaskDigests.sha256(e.json.writeValueAsString(intent)))));
  return tasks.submit(type(format),command.operationId(),canonical,TaskDigests.sha256(actor.userId()+":"+actor.securityVersion()+":"+payload),task->{var fresh=e.authorization.lockCurrent(actor);var read=scope(fresh,SnAuditAccess.VIEW,w.query());var exporting=scope(fresh,SnAuditAccess.EXPORT,w.query());e.rows.export(new SnExportEntity(task.id(),fresh.userId(),format,payload,0,null));
   // 失败审计可独立并发追加；单条INSERT SELECT用一个一致读视图固定全部行，不能先计数再跨语句分页猜快照。
   long count=e.rows.snapshot(read,w.query(),w.from(),w.to(),w.keyword(),exporting,task.id(),settings.getMaxBatchQuantity()+1);if(count>settings.getMaxBatchQuantity())throw new BusinessException(com.adtek.sn.constant.ErrorCodeConstants.RESOURCE_LIMIT);e.rows.quantity(task.id(),count);
   String ext=format.toLowerCase(Locale.ROOT);e.files.insert(task.id(),task.id()+"."+ext,count);e.files.metadata(task.id(),"sn-audit-"+task.id()+"."+ext,format.equals("CSV")?"text/csv;charset=UTF-8":PrintFiles.XLSX);tasks.confirmBusiness(task.id(),task.id());e.audit.success(new com.adtek.framework.audit.ManagementAuditPort.Event(fresh.userId(),"SN操作痕迹","sn:audit:export:submit","OTHER",task.id(),Map.of("quantity",Long.toString(count),"format",format),org.slf4j.MDC.get("traceId")));
  });
 }
 @Override public TaskVO operation(String format,String operationId){return tasks.operation(type(format),operationId);}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public PageResult<SnExportVO> exports(int page,int size){if(page<1||size<1||size>100)throw SnPolicy.invalid();var actor=e.policy.read(SnAuditAccess.EXPORT);e.policy.require(actor,SnAuditAccess.VIEW);return new PageResult<>(e.rows.history(actor.userId(),(long)(page-1)*size,size).stream().map(row->{e.authorize(actor,row.taskId());var file=e.files.find(row.taskId());if(file==null)throw new IllegalStateException("Missing audit export file");return exports.export(row,file.state().name());}).toList(),Long.toString(e.rows.historyCount(actor.userId())),page,size);}
}
