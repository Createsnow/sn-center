package com.adtek.server;

import com.adtek.integration.legacy.LegacySnapshotFactory;
import com.adtek.sn.config.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.service.*;
import com.adtek.sn.service.support.*;
import com.adtek.system.config.LegacyIdentityFactory;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 旧IMPORT库存真实转换与双护栏校验；明确没有生成来源内码、历史任务或启用账号。 */
@Testcontainers
class LegacyInventoryIT {
    static final String PASSWORD=UUID.randomUUID().toString();
    @Container static final MySQLContainer SOURCE=new MySQLContainer("mysql:8.4.11").withDatabaseName("legacy_inventory_source").withUsername("source_it").withPassword(PASSWORD);
    @Container static final MySQLContainer TARGET=new MySQLContainer("mysql:8.4.11").withDatabaseName("legacy_inventory_target").withUsername("target_it").withPassword(PASSWORD);
    static DriverManagerDataSource source,target;static JdbcTemplate old,jdbc;
    @TempDir Path root;
    String snapshot,run;LegacyInventoryService service;LegacyMasterService master;
    @BeforeAll static void schema()throws Exception{
        source=new DriverManagerDataSource(SOURCE.getJdbcUrl(),SOURCE.getUsername(),PASSWORD);target=new DriverManagerDataSource(TARGET.getJdbcUrl(),TARGET.getUsername(),PASSWORD);old=new JdbcTemplate(source);jdbc=new JdbcTemplate(target);
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("legacy/repository-v1.sql"));}Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
    }
    private void resetTarget(){
        for(String table:List.of("sn_legacy_inventory_run","sn_item","sn_key","sn_pi_counter","sn_legacy_master_run","sn_legacy_id_map","sn_legacy_row","sn_legacy_migration_table","sn_legacy_migration_run","sn_user_factory","sn_rule_version","sn_rule","sn_factory","sys_user_role","sys_user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE sys_security_control SET initialized=FALSE WHERE id=1");
    }
    @BeforeEach void prepare()throws Exception{
        resetTarget();
        var descriptor=new ObjectMapper().readTree(new ClassPathResource("legacy/repository-v1.json").getInputStream());for(var table:descriptor.path("tables"))old.update("DELETE FROM "+table.path("name").asString());
        old.update("INSERT INTO sn_factory(factory_code,factory_name,created_at,updated_at) VALUES('F','合成工厂','2026-09-01','2026-09-01')");
        old.update("INSERT INTO sn_user(id,emp_no,name,role,status,password_hash,must_change_pwd,lang,created_at,updated_at) VALUES(11,'legacy_admin','合成管理员','admin','ACTIVE',?,1,'vi','2026-09-01','2026-09-01')",LegacyMasterIT.HASH);
        old.update("INSERT INTO sn_rule(id,rule_code,rule_name,bind_scope,bind_value,current_version,created_at,updated_at) VALUES(101,'LEGACY','原规则','GENERAL','',1,'2026-09-01','2026-09-01')");
        old.update("INSERT INTO sn_rule_version(id,rule_id,version,prefix,suffix,base,seq_len,charset,used,created_at,updated_at) VALUES(201,101,1,'A','',36,12,'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',1,'2026-09-01','2026-09-01')");
        String precise=SequenceCodec.format(9007199254740993L,SequenceSpec.standard("A","",36,12));
        old.update("INSERT INTO sn_item(id,gen_month,sn,pi_no,customer_code,material_code,factory_code,rule_version_id,seq_pi_no,seq_dec,status,source,created_at,printed_at,updated_at) VALUES(9007199254740993,202609,?,'CURRENT','C','','F',201,'ORIGINAL',9007199254740993,'PRINTED','IMPORT','2026-09-01 00:01:02','2026-09-01 00:01:02','2026-09-01 00:01:02')",precise);
        old.update("INSERT INTO sn_item(id,gen_month,sn,pi_no,seq_pi_no,status,source,created_at,printed_at,updated_at) VALUES(2,202608,'Opaque-未解析','OPAQUE','OPAQUE','PRINTED','IMPORT','2026-08-02','2026-08-02','2026-08-02')");
        old.update("INSERT INTO sn_key(pi_no,sn,seq_pi_no,seq_dec) VALUES('CURRENT',?,'ORIGINAL',9007199254740993),('OPAQUE','Opaque-未解析',NULL,NULL)",precise);
        old.update("INSERT INTO sn_pi_counter(pi_no,last_seq_dec,imported_qty,start_locked,locked_by,locked_at,updated_at) VALUES('ORIGINAL',9007199254740993,1,1,'legacy_admin','2026-09-01','2026-09-01'),('OPAQUE',0,1,1,'deleted-person','2026-09-01','2026-09-01')");
        capture();master=LegacyMasterFactory.create(target,TARGET.getDatabaseName(),root,LegacyIdentityFactory.create());service=LegacyInventoryFactory.create(target,TARGET.getDatabaseName(),root,LegacyIdentityFactory.create());master.promote(command(true));
    }
    private void capture(){snapshot=UUID.randomUUID().toString();run=UUID.randomUUID().toString();LegacySnapshotFactory.create(source).capture(new LegacySnapshotCommandDTO(snapshot,SOURCE.getDatabaseName(),"sn-python-repository-v1","Asia/Shanghai",1000,1048576,root.resolve(snapshot)));LegacyStageFactory.create(target,TARGET.getDatabaseName(),root).stage(new LegacyStageCommandDTO(run,snapshot,"legacy-v1-staging-v1",true,2));}
    private LegacyMasterCommandDTO command(boolean apply){return new LegacyMasterCommandDTO(run,snapshot,apply);}
    @Test void preservesOriginalMonthSeparatePiAndPreciseNullableSequenceWithoutFakeTask(){
        assertThat(service.promote(command(false)).state()).isEqualTo("DRY_RUN");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isZero();
        var result=service.promote(command(true));assertThat(service.promote(command(true))).isEqualTo(result);assertThat(master.promote(command(true)).state()).isEqualTo("MASTER_STAGED");assertThat(result.readyForPromotion()).isFalse();
        assertThat(jdbc.queryForObject("SELECT CAST(gen_month AS CHAR) FROM sn_item WHERE pi='CURRENT'",String.class)).isEqualTo("2026-09-01");assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM sn_item WHERE pi='CURRENT'",String.class)).isEqualTo("2026-08-31 16:01:02");
        assertThat(jdbc.queryForObject("SELECT CAST(seq_dec AS CHAR) FROM sn_item WHERE pi='CURRENT'",String.class)).isEqualTo("9007199254740993");assertThat(jdbc.queryForObject("SELECT seq_pi FROM sn_item WHERE pi='CURRENT'",String.class)).isEqualTo("ORIGINAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE pi='OPAQUE' AND seq_pi IS NULL AND seq_dec IS NULL AND factory_id IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE task_id IS NULL AND legacy_run_id=?",Integer.class,run)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_task",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key k JOIN sn_item i ON k.id=i.id AND k.gen_month=i.gen_month AND k.sn=i.sn AND k.pi=i.pi",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locked_by FROM sn_pi_counter WHERE pi='OPAQUE'",Long.class)).isNull();assertThat(jdbc.queryForObject("SELECT last_seq FROM sn_pi_counter WHERE pi='ORIGINAL'",Long.class)).isEqualTo(9007199254740993L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map WHERE target_table IN ('sn_item','sn_key','sn_pi_counter')",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE enabled=TRUE",Integer.class)).isZero();assertThat(old.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isEqualTo(2);
    }
    @Test void counterFailureRollsBackBothSnTablesAllMapsAndProof(){
        jdbc.execute("ALTER TABLE sn_pi_counter ADD CONSTRAINT it_reject_inventory CHECK(pi<>'ORIGINAL')");
        try{assertThatThrownBy(()->service.promote(command(true))).hasMessageStartingWith("LEGACY_INVENTORY_SQL_");}finally{jdbc.execute("ALTER TABLE sn_pi_counter DROP CHECK it_reject_inventory");}
        for(String table:List.of("sn_item","sn_key","sn_pi_counter","sn_legacy_inventory_run"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map WHERE target_table IN ('sn_item','sn_key','sn_pi_counter')",Integer.class)).isZero();assertThat(service.promote(command(true)).items()).isEqualTo("2");
    }
    @Test void changedWatermarkAndKeysAreDetectedOnReplay(){
        service.promote(command(true));jdbc.update("UPDATE sn_pi_counter SET last_seq=last_seq+1 WHERE pi='ORIGINAL'");assertThatThrownBy(()->service.promote(command(true))).hasMessage("LEGACY_MASTER_TARGET_CHANGED");jdbc.update("UPDATE sn_pi_counter SET last_seq=last_seq-1 WHERE pi='ORIGINAL'");
        jdbc.update("DELETE FROM sn_key WHERE pi='OPAQUE'");assertThatThrownBy(()->service.promote(command(true))).hasMessage("LEGACY_INVENTORY_TARGET_CHANGED");
    }
    @Test void sourcePointersAndInvalidRulesNeedEvidenceNotNullingOrGuessing()throws Exception{
        // 直接构造另一个完整只读来源计划，目标既有主数据仍不改写。
        old.update("UPDATE sn_item SET batch_no='OLD-BATCH' WHERE pi_no='CURRENT'");String changed=UUID.randomUUID().toString();LegacySnapshotFactory.create(source).capture(new LegacySnapshotCommandDTO(changed,SOURCE.getDatabaseName(),"sn-python-repository-v1","Asia/Shanghai",1000,1048576,root.resolve(changed)));
        var snapshotPackage=new LegacySnapshotPackage(root,changed);var main=new LegacyMasterPlan(snapshotPackage);assertThatThrownBy(()->new LegacyInventoryPlan(snapshotPackage,main,run)).hasMessage("LEGACY_INVENTORY_HISTORY_EVIDENCE_REQUIRED");
        old.update("UPDATE sn_item SET batch_no=NULL,sn='INCORRECT' WHERE pi_no='CURRENT'");old.update("UPDATE sn_key SET sn='INCORRECT' WHERE pi_no='CURRENT'");changed=UUID.randomUUID().toString();LegacySnapshotFactory.create(source).capture(new LegacySnapshotCommandDTO(changed,SOURCE.getDatabaseName(),"sn-python-repository-v1","Asia/Shanghai",1000,1048576,root.resolve(changed)));
        var invalid=new LegacySnapshotPackage(root,changed);assertThatThrownBy(()->new LegacyInventoryPlan(invalid,new LegacyMasterPlan(invalid),run)).hasMessage("LEGACY_INVENTORY_RULE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isZero();
    }
    @Test void unverifiedMasterAndActivatedAccountRefuseInventory(){
        jdbc.update("UPDATE sys_user SET enabled=TRUE WHERE id=11");assertThatThrownBy(()->service.promote(command(true))).hasMessage("LEGACY_IDENTITY_TARGET_CHANGED");jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=11");
        jdbc.update("UPDATE sn_legacy_master_run SET mapping_version='tampered'");assertThatThrownBy(()->service.promote(command(true))).hasMessage("LEGACY_MASTER_PLAN_CONFLICT");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isZero();
    }
    @Test void databaseOriginConstraintDoesNotAllowLegacyGeneratedSnOrDetachedInventory(){
        service.promote(command(true));assertThatThrownBy(()->jdbc.update("UPDATE sn_item SET source='GEN' WHERE pi='CURRENT'")).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,e->assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->assertThat(sql.getErrorCode()).isEqualTo(3819)));
        assertThatThrownBy(()->jdbc.update("UPDATE sn_item SET legacy_run_id=NULL WHERE pi='CURRENT'")).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,e->assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->assertThat(sql.getErrorCode()).isEqualTo(3819)));
    }
    /** 达到当前25000枚明确上限，跨500行JDBC批次验证回滚、重试、全字段重放和映射篡改检测。 */
    @Test void boundedMaximumInventoryBatchesRollbackAndReplayWithoutDuplicateMappings()throws Exception{
        var extra=new ArrayList<Integer>();for(int i=0;i<24998;i++)extra.add(i);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(status->{
        old.batchUpdate("INSERT INTO sn_item(id,gen_month,sn,pi_no,seq_pi_no,status,source,created_at,printed_at,updated_at) VALUES(?,202609,?,'BULK','BULK','PRINTED','IMPORT','2026-09-01','2026-09-01','2026-09-01')",extra,500,(p,i)->{p.setLong(1,100+i);p.setString(2,"Opaque-bulk-"+i);});
        old.batchUpdate("INSERT INTO sn_key(pi_no,sn,seq_pi_no,seq_dec) VALUES('BULK',?,NULL,NULL)",extra,500,(p,i)->p.setString(1,"Opaque-bulk-"+i));
        old.update("INSERT INTO sn_pi_counter(pi_no,last_seq_dec,imported_qty,start_locked,locked_at,updated_at) VALUES('BULK',0,24998,1,'2026-09-01','2026-09-01')");
        });
        resetTarget();snapshot=UUID.randomUUID().toString();run=UUID.randomUUID().toString();
        LegacySnapshotFactory.create(source).capture(new LegacySnapshotCommandDTO(snapshot,SOURCE.getDatabaseName(),"sn-python-repository-v1","Asia/Shanghai",60000,67108864,root.resolve(snapshot)));
        LegacyStageFactory.create(target,TARGET.getDatabaseName(),root).stage(new LegacyStageCommandDTO(run,snapshot,"legacy-v1-staging-v1",true,500));master.promote(command(true));
        // 证明写在业务表/所有映射之后，强制失败必须回滚此前每个JDBC批次。
        jdbc.execute("ALTER TABLE sn_legacy_inventory_run ADD CONSTRAINT it_inventory_proof_reject CHECK(mapping_version='reject-all')");
        try{assertThatThrownBy(()->service.promote(command(true))).hasMessageStartingWith("LEGACY_INVENTORY_SQL_");}finally{jdbc.execute("ALTER TABLE sn_legacy_inventory_run DROP CHECK it_inventory_proof_reject");}
        for(String table:List.of("sn_item","sn_key","sn_pi_counter","sn_legacy_inventory_run"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map WHERE target_table IN ('sn_item','sn_key','sn_pi_counter')",Long.class)).isZero();
        long began=System.nanoTime();var result=service.promote(command(true));long applyMs=(System.nanoTime()-began)/1000000;assertThat(result.items()).isEqualTo("25000");
        began=System.nanoTime();assertThat(service.promote(command(true))).isEqualTo(result);long replayMs=(System.nanoTime()-began)/1000000;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key k JOIN sn_item i ON i.gen_month=k.gen_month AND i.id=k.id WHERE i.sn=k.sn AND i.pi=k.pi AND (i.seq_dec <=> k.seq_dec)",Long.class)).isEqualTo(25000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map WHERE target_table IN ('sn_item','sn_key','sn_pi_counter')",Long.class)).isEqualTo(50003);
        jdbc.update("UPDATE sn_legacy_id_map SET target_key=JSON_OBJECT('id','tampered') WHERE target_table='sn_key' LIMIT 1");assertThatThrownBy(()->service.promote(command(true))).hasMessage("LEGACY_MASTER_TARGET_CHANGED");
        old.update("INSERT INTO sn_item(id,gen_month,sn,pi_no,seq_pi_no,status,source,created_at,printed_at,updated_at) VALUES(99999,202609,'Opaque-over-limit','BULK','BULK','PRINTED','IMPORT','2026-09-01','2026-09-01','2026-09-01')");
        old.update("INSERT INTO sn_key(pi_no,sn,seq_pi_no,seq_dec) VALUES('BULK','Opaque-over-limit',NULL,NULL)");old.update("UPDATE sn_pi_counter SET imported_qty=24999 WHERE pi_no='BULK'");
        String over=UUID.randomUUID().toString();LegacySnapshotFactory.create(source).capture(new LegacySnapshotCommandDTO(over,SOURCE.getDatabaseName(),"sn-python-repository-v1","Asia/Shanghai",60000,67108864,root.resolve(over)));
        var tooLarge=new LegacySnapshotPackage(root,over);assertThatThrownBy(()->new LegacyInventoryPlan(tooLarge,new LegacyMasterPlan(tooLarge),run)).hasMessage("LEGACY_INVENTORY_RESOURCE_LIMIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Long.class)).isEqualTo(25000);
        Files.writeString(Path.of("target/legacy-inventory-capacity.json"),new ObjectMapper().writeValueAsString(Map.of("items","25000","mappings","50003","applyMs",applyMs,"verifiedReplayMs",replayMs,"rollbackAtProof",true,"scope","synthetic bounded inventory; not full historical migration")));
    }
}
