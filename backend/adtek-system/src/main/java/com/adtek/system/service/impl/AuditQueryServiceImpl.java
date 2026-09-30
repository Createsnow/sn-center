package com.adtek.system.service.impl;
import com.adtek.common.annotation.DataScope;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.system.convert.AuditConvert;
import com.adtek.system.domain.OperLogEntity;
import com.adtek.system.mapper.AuditQueryMapper;
import com.adtek.system.query.AuditQuery;
import com.adtek.system.service.AuditQueryService;
import com.adtek.system.service.support.*;
import com.adtek.system.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
@Service
public class AuditQueryServiceImpl implements AuditQueryService {
    private final AuditQueryMapper logs; private final AuditConvert convert; private final CommandValidator validator; private final ObjectMapper json;
    private final com.adtek.framework.security.CurrentActor actors;private final com.adtek.framework.task.TaskSettings settings;
    public AuditQueryServiceImpl(AuditQueryMapper logs,AuditConvert convert,CommandValidator validator,ObjectMapper json,com.adtek.framework.security.CurrentActor actors,com.adtek.framework.task.TaskSettings settings) {
        this.logs=logs;this.convert=convert;this.validator=validator;this.json=json;this.actors=actors;this.settings=settings;
    }
    @Override @Transactional(readOnly=true) @DataScope(resource="system:audit",permission="system:audit:list")
    public PageResult<AuditLogVO> list(AuditQuery query) {
        var where=where(query);
        var page=logs.selectPage(new Page<>(query.getPage(),query.getSize()),where);
        return new PageResult<>(page.getRecords().stream().map(convert::toView).toList(),Long.toString(page.getTotal()),query.getPage(),query.getSize());
    }
    @Override @Transactional(readOnly=true) @DataScope(resource="system:audit",permission="system:audit:list")
    public java.util.List<AuditLogVO> exportSnapshot(AuditQuery query) {
        var actor=actors.requirePermission("system:audit:export");var scope=actor.grants().get("system:audit:export").departments();
        var where=where(query);
        if(!scope.all()) {
            if(scope.departmentIds().isEmpty() && !scope.self()) {where.apply("1=0");}
            else {where.and(part->{
                if(!scope.departmentIds().isEmpty()) {part.in(OperLogEntity::getActorDeptId,scope.departmentIds());}
                if(scope.self()) {if(!scope.departmentIds().isEmpty()) {part.or();}part.eq(OperLogEntity::getUserId,actor.userId());}
            });}
        }
        // 一个SELECT的完整快照；多读一行只用于明确拒绝超限，不截断成成功。
        var result=logs.selectPage(new Page<OperLogEntity>(1,settings.exportRows()+1,false),where).getRecords();
        if(result.size()>settings.exportRows()) {throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);}
        return result.stream().map(convert::toView).toList();
    }
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperLogEntity> where(AuditQuery query) {
        var window=window(query);
        return Wrappers.<OperLogEntity>lambdaQuery()
            .select(OperLogEntity.class,field->!field.getProperty().equals("changes"))
            .ge(OperLogEntity::getCreateTime,window.from()).lt(OperLogEntity::getCreateTime,window.to())
            .eq(query.getAction()!=null,OperLogEntity::getAction,query.getAction())
            .eq(query.getModule()!=null,OperLogEntity::getModuleName,query.getModule())
            .eq(query.getSuccess()!=null,OperLogEntity::getSuccess,query.getSuccess())
            .eq(query.getActorUsername()!=null,OperLogEntity::getActorUsername,query.getActorUsername())
            .eq(query.getUserId()!=null,OperLogEntity::getUserId,query.getUserId()==null?null:ConfigurationPolicy.id(query.getUserId()))
            .eq(query.getTraceId()!=null,OperLogEntity::getTraceId,query.getTraceId()==null?null:query.getTraceId().toLowerCase(java.util.Locale.ROOT))
            .orderByDesc(OperLogEntity::getCreateTime,OperLogEntity::getId);
    }
    @Override @Transactional(readOnly=true) @DataScope(resource="system:audit",permission="system:audit:list")
    public AuditDetailVO detail(String id) {
        var entity=logs.selectById(ConfigurationPolicy.id(id));
        if(entity==null) { throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND); }
        var changes=AuditChanges.project(json,entity.getChanges());
        return new AuditDetailVO(convert.toView(entity),changes.changes(),changes.reason(),changes.failureCode(),changes.redacted());
    }
    @Override @Transactional(readOnly=true) @DataScope(resource="system:audit",permission="system:audit:list")
    public AuditOptionsVO options(AuditQuery query) {
        var window=window(query);var actions=logs.actions(window.from(),window.to());var modules=logs.modules(window.from(),window.to());
        if(actions.size()>200 || modules.size()>200) { throw new BusinessException(com.adtek.system.constant.ErrorCodeConstants.AUDIT_OPTIONS_LIMIT); }
        return new AuditOptionsVO(actions,modules);
    }
    private Window window(AuditQuery query) {
        validator.requireValid(query);
        try {
            if(query.getFrom()==null && query.getTo()==null) { var end=Instant.now();return new Window(end.minus(Duration.ofDays(7)),end); }
            if(query.getFrom()==null || query.getTo()==null) { throw ConfigurationPolicy.invalid(); }
            Instant from=Instant.parse(query.getFrom()),to=Instant.parse(query.getTo());
            if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0) { throw ConfigurationPolicy.invalid(); }
            return new Window(from,to);
        } catch(java.time.format.DateTimeParseException error) { throw ConfigurationPolicy.invalid(); }
    }
    private record Window(Instant from,Instant to) {}
}
