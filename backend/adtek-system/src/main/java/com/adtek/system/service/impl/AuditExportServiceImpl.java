package com.adtek.system.service.impl;
import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import com.adtek.framework.task.*;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.system.convert.AuditExportConvert;
import com.adtek.system.dto.AuditExportCommandDTO;
import com.adtek.system.mapper.*;
import com.adtek.system.query.AuditQuery;
import com.adtek.system.service.*;
import com.adtek.system.service.support.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
@Service
public class AuditExportServiceImpl implements AuditExportService {
    private final DurableTaskService tasks;private final AuditQueryService query;private final CurrentActor actors;private final CommandValidator validator;
    private final AuditExportMapper rows;private final DurableFileMapper files;private final AuditExportConvert convert;private final ObjectMapper json;private final TaskSettings settings;private final OperLogMapper audit;
    public AuditExportServiceImpl(DurableTaskService tasks,AuditQueryService query,CurrentActor actors,CommandValidator validator,AuditExportMapper rows,DurableFileMapper files,AuditExportConvert convert,ObjectMapper json,TaskSettings settings,OperLogMapper audit) {
        this.tasks=tasks;this.query=query;this.actors=actors;this.validator=validator;this.rows=rows;this.files=files;this.convert=convert;this.json=json;this.settings=settings;this.audit=audit;
    }
    @Override public TaskVO submit(AuditExportCommandDTO command) {
        validator.requireValid(command);Actor actor=actors.requirePermission(AuditExportTaskHandler.ACTION);actors.requirePermission("system:audit:list");
        AuditQuery filter=new AuditQuery();
        try {filter.setFrom(Instant.parse(command.from()).toString());filter.setTo(Instant.parse(command.to()).toString());}
        catch(java.time.format.DateTimeParseException error) {throw ConfigurationPolicy.invalid();}
        filter.setAction(empty(command.action()));filter.setModule(empty(command.module()));filter.setActorUsername(empty(command.actorUsername()));filter.setUserId(empty(command.userId()));
        filter.setTraceId(empty(command.traceId())==null?null:TaskAccess.uuid(command.traceId()));filter.setSuccess(command.success());validator.requireValid(filter);
        if(!Instant.parse(filter.getFrom()).isBefore(Instant.parse(filter.getTo())) || java.time.Duration.between(Instant.parse(filter.getFrom()),Instant.parse(filter.getTo())).compareTo(java.time.Duration.ofDays(31))>0) {throw ConfigurationPolicy.invalid();}
        // TreeMap固定字段顺序，空字符串统一为null，时间转UTC，布尔false保留；不包含traceId追踪头或分页参数。
        Map<String,Object> input=new TreeMap<>();input.put("from",filter.getFrom());input.put("to",filter.getTo());input.put("action",filter.getAction());input.put("module",filter.getModule());
        input.put("actorUsername",filter.getActorUsername());input.put("userId",filter.getUserId());input.put("traceId",filter.getTraceId());input.put("success",filter.getSuccess());
        String scope=TaskDigests.sha256(actor.userId()+"/"+actor.securityVersion()+"/"+actor.grants().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->entry.getKey()+":"+entry.getValue().departments().all()+":"+new TreeSet<>(entry.getValue().departments().departmentIds())+":"+entry.getValue().departments().self()).toList());
        return tasks.submit(AuditExportTaskHandler.TYPE,command.operationId(),json.writeValueAsString(input),scope,task->{
            var events=query.exportSnapshot(filter);long bytes=AuditCsv.HEADER.getBytes(StandardCharsets.UTF_8).length;
            List<com.adtek.system.domain.AuditExportRowEntity> batch=new ArrayList<>();int ordinal=0;
            for(var event:events) {
                String line=AuditCsv.row(event);bytes+=line.getBytes(StandardCharsets.UTF_8).length;
                if(bytes>settings.fileBytes()) {throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);}
                batch.add(convert.row(event,task.id(),++ordinal,line));if(batch.size()==200) {rows.insert(batch);batch.clear();}
            }
            if(!batch.isEmpty()) {rows.insert(batch);}files.insert(task.id(),task.id()+".csv",events.size());
            var snapshot=audit.actorSnapshot(actor.userId());audit.management(actor.userId(),"操作日志导出","INSERT","system:audit:export:submit",task.id(),true,"{}",org.slf4j.MDC.get("traceId"),snapshot==null?null:snapshot.departmentId(),snapshot==null?null:snapshot.username());
        });
    }
    @Override public TaskVO operation(String operationId) {actors.requirePermission(AuditExportTaskHandler.ACTION);return tasks.operation(AuditExportTaskHandler.TYPE,operationId);}
    private String empty(String value) {return value==null || value.isEmpty()?null:value;}
}
