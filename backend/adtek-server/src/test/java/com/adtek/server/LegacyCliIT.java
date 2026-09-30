package com.adtek.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** 执行打包JAR和Windows正式脚本，证明入口参数/退出码/环境注入有效；仅使用两个一次性数据库。 */
@Testcontainers
class LegacyCliIT {
    static final String PASSWORD=UUID.randomUUID().toString().replace("-","");
    @Container static final MySQLContainer SOURCE=new MySQLContainer("mysql:8.4.11").withDatabaseName("legacy_cli_source").withUsername("fixture_writer").withPassword(PASSWORD);
    @Container static final MySQLContainer TARGET=new MySQLContainer("mysql:8.4.11").withDatabaseName("legacy_cli_target").withUsername("fixture_target").withPassword(PASSWORD);
    static JdbcTemplate target;
    @TempDir Path temporary;
    final Path project=Path.of("../..").toAbsolutePath().normalize();
    final Path jar=Path.of("target/adtek-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    @BeforeAll static void setup()throws Exception{
        var source=new DriverManagerDataSource(SOURCE.getJdbcUrl(),SOURCE.getUsername(),PASSWORD);
        try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("legacy/repository-v1.sql"));}
        var old=new JdbcTemplate(source);old.update("INSERT INTO sn_factory(factory_code,factory_name,created_at,updated_at) VALUES('CLI-F','命令行合成工厂','2026-01-01','2026-01-01')");
        old.update("INSERT INTO sn_user(id,emp_no,name,role,status,password_hash,must_change_pwd,lang,created_at,updated_at) VALUES(9007199254740993,'cli_admin','命令行合成用户','admin','ACTIVE',?,1,'vi','2026-01-01','2026-01-01')",LegacyMasterIT.HASH);
        old.update("INSERT INTO sn_rule(id,rule_code,rule_name,bind_scope,bind_value,current_version,created_at,updated_at) VALUES(101,'CLI','命令行规则','GENERAL','',1,'2026-01-01','2026-01-01')");
        old.update("INSERT INTO sn_rule_version(id,rule_id,version,prefix,suffix,base,seq_len,charset,used,created_at,updated_at) VALUES(201,101,1,'C','',36,12,'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',1,'2026-01-01','2026-01-01')");
        String sn=com.adtek.sn.service.support.SequenceCodec.format(9007199254740993L,com.adtek.sn.service.support.SequenceSpec.standard("C","",36,12));
        old.update("INSERT INTO sn_item(id,gen_month,sn,pi_no,seq_pi_no,seq_dec,rule_version_id,factory_code,status,source,created_at,printed_at,updated_at) VALUES(9007199254740993,202609,?,'CLI-PI','CLI-PI',9007199254740993,201,'CLI-F','PRINTED','IMPORT','2026-09-01','2026-09-01','2026-09-01')",sn);
        old.update("INSERT INTO sn_key(pi_no,sn,seq_pi_no,seq_dec) VALUES('CLI-PI',?,'CLI-PI',9007199254740993)",sn);
        old.update("INSERT INTO sn_pi_counter(pi_no,last_seq_dec,imported_qty,start_locked,locked_by,locked_at,updated_at) VALUES('CLI-PI',9007199254740993,1,1,'cli_admin','2026-09-01','2026-09-01')");
        var grant=SOURCE.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot -e \"CREATE USER 'legacy_reader'@'%' IDENTIFIED BY '"+PASSWORD+"'; GRANT SELECT ON legacy_cli_source.* TO 'legacy_reader'@'%';\"");
        assertThat(grant.getExitCode()).isZero();
        var destination=new DriverManagerDataSource(TARGET.getJdbcUrl(),TARGET.getUsername(),PASSWORD);Flyway.configure().dataSource(destination).locations("classpath:db/migration").load().migrate();target=new JdbcTemplate(destination);
    }
    private Map<String,String> env(Path root){return Map.of("ADTEK_LEGACY_SOURCE_URL",SOURCE.getJdbcUrl(),"ADTEK_LEGACY_SOURCE_USER","legacy_reader","ADTEK_LEGACY_SOURCE_PASSWORD",PASSWORD,
        "ADTEK_LEGACY_TARGET_URL",TARGET.getJdbcUrl(),"ADTEK_LEGACY_TARGET_USER",TARGET.getUsername(),"ADTEK_LEGACY_TARGET_PASSWORD",PASSWORD,"ADTEK_LEGACY_TARGET_SCHEMA",TARGET.getDatabaseName(),"ADTEK_LEGACY_SNAPSHOT_ROOT",root.toString());}
    private String execute(List<String> command,Map<String,String> environment,int exit)throws Exception{
        Path output=temporary.resolve(UUID.randomUUID()+".log");var builder=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());builder.environment().putAll(environment);
        var process=builder.start();if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Legacy CLI exceeded test budget");}
        String response=Files.readString(output);assertThat(response).doesNotContain(PASSWORD,LegacyMasterIT.HASH,SOURCE.getJdbcUrl(),TARGET.getJdbcUrl());assertThat(process.exitValue()).as(response).isEqualTo(exit);return response;
    }
    private List<String> java(String... args){var cmd=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar",jar.toString()));cmd.addAll(List.of(args));return cmd;}
    @Test void packagedSnapshotStageAndResumeThroughOfficialEntryPoints()throws Exception{
        boolean windows=System.getProperty("os.name").startsWith("Windows");Path root=windows?project.resolve("deploy/data/legacy-snapshots"):temporary.resolve("snapshots");Files.createDirectories(root);
        String snapshot=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();var environment=env(root);
        var capture=windows?List.of("pwsh","-NoProfile","-File",project.resolve("tools/legacy-snapshot.ps1").toString(),"-RunId",snapshot,"-SourceSchema",SOURCE.getDatabaseName(),"-SourceZone","Asia/Shanghai"):
            java("--legacy-snapshot","--run-id",snapshot,"--source-schema",SOURCE.getDatabaseName(),"--source-zone","Asia/Shanghai","--schema-version","sn-python-repository-v1");
        assertThat(execute(capture,environment,0)).contains("SNAPSHOT_CAPTURED");assertThat(root.resolve(snapshot+"/manifest.json")).exists();
        var dry=windows?List.of("pwsh","-NoProfile","-File",project.resolve("tools/legacy-stage.ps1").toString(),"-SnapshotId",snapshot,"-RunId",run):java("--legacy-stage","--snapshot-id",snapshot,"--run-id",run);
        assertThat(execute(dry,environment,0)).contains("DRY_RUN");assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_legacy_row",Integer.class)).isZero();
        var apply=new ArrayList<>(dry);apply.add(windows?"-Apply":"--apply");assertThat(execute(apply,environment,0)).contains("STAGED");assertThat(execute(apply,environment,0)).contains("STAGED");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_legacy_row WHERE run_id=?",Integer.class,run)).isEqualTo(7);
        assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_factory",Integer.class)).isZero();assertThat(target.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class)).isZero();
        var master=windows?List.of("pwsh","-NoProfile","-File",project.resolve("tools/legacy-master.ps1").toString(),"-SnapshotId",snapshot,"-RunId",run):java("--legacy-master","--snapshot-id",snapshot,"--run-id",run);
        assertThat(execute(master,environment,0)).contains("DRY_RUN");var masterApply=new ArrayList<>(master);masterApply.add(windows?"-Apply":"--apply");assertThat(execute(masterApply,environment,0)).contains("MASTER_STAGED");assertThat(execute(masterApply,environment,0)).contains("MASTER_STAGED");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_factory",Integer.class)).isEqualTo(1);assertThat(target.queryForObject("SELECT COUNT(*) FROM sys_user WHERE enabled=TRUE",Integer.class)).isZero();
        var inventory=windows?List.of("pwsh","-NoProfile","-File",project.resolve("tools/legacy-inventory.ps1").toString(),"-SnapshotId",snapshot,"-RunId",run):java("--legacy-inventory","--snapshot-id",snapshot,"--run-id",run);
        assertThat(execute(inventory,environment,0)).contains("DRY_RUN");var inventoryApply=new ArrayList<>(inventory);inventoryApply.add(windows?"-Apply":"--apply");assertThat(execute(inventoryApply,environment,0)).contains("INVENTORY_STAGED");assertThat(execute(inventoryApply,environment,0)).contains("INVENTORY_STAGED");
        assertThat(target.queryForObject("SELECT CAST(seq_dec AS CHAR) FROM sn_item",String.class)).isEqualTo("9007199254740993");assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_key",Integer.class)).isEqualTo(1);
        assertThat(target.queryForObject("SELECT CAST(locked_by AS CHAR) FROM sn_pi_counter",String.class)).isEqualTo("9007199254740993");assertThat(target.queryForObject("SELECT COUNT(*) FROM sys_user WHERE enabled=FALSE AND must_change_password=TRUE AND language='vi'",Integer.class)).isEqualTo(1);
        assertThat(target.queryForObject("SELECT COUNT(*) FROM sys_task",Integer.class)).isZero();assertThat(target.queryForObject("SELECT COUNT(*) FROM sn_legacy_id_map WHERE target_table IN ('sn_item','sn_key','sn_pi_counter')",Integer.class)).isEqualTo(3);
    }
    @Test void missingOrDuplicateFlagsExitBeforeSourceAccessWithoutLeakingEnvironment()throws Exception{
        var environment=env(temporary);
        assertThat(execute(java("--legacy-snapshot","--run-id",UUID.randomUUID().toString()),environment,2)).contains("LEGACY_SNAPSHOT_FAILED");
        assertThat(execute(java("--legacy-stage","--snapshot-id",UUID.randomUUID().toString(),"--apply"),environment,2)).contains("LEGACY_STAGE_FAILED");
        assertThat(execute(java("--legacy-stage","--snapshot-id",UUID.randomUUID().toString(),"--snapshot-id",UUID.randomUUID().toString()),environment,2)).contains("LEGACY_STAGE_FAILED");
    }
}
