package com.adtek.server;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 启动真实打包应用A，数据库测试门闩固定在生成事务提交前，强杀其OS进程，再启动真实应用B自动接管。
 * 测试门闩仅装在一次性数据库，不修改生产代码；不是异常注入模拟进程死亡，也不是容量测量。
 */
@Testcontainers
class ProcessTaskRecoveryIT {
    static final String SECRET=HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),PASSWORD="Recovery9!"+SECRET.substring(0,18);
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("adtek_process_recovery_it").withUsername("recovery_it").withPassword(SECRET).withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_0900_as_cs","--default-time-zone=+00:00");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.4.7-alpine").withExposedPorts(6379).withCommand("redis-server","--requirepass",SECRET);
    @TempDir Path temporary;
    final ObjectMapper json=new ObjectMapper();final List<Process> children=new ArrayList<>();final List<Thread> logReaders=new ArrayList<>();
    DriverManagerDataSource database;JdbcTemplate jdbc;int port;
    @AfterEach void close()throws Exception{
        for(var child:children){if(child.isAlive())child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).as("owned child must exit").isTrue();}child.getOutputStream().close();}
        // 等待父进程日志读取器关闭文件，避免Windows进程退出与重定向文件句柄释放之间的清理竞态。
        for(var reader:logReaders){reader.join(5000);assertThat(reader.isAlive()).as("child log reader must finish").isFalse();}
        for(var child:children){child.getInputStream().close();child.getErrorStream().close();}
    }
    private Process start(String label,String erp)throws Exception{
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");Path jar=Path.of("target/adtek-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        var command=List.of(java.toString(),"-Xmx384m","-jar",jar.toString(),"--spring.profiles.active=local","--spring.flyway.enabled=false","--adtek.tasks.lease-seconds=5","--adtek.tasks.budget-seconds=180","--adtek.tasks.retry-seconds=1");
        var builder=new ProcessBuilder(command).redirectErrorStream(true);var env=builder.environment();
        env.putAll(Map.of("ADTEK_DB_URL",MYSQL.getJdbcUrl(),"ADTEK_DB_USER",MYSQL.getUsername(),"ADTEK_DB_PASSWORD",SECRET,"ADTEK_MIGRATION_USER",MYSQL.getUsername(),"ADTEK_MIGRATION_PASSWORD",SECRET,"ADTEK_REDIS_HOST",REDIS.getHost(),"ADTEK_REDIS_PORT",REDIS.getMappedPort(6379).toString(),"ADTEK_REDIS_PASSWORD",SECRET,"ADTEK_JWT_SECRET",SECRET));
        env.putAll(Map.of("ADTEK_SERVER_ADDRESS","127.0.0.1","ADTEK_SERVER_PORT",Integer.toString(port),"ADTEK_FILE_ROOT",temporary.resolve("files").toString(),"ADTEK_K3_ENABLED","true","ADTEK_K3_URL",erp,"ADTEK_K3_ACCOUNT","ISOLATED_PROCESS_FIXTURE","ADTEK_K3_APP_ID","fixture","ADTEK_K3_APP_SECRET",SECRET,"ADTEK_K3_USERNAME","fixture"));
        var process=builder.start();children.add(process);
        logReaders.add(Thread.ofVirtual().name("recovery-log-"+label).start(()->{
            try(var input=process.getInputStream();var output=Files.newOutputStream(temporary.resolve(label+".log"))){input.transferTo(output);}
            catch(java.io.IOException ignored){/* 进程强杀可能关闭管道；业务状态由HTTP/数据库断言验证，日志不作为通过依据。 */}
        }));
        try(var client=HttpClient.newHttpClient()){
            waitFor(60,()->{if(!process.isAlive())throw new AssertionError("Packaged child exited; inspect isolated test log "+label);try{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception e){return false;}},"child readiness");
        }
        return process;
    }
    @Test void killedGenerationRollsBackThenRestartsWithSameIntentExactlyOnce()throws Exception{
        database=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),SECRET);jdbc=new JdbcTemplate(database);Flyway.configure().dataSource(database).locations("classpath:db/migration").load().migrate();
        var encoder=new Pbkdf2PasswordEncoder("",16,600000,Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        jdbc.update("INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,must_change_password) VALUES(11,'recovery_hq',?,'进程恢复夹具',1,FALSE)","{pbkdf2-sha256-600k}"+encoder.encode(PASSWORD));jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(11,2)");jdbc.update("UPDATE sys_security_control SET initialized=TRUE");
        jdbc.execute("CREATE TABLE it_process_gate(id INT PRIMARY KEY) ENGINE=InnoDB");jdbc.update("INSERT INTO it_process_gate VALUES(1)");
        // 仅测试容器root安装门闩；业务子进程仍使用普通schema账号，不扩大其权限或关闭binlog安全限制。
        new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",SECRET)).execute("CREATE TRIGGER it_generation_commit_gate BEFORE INSERT ON sn_audit FOR EACH ROW BEGIN DECLARE gate_id INT; IF NEW.action='sn:generation:execute' AND NEW.result='SUCCESS' THEN SELECT id INTO gate_id FROM it_process_gate WHERE id=1 FOR UPDATE; END IF; END");
        try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        try(var erp=new K3ProtocolFixture();var browser=new Browser()){
            erp.orders=List.of(K3ProtocolFixture.row("9007199254740993","11","RECOVERY-MO","C","RECOVERY-PI","101","ERP-A","甲工厂","M","13"));var first=start("process-a",erp.url());browser.login();
            browser.data(browser.post("/sn/orders/sync",Map.of()));browser.data(browser.post("/sn/rules",Map.of("code","RECOVERY_RULE","name","恢复夹具规则","bindScope","GENERAL","bindValue","","prefix","R","suffix","","base",10,"length",6,"alphabet","")));
            String bill=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='RECOVERY-MO'",String.class);
            String preview=browser.data(browser.post("/sn/generation/previews",Map.of("billId",bill,"quantity","13"))).path("id").asString();String operation=UUID.randomUUID().toString();var command=Map.of("operationId",operation,"previewId",preview);String task;
            long killedAt;
            try(Connection gate=database.getConnection()){
                gate.setAutoCommit(false);try(var statement=gate.createStatement();var row=statement.executeQuery("SELECT id FROM it_process_gate WHERE id=1 FOR UPDATE")){assertThat(row.next()).isTrue();}
                task=browser.data(browser.post("/sn/generation",command)).path("taskId").asString();String taskId=task;
                waitFor(20,()->jdbc.queryForObject("SELECT COUNT(*) FROM sys_task_progress WHERE task_id=? AND execution_epoch=1 AND processed=13",Integer.class,taskId)==1,"precommit progress");
                assertThat(jdbc.queryForObject("SELECT state FROM sys_task WHERE id=?",String.class,task)).isEqualTo("RUNNING");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isZero();
                killedAt=System.nanoTime();first.destroyForcibly();assertThat(first.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(first.exitValue()).isNotZero();gate.rollback();
            }
            // 已提交的领取代次/进度保留；SN/额度/水位仍为零，不能把进度当成生效数量。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT generated_qty FROM sn_source_entry_quota",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT last_seq FROM sn_pi_counter WHERE pi='RECOVERY-PI'",Long.class)).isZero();
            start("process-b",erp.url());String taskId=task;waitFor(60,()->"SUCCESS".equals(jdbc.queryForObject("SELECT state FROM sys_task WHERE id=?",String.class,taskId)),"reclaimed generation");
            var recovered=browser.data(browser.get("/sn/operations/GENERATE/"+operation));assertThat(recovered.path("taskId").asString()).isEqualTo(task);assertThat(recovered.path("state").asString()).isEqualTo("SUCCESS");assertThat(recovered.path("operationState").asString()).isEqualTo("SUCCEEDED");assertThat(recovered.path("attempt").asString()).isEqualTo("2");assertThat(recovered.path("executionEpoch").asString()).isEqualTo("2");
            assertThat(browser.data(browser.post("/sn/generation",command)).path("taskId").asString()).isEqualTo(task);
            for(String table:List.of("sn_item","sn_key"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(13);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT sn) FROM sn_item",Integer.class)).isEqualTo(13);assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT seq_dec) FROM sn_item",Integer.class)).isEqualTo(13);assertThat(jdbc.queryForObject("SELECT generated_qty FROM sn_source_entry_quota",Long.class)).isEqualTo(13L);assertThat(jdbc.queryForObject("SELECT last_seq FROM sn_pi_counter WHERE pi='RECOVERY-PI'",Long.class)).isEqualTo(13L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit WHERE action='sn:generation:execute' AND result='SUCCESS'",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation WHERE operation_id=?",Integer.class,operation)).isEqualTo(1);
            var report=Map.of("test","actual packaged JVM forced termination and restart","applications",2,"forcedTermination",true,"snBeforeRestart",0,"snAfterRecovery",13,"attempt",2,"epoch",2,"successAudit",1,"recoverySeconds",(System.nanoTime()-killedAt)/1_000_000_000.0,"capacityEvidence",false);
            Files.writeString(Path.of("target/process-recovery-measurement.json"),json.writeValueAsString(report));
        }
    }
    private static void waitFor(int seconds,BooleanSupplier ready,String condition)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Timeout waiting for "+condition);Thread.sleep(100);}}
    final class Browser implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;
        HttpResponse<String> get(String path)throws Exception{return call("GET",path,null,null);}
        HttpResponse<String> post(String path,Object body)throws Exception{String csrf=data(get("/system/auth/csrf")).path("token").asString();return call("POST",path,body,csrf);}
        void login()throws Exception{token=data(post("/system/auth/login",Map.of("username","recovery_hq","password",PASSWORD))).path("accessToken").asString();}
        JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as("HTTP result code; body withheld").isIn(200,202);return json.readTree(response.body()).path("data");}
        HttpResponse<String> call(String method,String path,Object body,String csrf)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");if(token!=null)request.header("Authorization","Bearer "+token);if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close(){client.close();}
    }
}
