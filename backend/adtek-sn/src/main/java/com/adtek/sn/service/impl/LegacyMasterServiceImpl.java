package com.adtek.sn.service.impl;

import com.adtek.framework.security.LegacyIdentityPort;
import com.adtek.sn.dto.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.LegacyMasterService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/** 原始staging及来源包再次逐行核对后，主数据/身份/映射/证明单事务提交；没有启用/切换捷径。 */
public final class LegacyMasterServiceImpl implements LegacyMasterService {
    private final DataSource target;private final String schema;private final Path root;private final LegacyIdentityPort identities;
    private final LegacyStageMapper stage;private final LegacyMasterMapper rows;private final ObjectMapper json=new ObjectMapper();
    public LegacyMasterServiceImpl(DataSource target,String schema,Path root,LegacyIdentityPort identities,LegacyStageMapper stage,LegacyMasterMapper rows){
        if(schema==null||!schema.matches("[a-z][a-z0-9_]{0,63}"))throw fail("TARGET_ALLOWLIST");this.target=target;this.schema=schema;this.root=root;this.identities=identities;this.stage=stage;this.rows=rows;
    }
    @Override public LegacyMasterResultVO promote(LegacyMasterCommandDTO cmd){
        try{
            var snapshot=new LegacySnapshotPackage(root,cmd.snapshotId());var plan=new LegacyMasterPlan(snapshot);identities.validate(plan.users());var manifest=snapshot.manifest();String digest=plan.digest(manifest.sourceFingerprint());
            var result=new LegacyMasterResultVO(cmd.runId(),cmd.apply()?"MASTER_STAGED":"DRY_RUN",LegacyMasterPlan.VERSION,manifest.sourceFingerprint(),digest,Long.toString(plan.count("sn_factory")),Long.toString(plan.users().size()),Long.toString(plan.count("sn_rule")),Long.toString(plan.count("sn_rule_version")),Integer.toString(plan.unknownActors()),manifest.issues(),false,false);
            try(var c=target.getConnection()){
                if(!schema.equals(c.getCatalog())||!schema.equals(stage.scalar(c,"SELECT DATABASE()")))throw fail("TARGET_MISMATCH");
                if(c.getMetaData().getDatabaseMajorVersion()!=8||c.getMetaData().getDatabaseMinorVersion()!=4)throw fail("TARGET_VERSION");
                if(manifest.sourceServerUuid().equals(stage.scalar(c,"SELECT @@server_uuid")))throw fail("TARGET_IS_SOURCE_INSTANCE");
                if(!Set.of("15","16","17").contains(stage.scalar(c,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")))throw fail("TARGET_MIGRATION_VERSION");
                stage.update(c,"SET SESSION time_zone='+00:00'");stage.update(c,"SET SESSION innodb_lock_wait_timeout=5");String lock="adtek:legacy:"+LegacySnapshotPackage.sha(schema.getBytes(StandardCharsets.UTF_8)).substring(0,40);
                if(!"1".equals(stage.scalar(c,"SELECT GET_LOCK(?,0)",lock)))throw fail("BUSY");
                try{
                    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);identities.lock(c);
                    var run=stage.run(c,cmd.runId());if(run==null||!"STAGED".equals(run.state())||!run.fingerprint().equals(manifest.sourceFingerprint())||!run.manifest().equals(snapshot.manifestDigest())||!"legacy-v1-staging-v1".equals(run.mapping()))throw fail("STAGED_RUN_REQUIRED");
                    verifyStage(c,cmd.runId(),snapshot);
                    String previous=stage.scalar(c,"SELECT plan_digest FROM sn_legacy_master_run WHERE run_id=?",cmd.runId());
                    if(previous!=null){if(!previous.equals(digest))throw fail("MASTER_PLAN_CONFLICT");verifyImported(c,cmd,snapshot,plan);c.rollback();return result;}
                    if(!"0".equals(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_master_run")))throw fail("TARGET_NOT_EMPTY");
                    rows.requireNoBusiness(c);com.adtek.framework.task.LegacyTaskGuard.requireEmpty(c);identities.requireEmpty(c);for(String table:rows.tables())if(rows.count(c,table)!=0)throw fail("TARGET_NOT_EMPTY");
                    if(!"0".equals(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_id_map WHERE run_id=?",cmd.runId())))throw fail("MASTER_MAP_CONFLICT");
                    if(!cmd.apply()){c.rollback();return result;}
                    identities.insert(c,plan.users());for(var row:plan.rows())rows.insert(c,row);mappings(c,cmd.runId(),plan,true);verify(c,cmd.runId(),plan);
                    stage.update(c,"INSERT INTO sn_legacy_master_run(run_id,mapping_version,source_fingerprint,plan_digest,result) VALUES(?,?,?,?,?)",cmd.runId(),LegacyMasterPlan.VERSION,manifest.sourceFingerprint(),digest,json.writeValueAsString(result));
                    c.commit();return result;
                }finally{if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}stage.scalar(c,"SELECT RELEASE_LOCK(?)",lock);}
            }
        }catch(SQLException error){throw fail("MASTER_SQL_"+error.getErrorCode());}catch(IOException error){throw fail("FILE_IO");}
    }
    private void verifyStage(Connection c,String run,LegacySnapshotPackage snapshot)throws IOException,SQLException{
        if(!"19".equals(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_migration_table WHERE run_id=? AND state='STAGED'",run))||!"19".equals(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_migration_table WHERE run_id=?",run)))throw fail("CHECKPOINT_ROWS_CHANGED");
        long expected=snapshot.manifest().tables().stream().mapToLong(t->Long.parseLong(t.rows())).sum();if(Long.parseLong(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_row WHERE run_id=?",run))!=expected)throw fail("CHECKPOINT_ROWS_CHANGED");
        for(var table:snapshot.manifest().tables()){
            var checkpoint=stage.checkpoint(c,run,table.name());long count=Long.parseLong(table.rows());
            if(checkpoint==null||checkpoint.ordinal()!=count||checkpoint.sourceRows()!=count||!checkpoint.digest().equals(table.sha256())||stage.count(c,run,table.name())!=count||stage.maximum(c,run,table.name())!=count)throw fail("CHECKPOINT_ROWS_CHANGED");
            var batch=new ArrayList<LegacyStageRowDTO>();snapshot.rows(table,row->{batch.add(row);if(batch.size()==500){verifyChunk(c,run,snapshot,table,batch);batch.clear();}});if(!batch.isEmpty())verifyChunk(c,run,snapshot,table,batch);
        }
    }
    private void verifyChunk(Connection c,String run,LegacySnapshotPackage snapshot,LegacySnapshotManifestVO.Table table,List<LegacyStageRowDTO> batch)throws SQLException{
        var actual=stage.existing(c,run,table.name(),batch.getFirst().ordinal(),batch.getLast().ordinal());if(actual.size()!=batch.size())throw fail("STAGE_ROW_MISMATCH");
        for(var row:batch){var old=actual.get(row.ordinal());if(old==null||!old.key().equals(row.key())||!old.digest().equals(row.digest())||!snapshot.payloadDigest(table,old.payload()).equals(row.digest()))throw fail("STAGE_ROW_MISMATCH");}
    }
    private void mappings(Connection c,String run,LegacyMasterPlan plan,boolean insert)throws SQLException{
        for(var row:plan.rows())rows.mapping(c,run,row.sourceTable(),row.sourceKey(),row.table(),rows.targetKey(row),row.evidenceDigest(),insert);
        for(var user:plan.users()){var source=plan.userSources().get(user.id());String evidence=LegacySnapshotPackage.sha((LegacyMasterPlan.VERSION+"\n"+source.digest()).getBytes(StandardCharsets.UTF_8));rows.mapping(c,run,"sn_user",source.key(),"sys_user",Map.of("id",Long.toString(user.id())),evidence,insert);}
    }
    private void verify(Connection c,String run,LegacyMasterPlan plan)throws SQLException{
        identities.verify(c,plan.users());for(String table:rows.tables())if(rows.count(c,table)!=plan.count(table))throw fail("MASTER_TARGET_CHANGED");for(var row:plan.rows())rows.verify(c,row);mappings(c,run,plan,false);
        if(Long.parseLong(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_id_map WHERE run_id=? AND target_table IN ('sn_factory','sn_rule','sn_rule_version','sn_user_factory','sys_user')",run))!=plan.rows().size()+plan.users().size())throw fail("MASTER_MAP_CONFLICT");
    }
    /** 后续库存阶段在同一事务与迁移锁内使用；重新验证源暂存和全部停用身份，不能仅凭主数据摘要放行。 */
    void verifyImported(Connection c,LegacyMasterCommandDTO cmd,LegacySnapshotPackage snapshot,LegacyMasterPlan plan)throws SQLException,IOException{
        identities.lock(c);var manifest=snapshot.manifest();var run=stage.run(c,cmd.runId());
        if(run==null||!"STAGED".equals(run.state())||!run.fingerprint().equals(manifest.sourceFingerprint())||!run.manifest().equals(snapshot.manifestDigest())||!"legacy-v1-staging-v1".equals(run.mapping()))throw fail("STAGED_RUN_REQUIRED");
        if(!"1".equals(stage.scalar(c,"SELECT COUNT(*) FROM sn_legacy_master_run WHERE run_id=? AND mapping_version=? AND source_fingerprint=? AND plan_digest=?",cmd.runId(),LegacyMasterPlan.VERSION,manifest.sourceFingerprint(),plan.digest(manifest.sourceFingerprint()))))throw fail("MASTER_PLAN_CONFLICT");
        verifyStage(c,cmd.runId(),snapshot);verify(c,cmd.runId(),plan);
    }
    private static IllegalStateException fail(String code){return new IllegalStateException("LEGACY_"+code);}
}
