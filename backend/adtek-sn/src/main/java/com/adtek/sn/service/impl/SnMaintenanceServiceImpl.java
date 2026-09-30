package com.adtek.sn.service.impl;

import com.adtek.sn.dto.MaintenanceCommandDTO;
import com.adtek.sn.mapper.MaintenanceMapper;
import com.adtek.sn.service.SnMaintenanceService;
import com.adtek.sn.vo.MaintenanceResultVO;
import com.adtek.sn.service.support.ArchiveRestoreProof;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/** 同一物理连接持有命名锁；DDL自动提交与DML事务分别处理，不进入页面Spring事务。 */
public final class SnMaintenanceServiceImpl implements SnMaintenanceService {
    private final DataSource source;
    private final String schema;
    private final MaintenanceMapper rows;
    private final ArchiveRestoreProof proofs;
    private final ObjectMapper json=new ObjectMapper();
    public SnMaintenanceServiceImpl(DataSource source,String allowedSchema,MaintenanceMapper rows,ArchiveRestoreProof proofs){
        if(allowedSchema==null||!allowedSchema.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("MAINTENANCE_SCHEMA_ALLOWLIST");
        this.source=Objects.requireNonNull(source);this.schema=allowedSchema;this.rows=rows;this.proofs=proofs;
    }
    @Override public MaintenanceResultVO execute(MaintenanceCommandDTO command){
        Objects.requireNonNull(command);
        var proof=command.action()==MaintenanceCommandDTO.Action.ARCHIVE_FINALIZE?proofs.verify(command.proofDirectory(),command.month()):null;
        String request=MaintenanceMapper.digest("v1|"+command.action()+"|"+command.month()+"|"+command.monthsAhead()+"|"+command.rowLimit()+(proof==null?"":"|"+proof.proofDigest()));
        String lock="adtek:maint:"+MaintenanceMapper.digest(schema).substring(0,40);
        try(var c=source.getConnection()){
            if(!schema.equals(c.getCatalog())||!schema.equals(rows.scalar(c,"SELECT DATABASE()")))throw new IllegalStateException("MAINTENANCE_SCHEMA_MISMATCH");
            if(c.getMetaData().getDatabaseMajorVersion()!=8||c.getMetaData().getDatabaseMinorVersion()!=4)throw new IllegalStateException("MAINTENANCE_MYSQL_VERSION");
            if(proof!=null&&(!schema.equals(proof.schema())||!proof.sourceUuid().equals(rows.scalar(c,"SELECT @@server_uuid"))))throw new IllegalStateException("ARCHIVE_RESTORE_SOURCE_MISMATCH");
            rows.update(c,"SET SESSION time_zone='+00:00'");rows.update(c,"SET SESSION lock_wait_timeout=5");rows.update(c,"SET SESSION innodb_lock_wait_timeout=5");
            if(!"1".equals(rows.scalar(c,"SELECT GET_LOCK(?,0)",lock)))throw new IllegalStateException("MAINTENANCE_BUSY");
            boolean recorded=false;
            try{
                if(command.apply()){
                    var old=rows.run(c,command.runId());
                    if(old!=null&&!request.equals(old.digest()))throw new IllegalStateException("MAINTENANCE_RUN_CONFLICT");
                    if(old!=null&&"SUCCEEDED".equals(old.state()))return json.readValue(old.result(),MaintenanceResultVO.class);
                    if(old==null)rows.update(c,"INSERT INTO sn_maintenance_run(run_id,action,request_digest,state) VALUES(?,?,?,'RUNNING')",command.runId(),command.action().name(),request);
                    else rows.update(c,"UPDATE sn_maintenance_run SET state='RUNNING',error_type=NULL,finished_at=NULL,attempts=attempts+1 WHERE run_id=?",command.runId());
                    recorded=true;
                }
                var result=perform(c,command,proof);
                if(command.apply())rows.update(c,"UPDATE sn_maintenance_run SET state='SUCCEEDED',result=?,finished_at=UTC_TIMESTAMP(6) WHERE run_id=?",json.writeValueAsString(result),command.runId());
                // DML与终态同事务；写终态失败时也回滚已复制/清理的内容，避免成功结果丢失后重复处理。
                if(!c.getAutoCommit()){if(command.apply())c.commit();else c.rollback();c.setAutoCommit(true);}
                return result;
            }catch(Exception failure){
                if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}
                if(recorded)rows.update(c,"UPDATE sn_maintenance_run SET state='FAILED',error_type=?,finished_at=UTC_TIMESTAMP(6) WHERE run_id=?",failure.getClass().getSimpleName(),command.runId());
                if(failure instanceof SQLException sql)throw sql;
                if(failure instanceof RuntimeException runtime)throw runtime;
                throw new IllegalStateException("MAINTENANCE_FAILED");
            }finally{
                if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}
                rows.scalar(c,"SELECT RELEASE_LOCK(?)",lock);
            }
        }catch(SQLException failure){
            // 原始SQL异常可能包含行内容/地址，命令层只接收稳定类别及数据库错误号。
            throw new IllegalStateException("MAINTENANCE_SQL_"+failure.getErrorCode());
        }
    }
    private MaintenanceResultVO perform(Connection c,MaintenanceCommandDTO cmd,ArchiveRestoreProof.Verified proof)throws SQLException{
        var plan=new ArrayList<String>();long affected=0;String sourceHash=null,archiveHash=null;
        String state=cmd.apply()?"SUCCEEDED":"DRY_RUN";
        switch(cmd.action()){
            case PARTITIONS -> {
                plan.addAll(rows.partitionPlan(c,cmd.month(),cmd.monthsAhead()));
                if(cmd.apply()){
                    rows.update(c,"UPDATE sn_maintenance_run SET result=? WHERE run_id=?",json.writeValueAsString(result(cmd,"RUNNING",plan,0,null,null)),cmd.runId());
                    for(String ddl:plan){rows.update(c,ddl);affected++;}
                    if(!rows.partitionPlan(c,cmd.month(),cmd.monthsAhead()).isEmpty())throw new IllegalStateException("PARTITION_POSTCHECK");
                }
            }
            case CLEAN_PREVIEWS -> {
                plan.add("DELETE expired unbound sn_preview, limited to "+cmd.rowLimit());
                if(cmd.apply()){
                    c.setAutoCommit(false);affected=rows.cleanPreviews(c,cmd.rowLimit());
                }else affected=Math.min(cmd.rowLimit(),rows.expiredPreviews(c));
            }
            case ARCHIVE_COPY,ARCHIVE_VERIFY -> {
                if(!cmd.month().isBefore(YearMonth.now(ZoneOffset.UTC)))throw new IllegalArgumentException("ARCHIVE_MONTH_NOT_CLOSED");
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
                if(rows.auditCount(c,"sn_audit",cmd.month())>cmd.rowLimit()||rows.auditCount(c,"sn_audit_archive",cmd.month())>cmd.rowLimit())throw new IllegalStateException("ARCHIVE_ROW_LIMIT");
                plan.add("COPY/VERIFY UTC month "+cmd.month()+"; retain online and permanent archive");
                if(cmd.action()==MaintenanceCommandDTO.Action.ARCHIVE_COPY&&cmd.apply())rows.copyAudit(c,cmd.month());
                var original=rows.auditDigest(c,"sn_audit",cmd.month());var archive=rows.auditDigest(c,"sn_audit_archive",cmd.month());
                affected=original.rows();sourceHash=original.sha256();archiveHash=archive.sha256();
                if(cmd.apply()||cmd.action()==MaintenanceCommandDTO.Action.ARCHIVE_VERIFY){
                    if(!original.equals(archive))throw new IllegalStateException("ARCHIVE_CONTENT_MISMATCH");
                    state="COPIED_VERIFIED_ONLINE_RETAINED";
                }
            }
            case ARCHIVE_FINALIZE -> {
                if(!cmd.month().isBefore(YearMonth.now(ZoneOffset.UTC)))throw new IllegalArgumentException("ARCHIVE_MONTH_NOT_CLOSED");
                if(proof.rows()>cmd.rowLimit())throw new IllegalStateException("ARCHIVE_ROW_LIMIT");
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
                if(rows.auditCount(c,"sn_audit",cmd.month())>cmd.rowLimit()||rows.auditCount(c,"sn_audit_archive",cmd.month())>cmd.rowLimit())throw new IllegalStateException("ARCHIVE_ROW_LIMIT");
                var online=rows.auditDigest(c,"sn_audit",cmd.month(),true);var archive=rows.auditDigest(c,"sn_audit_archive",cmd.month(),true);
                if(!online.equals(archive)||online.rows()!=proof.rows()||!online.sha256().equals(proof.auditDigest()))throw new IllegalStateException("ARCHIVE_RESTORE_SNAPSHOT_CHANGED");
                plan.add("REMOVE online UTC month "+cmd.month()+"; retain verified permanent archive; proof="+proof.proofDigest());
                affected=online.rows();sourceHash=online.sha256();archiveHash=archive.sha256();
                if(cmd.apply()){
                    int removed=rows.update(c,"DELETE FROM sn_audit WHERE created_at>=? AND created_at<?",cmd.month().atDay(1),cmd.month().plusMonths(1).atDay(1));
                    if(removed!=affected)throw new IllegalStateException("ARCHIVE_DELETE_COUNT_CHANGED");
                    state="ARCHIVED_ONLINE_REMOVED";
                }
            }
        }
        return result(cmd,state,plan,affected,sourceHash,archiveHash);
    }
    private MaintenanceResultVO result(MaintenanceCommandDTO cmd,String state,List<String> plan,long affected,String sourceHash,String archiveHash){
        return new MaintenanceResultVO(cmd.runId(),cmd.action().name(),cmd.apply(),state,schema,List.copyOf(plan),Long.toString(affected),sourceHash,archiveHash);
    }
}
