package com.adtek.sn.service.impl;
import com.adtek.common.core.*;
import com.adtek.common.annotation.Idempotent;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.AcquisitionDTO;
import com.adtek.sn.domain.*;
import com.adtek.sn.query.AcquireQuery;
import com.adtek.sn.service.PrintService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.PrintVO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

@Service public class PrintServiceImpl implements PrintService {
 private final AcquireEngine e;private final DurableTaskService tasks;private final DurableFileMapper files;private final CommandValidator validator;
 public PrintServiceImpl(AcquireEngine e,DurableTaskService tasks,DurableFileMapper files,CommandValidator validator){this.e=e;this.tasks=tasks;this.files=files;this.validator=validator;}
 @Override @Idempotent public TaskVO confirm(AcquisitionDTO command){validator.requireValid(command);long factory=SnPolicy.id(command.factoryId());String pi=AcquirePolicy.pi(command.pi());var actor=e.policy.require(e.policy.read(AcquirePolicy.PRINT),AcquirePolicy.PRINT,factory);e.policy.require(actor,AcquirePolicy.DOWNLOAD,factory);
  String input=e.json.writeValueAsString(new TreeMap<>(Map.of("v",1,"factoryId",Long.toString(factory),"pi",pi)));String scope=TaskDigests.sha256(actor.userId()+":"+factory);
  return tasks.submit("SN_PRINT_XLSX",command.operationId(),input,scope,task->{
   var fresh=e.policy.lock(actor,AcquirePolicy.PRINT,factory);e.policy.require(fresh,AcquirePolicy.DOWNLOAD,factory);var counter=e.work.lockPi(pi);if(counter.activeTaskId()!=null)throw new BusinessException(PI_BUSY);e.work.factory(factory);
   long count=e.rows.count(factory,pi,"TO_PRINT");e.work.resource(count);if(count==0)throw new BusinessException(PRINT_NOTHING);var factoryRow=e.work.factories.selectById(factory);long ordinal=0;LocalDate month=null;String after=null;Map<Long,RuleVersionEntity> rules=new HashMap<>();Map<String,AcquireBatchEntity> batches=new HashMap<>();
   while(true){var chunk=e.rows.candidates(factory,pi,"TO_PRINT",month,after,500);if(chunk.isEmpty())break;var members=new ArrayList<PrintMemberEntity>();
    // 成员核验按同一500枚窗口读取，避免每枚SN一次SQL；批次头仍逐头检查原工厂/PI。
    var fixed=new HashMap<String,AcquireMemberEntity>();for(var member:e.rows.membersForRows(chunk))fixed.put(member.batchId()+":"+member.genMonth()+":"+member.itemId(),member);
    for(var row:chunk){if(row.currentBatchId()==null)throw new BusinessException(ACQUIRE_CHANGED);var batch=batches.computeIfAbsent(row.currentBatchId(),e.rows::batchById);var original=fixed.get(row.currentBatchId()+":"+row.genMonth()+":"+row.id());
     if(batch==null||original==null||!row.sn().equals(original.sn())||batch.factoryId()!=factory||!pi.equals(batch.pi()))throw new BusinessException(ACQUIRE_CHANGED);
     members.add(new PrintMemberEntity(task.id(),++ordinal,row.genMonth(),row.id(),batch.id(),e.json.writeValueAsString(e.snapshot(row,factoryRow,rules))));}
    e.rows.printMemberBatch(members);if(e.rows.printedBatch(chunk,task.id())!=chunk.size())throw new BusinessException(ACQUIRE_CHANGED);var last=chunk.getLast();month=last.genMonth();after=last.id();
   }
   if(ordinal!=count)throw new BusinessException(ACQUIRE_CHANGED);
   // 两种产物各有同标识恢复任务；两份任务登记和唯一打印事实仍属于外层同一事务。
   String csvOperation=UUID.nameUUIDFromBytes(("SN_PRINT_CSV:"+task.id()).getBytes(StandardCharsets.UTF_8)).toString();
   tasks.submit("SN_PRINT_CSV",csvOperation,e.json.writeValueAsString(Map.of("printId",task.id())),scope,csv->{
    e.rows.print(new PrintEntity(task.id(),factory,factoryRow.getCode(),factoryRow.getName(),pi,count,fresh.userId(),TaskAccess.uuid(command.operationId()),csv.id(),null));
    file(csv.id(),task.id(),"csv",count);tasks.confirmBusiness(csv.id(),task.id());
   });
   file(task.id(),task.id(),"xlsx",count);e.fact(fresh,AcquirePolicy.PRINT,task.id(),Map.of("quantity",Long.toString(count),"pi",pi,"factoryId",Long.toString(factory),"origin","PAGE"));tasks.confirmBusiness(task.id(),task.id());
  });
 }
 private void file(String task,String print,String format,long count){files.insert(task,task+"."+format,count);files.metadata(task,"print-"+print+"."+format,format.equals("xlsx")?PrintFiles.XLSX:"text/csv;charset=UTF-8");}
 @Override public TaskVO operation(String id){return tasks.operation("SN_PRINT_XLSX",id);}
 private PrintVO view(PrintEntity print){var xlsx=files.find(print.id());var csv=files.find(print.csvTaskId());return e.convert.print(print,xlsx==null?null:xlsx.state().name(),csv==null?null:csv.state().name());}
 @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public PageResult<PrintVO> history(AcquireQuery query){validator.requireValid(query);var scope=e.policy.scope(e.policy.read(AcquirePolicy.VIEW),AcquirePolicy.VIEW,query,false);return new PageResult<>(e.rows.prints(scope,(long)(query.page()-1)*query.size(),query.size()).stream().map(this::view).toList(),Long.toString(e.rows.printCount(scope)),query.page(),query.size());}
 @Override @Transactional(readOnly=true) public PrintVO get(String id){var actor=e.policy.read(AcquirePolicy.VIEW);var print=e.rows.printByTask(TaskAccess.uuid(id));if(print==null)throw SnPolicy.missing();e.policy.require(actor,AcquirePolicy.VIEW,print.factoryId());return view(print);}
}
