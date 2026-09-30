package com.adtek.sn.service.impl;

import com.adtek.sn.dto.LegacyStageCommandDTO;
import com.adtek.sn.mapper.LegacyStageMapper;
import com.adtek.sn.service.LegacyStageService;
import com.adtek.sn.service.support.LegacySnapshotPackage;
import com.adtek.sn.dto.LegacyStageRowDTO;
import com.adtek.sn.vo.LegacyStageResultVO;
import com.adtek.sn.vo.LegacySnapshotManifestVO.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/** ETL阶段按块提交，检查点与原行原子；不得复用到线上生成/转厂等要求整批原子的业务命令。 */
public final class LegacyStageServiceImpl implements LegacyStageService {
    private final DataSource target;private final String schema;private final Path root;private final LegacyStageMapper rows;
    private final ObjectMapper json=new ObjectMapper();
    public LegacyStageServiceImpl(DataSource target,String schema,Path root,LegacyStageMapper rows){
        if(schema==null||!schema.matches("[a-z][a-z0-9_]{0,63}")||root==null)throw new IllegalArgumentException("LEGACY_TARGET_ALLOWLIST");
        this.target=target;this.schema=schema;this.root=root;this.rows=rows;
    }
    @Override public LegacyStageResultVO stage(LegacyStageCommandDTO cmd){
        try{
            var snapshot=new LegacySnapshotPackage(root,cmd.snapshotId());var manifest=snapshot.manifest();
            // 写目标前验证所有行结构，非法/截断文件不能留下部分导入；后续逐块仍再次验证摘要。
            for(var table:manifest.tables())snapshot.rows(table,row->{});
            try(var c=target.getConnection()){
                if(!schema.equals(c.getCatalog())||!schema.equals(rows.scalar(c,"SELECT DATABASE()")))throw failure("TARGET_MISMATCH");
                if(c.getMetaData().getDatabaseMajorVersion()!=8||c.getMetaData().getDatabaseMinorVersion()!=4)throw failure("TARGET_VERSION");
                if(manifest.sourceServerUuid().equals(rows.scalar(c,"SELECT @@server_uuid")))throw failure("TARGET_IS_SOURCE_INSTANCE");
                if(!Set.of("14","15","16","17").contains(rows.scalar(c,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")))throw failure("TARGET_MIGRATION_VERSION");
                long count=manifest.tables().stream().mapToLong(x->Long.parseLong(x.rows())).sum();
                var result=new LegacyStageResultVO(cmd.runId(),cmd.apply()?"STAGED":"DRY_RUN",cmd.apply(),schema,manifest.sourceFingerprint(),cmd.mappingVersion(),Long.toString(count),manifest.issues(),false);
                if(!cmd.apply())return result;
                String lock="adtek:legacy:"+LegacySnapshotPackage.sha(schema.getBytes(StandardCharsets.UTF_8)).substring(0,40);
                rows.update(c,"SET SESSION time_zone='+00:00'");rows.update(c,"SET SESSION innodb_lock_wait_timeout=5");
                if(!"1".equals(rows.scalar(c,"SELECT GET_LOCK(?,0)",lock)))throw failure("BUSY");
                boolean recorded=false;
                try{
                    var old=rows.run(c,cmd.runId());
                    if(old!=null&&(!old.fingerprint().equals(manifest.sourceFingerprint())||!old.manifest().equals(snapshot.manifestDigest())||!old.mapping().equals(cmd.mappingVersion())))throw failure("RUN_CONFLICT");
                    if(old==null)rows.update(c,"INSERT INTO sn_legacy_migration_run(run_id,source_fingerprint,manifest_digest,mapping_version,source_schema,source_server_uuid,source_zone,state,source_manifest) VALUES(?,?,?,?,?,?,?,'STAGING',?)",
                            cmd.runId(),manifest.sourceFingerprint(),snapshot.manifestDigest(),cmd.mappingVersion(),manifest.sourceSchema(),manifest.sourceServerUuid(),manifest.sourceZone(),snapshot.manifestJson());
                    else if(!Set.of("STAGED","FAILED","STAGING").contains(old.state()))throw failure("RUN_STATE");
                    else rows.update(c,"UPDATE sn_legacy_migration_run SET state='STAGING',error_type=NULL,updated_at=UTC_TIMESTAMP(6) WHERE run_id=?",cmd.runId());
                    recorded=true;
                    for(var table:manifest.tables())stageTable(c,cmd,snapshot,table);
                    rows.update(c,"UPDATE sn_legacy_migration_run SET state='STAGED',result=?,updated_at=UTC_TIMESTAMP(6) WHERE run_id=?",json.writeValueAsString(result),cmd.runId());
                    return result;
                }catch(Exception error){
                    if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}
                    if(recorded)rows.update(c,"UPDATE sn_legacy_migration_run SET state='FAILED',error_type=?,updated_at=UTC_TIMESTAMP(6) WHERE run_id=?",error.getClass().getSimpleName(),cmd.runId());
                    if(error instanceof SQLException sql)throw sql;if(error instanceof IOException io)throw io;if(error instanceof RuntimeException runtime)throw runtime;
                    throw failure("STAGE_FAILED");
                }finally{if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}rows.scalar(c,"SELECT RELEASE_LOCK(?)",lock);}
            }
        }catch(SQLException error){throw failure("SQL_"+error.getErrorCode());}catch(IOException error){throw failure("FILE_IO");}
    }
    private void stageTable(Connection c,LegacyStageCommandDTO cmd,LegacySnapshotPackage snapshot,Table table)throws SQLException,IOException{
        var checkpoint=rows.checkpoint(c,cmd.runId(),table.name());long original=0;
        if(checkpoint==null){
            rows.update(c,"INSERT INTO sn_legacy_migration_table(run_id,table_name,source_digest,source_rows) VALUES(?,?,?,?)",cmd.runId(),table.name(),table.sha256(),Long.parseLong(table.rows()));
        }else{
            if(!checkpoint.digest().equals(table.sha256())||checkpoint.sourceRows()!=Long.parseLong(table.rows()))throw failure("CHECKPOINT_CONFLICT");
            original=checkpoint.ordinal();
        }
        if(rows.count(c,cmd.runId(),table.name())!=original||rows.maximum(c,cmd.runId(),table.name())!=original)throw failure("CHECKPOINT_ROWS_CHANGED");
        final long committed=original;var batch=new ArrayList<LegacyStageRowDTO>();
        snapshot.rows(table,row->{batch.add(row);if(batch.size()==cmd.chunkSize()){flush(c,cmd,snapshot,table,batch,committed);batch.clear();}});
        if(!batch.isEmpty())flush(c,cmd,snapshot,table,batch,committed);
        if(rows.count(c,cmd.runId(),table.name())!=Long.parseLong(table.rows()))throw failure("STAGED_COUNT_MISMATCH");
        rows.update(c,"UPDATE sn_legacy_migration_table SET state='STAGED' WHERE run_id=? AND table_name=?",cmd.runId(),table.name());
    }
    private void flush(Connection c,LegacyStageCommandDTO cmd,LegacySnapshotPackage snapshot,Table table,List<LegacyStageRowDTO> batch,long committed)throws SQLException{
        var existing=rows.existing(c,cmd.runId(),table.name(),batch.getFirst().ordinal(),batch.getLast().ordinal());var insert=new ArrayList<LegacyStageRowDTO>();
        for(var row:batch){
            var old=existing.remove(row.ordinal());
            if(row.ordinal()<=committed){
                if(old==null||!old.key().equals(row.key())||!old.digest().equals(row.digest())||!snapshot.payloadDigest(table,old.payload()).equals(row.digest()))throw failure("STAGE_ROW_MISMATCH");
            }else{if(old!=null)throw failure("CHECKPOINT_ROWS_CHANGED");insert.add(row);}
        }
        if(!existing.isEmpty())throw failure("STAGE_ROW_MISMATCH");
        if(insert.isEmpty())return;
        c.setAutoCommit(false);rows.insert(c,cmd.runId(),table.name(),insert);
        if(rows.update(c,"UPDATE sn_legacy_migration_table SET last_ordinal=?,state='STAGING' WHERE run_id=? AND table_name=?",batch.getLast().ordinal(),cmd.runId(),table.name())!=1)throw failure("CHECKPOINT_MISSING");
        c.commit();c.setAutoCommit(true);
    }
    private static IllegalStateException failure(String code){return new IllegalStateException("LEGACY_"+code);}
}
