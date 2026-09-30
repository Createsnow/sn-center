package com.adtek.sn.service.impl;
import com.adtek.common.core.Actor;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.sn.service.support.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.*;

/** 文件只消费已提交的固定打印成员；失败保留PRINTED，恢复不重做业务写或业务成功审计。 */
@Configuration public class PrintHandlers {
 private final AcquireEngine e;private final DurableFileMapper files;private final FileStorage storage;private final TaskSettings settings;
 public PrintHandlers(AcquireEngine e,DurableFileMapper files,FileStorage storage,TaskSettings settings){this.e=e;this.files=files;this.storage=storage;this.settings=settings;}
 @Bean TaskHandler snPrintXlsxHandler(){return handler("xlsx");}
 @Bean TaskHandler snPrintCsvHandler(){return handler("csv");}
 private TaskHandler handler(String format){return new TaskHandler(){
  public String type(){return "SN_PRINT_"+format.toUpperCase(java.util.Locale.ROOT);}public String action(){return format.equals("xlsx")?AcquirePolicy.PRINT:"sn:print:file:csv";}
  public void authorize(Actor actor,TaskEntity task){var print=e.rows.printByTask(task.id());if(print==null)throw SnPolicy.missing();e.policy.require(actor,AcquirePolicy.PRINT,print.factoryId());e.policy.require(actor,AcquirePolicy.DOWNLOAD,print.factoryId());}
  public boolean sharedFileAccess(){return true;}
  public void authorizeFile(Actor actor,TaskEntity task){var print=e.rows.printByTask(task.id());if(print==null)throw SnPolicy.missing();e.policy.require(actor,AcquirePolicy.DOWNLOAD,print.factoryId());}
  public String execute(TaskEntity task,Actor actor)throws Exception{
   var print=e.rows.printByTask(task.id());var fresh=e.policy.lock(actor,AcquirePolicy.PRINT,print.factoryId());e.policy.require(fresh,AcquirePolicy.DOWNLOAD,print.factoryId());var file=files.find(task.id());if(file==null)throw new IllegalStateException("Missing print file metadata");storage.cleanInterrupted(task.id());
   PrintFiles.Rows rows=consumer->{long ordinal=0;while(true){var chunk=e.rows.printMembers(print.id(),ordinal,500);if(chunk.isEmpty())break;for(var member:chunk){if(member.ordinal()!=++ordinal)throw new IllegalStateException("Broken print snapshot ordinal");consumer.accept(PrintFiles.cells(ordinal,e.json.readValue(member.snapshot(),AcquireSnapshot.class)));}e.progress.record(task.id(),task.executionEpoch(),ordinal,"WRITING");}if(ordinal!=print.quantity())throw new IllegalStateException("Incomplete print snapshot");};
   var published=storage.publish(task.id(),file.storageKey(),file.sha256(),output->{if(format.equals("xlsx"))PrintFiles.xlsx(output,print,settings.fileBytes(),rows);else{output.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});PrintFiles.csvRow(output,PrintFiles.HEADER);rows.each(values->{try{PrintFiles.csvRow(output,values);}catch(IOException error){throw new UncheckedIOException(error);}});}});
   if(file.sha256()!=null&&(!file.sha256().equals(published.digest())||file.byteSize()!=published.bytes()))throw new IllegalStateException("Immutable print changed");files.ready(task.id(),published.digest(),published.bytes());e.progress.record(task.id(),task.executionEpoch(),print.quantity(),"PUBLISHED");return file.id();
  }
  public void failed(TaskEntity task,int code){
   try{e.audit.failure(new com.adtek.framework.audit.ManagementAuditPort.Event(task.userId(),"SN打印文件","sn:print:file:failed","OTHER",task.id(),java.util.Map.of("errorCode",Integer.toString(code)),org.slf4j.MDC.get("traceId")));}
   catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(PrintHandlers.class).warn("Print file failure audit unavailable traceId={} exceptionType={}",org.slf4j.MDC.get("traceId"),error.getClass().getSimpleName());}
  }
  public void cancelled(TaskEntity task){throw new IllegalStateException("Confirmed print cannot be cancelled");}
 };}
}
