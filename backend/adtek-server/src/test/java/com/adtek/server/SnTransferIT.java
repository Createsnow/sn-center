package com.adtek.server;

import java.net.*;
import java.net.http.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** S6真实HTTP/MySQL/Redis转厂状态、固定成员、双PI/护栏及权限回归，手动驱动工作器注入故障；不连接真实ERP。 */
@Testcontainers @ActiveProfiles("integration-test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnTransferIT {
 static final String SECRET=HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),PASSWORD="SnStage9!"+SECRET.substring(0,24);
 static final K3ProtocolFixture ERP=new K3ProtocolFixture();
 @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("adtek_sn_stage6_transfer_it").withUsername("stage3").withPassword(SECRET).withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_0900_as_cs","--default-time-zone=+00:00");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.4.7-alpine").withExposedPorts(6379).withCommand("redis-server","--requirepass",SECRET);
 @DynamicPropertySource static void settings(DynamicPropertyRegistry p){
  p.add("ADTEK_DB_URL",MYSQL::getJdbcUrl);p.add("ADTEK_DB_USER",MYSQL::getUsername);p.add("ADTEK_DB_PASSWORD",MYSQL::getPassword);p.add("ADTEK_MIGRATION_USER",MYSQL::getUsername);p.add("ADTEK_MIGRATION_PASSWORD",MYSQL::getPassword);p.add("ADTEK_REDIS_HOST",REDIS::getHost);p.add("ADTEK_REDIS_PORT",()->REDIS.getMappedPort(6379));p.add("ADTEK_REDIS_PASSWORD",()->SECRET);p.add("ADTEK_JWT_SECRET",()->SECRET);
  p.add("adtek.k3.enabled",()->true);p.add("adtek.k3.url",ERP::url);p.add("adtek.k3.account",()->"ISOLATED");p.add("adtek.k3.app-id",()->"fixture");p.add("adtek.k3.app-secret",()->SECRET);p.add("adtek.k3.username",()->"fixture");p.add("adtek.k3.page-size",()->2);p.add("adtek.k3.max-pages",()->10);p.add("adtek.k3.max-rows",()->18);p.add("adtek.k3.query-seconds",()->10);
 }
 @Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder passwords;@Autowired StringRedisTemplate redis;@Autowired ObjectMapper json;@LocalServerPort int port;
 @Autowired com.adtek.framework.task.service.TaskExecutionService worker;
 @Autowired com.adtek.framework.task.mapper.DurableTaskMapper tasks;
 @AfterAll static void close(){ERP.close();}
 @BeforeEach void reset(){
  ERP.reset();for(String table:List.of("sn_transfer_work","sn_transfer_member","sn_transfer","sn_acquire_result","sn_acquire_member","sn_acquire_batch","sn_acquire_work","sn_print_member","sn_print","sn_acquire_exception","sys_service_permission","sys_service_client","sn_key","sn_item","sn_work","sn_preview","sn_pi_counter","sys_task_progress","sys_file","sys_task","sys_operation","sn_source_entry_quota","sn_order_entry","sn_order_bill","sn_order_sync","sn_org_mapping","sn_factory","sn_rule_version","sn_rule","sn_user_factory","sys_user_role","sys_user","sys_oper_log"))jdbc.update("DELETE FROM "+table);
  jdbc.update("DELETE FROM sys_role_menu WHERE role_id>=1000");jdbc.update("DELETE FROM sys_role WHERE id>=1000");jdbc.update("UPDATE sn_snapshot_control SET revision=0");
  jdbc.execute("ALTER TABLE sn_factory AUTO_INCREMENT=9007199254742001");jdbc.execute("ALTER TABLE sn_order_bill AUTO_INCREMENT=9007199254743001");jdbc.execute("ALTER TABLE sn_rule AUTO_INCREMENT=9007199254744001");
  try(var c=redis.getConnectionFactory().getConnection()){c.serverCommands().flushDb();}
  user(9007199254741001L,"sn_hq",2);jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9007199254741001,1)");
 }
 void user(long id,String username,long role){jdbc.update("INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,must_change_password) VALUES(?,?,?,?,1,FALSE)",id,username,passwords.encode(PASSWORD),username);jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);}
 List<Object> row(String bill,String entry,String no,String material,Object qty){return K3ProtocolFixture.row(bill,entry,no,"C1","PI-1","101","ERP-A","甲工厂",material,qty);}
 void sample(){ERP.orders=List.of(row("9007199254740993","11","MO-1","M1","8"),row("9007199254740993","12","MO-1","M1","3"),row("9007199254740993","13","MO-1","","2"),K3ProtocolFixture.row("2","21","MO-2","C2","PI-2","102","ERP-B","乙工厂","M2","5"));}
 JsonNode data(HttpResponse<String> response)throws Exception{assertThat(response.statusCode()).as(response.body()).isIn(200,202);return json.readTree(response.body()).path("data");}
 Map<String,Object> rule(String code,String scope,String value,String prefix){return new HashMap<>(Map.of("code",code,"name",code,"bindScope",scope,"bindValue",value,"prefix",prefix,"suffix","","base",10,"length",4,"alphabet",""));}


 String uuid(){return UUID.randomUUID().toString();}
 Map<String,Object> init(String operation,String pi,String start,List<String> sns){var out=new HashMap<String,Object>();out.put("operationId",operation);out.put("pi",pi);out.put("start",start);out.put("sns",sns);return out;}
 String prepared(Browser b)throws Exception{sample();data(b.post("/orders/sync",Map.of()));data(b.post("/rules",rule("GENERAL","GENERAL","","G")));return jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='MO-1'",String.class);}
 String preview(Browser b,String bill,String qty)throws Exception{return data(b.post("/generation/previews",Map.of("billId",bill,"quantity",qty))).path("id").asString();}
 String generate(Browser b,String preview)throws Exception{return data(b.post("/generation",Map.of("operationId",uuid(),"previewId",preview))).path("taskId").asString();}
 void run(String id)throws Exception{var lease=worker.claim(uuid());assertThat(lease.id()).isEqualTo(id);worker.execute(lease);}
 int fail(String id)throws Exception{var lease=worker.claim(uuid());assertThat(lease.id()).isEqualTo(id);Exception failure=catchThrowableOfType(Exception.class,()->worker.execute(lease));assertThat(failure).isNotNull();worker.failed(lease,failure);return jdbc.queryForObject("SELECT error_code FROM sys_task WHERE id=?",Integer.class,id);}
 long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 long last(String pi){return jdbc.queryForObject("SELECT last_seq FROM sn_pi_counter WHERE pi=?",Long.class,pi);}


 @Autowired com.adtek.framework.task.TaskSettings taskSettings;
 String allocated(Browser b)throws Exception{String bill=prepared(b);run(generate(b,preview(b,bill,"13")));run(data(b.post("/allocations",Map.of("operationId",uuid(),"billId",bill,"expectedQuantity","13"))).path("taskId").asString());return jdbc.queryForObject("SELECT CAST(factory_id AS CHAR) FROM sn_order_bill WHERE id=?",String.class,bill);}
 Map<String,Object> take(String operation,String factory,String pi){return Map.of("operationId",operation,"factoryId",factory,"pi",pi);}
 String acquire(Browser b,String factory)throws Exception{String task=data(b.post("/acquisitions",take(uuid(),factory,"PI-1"))).path("taskId").asString();run(task);return task;}
 List<JsonNode> members(Browser b,String batch)throws Exception{var out=new ArrayList<JsonNode>();String cursor=null;do{var page=data(b.get("/acquire-batches/"+batch+"/items?size=5"+(cursor==null?"":"&cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8))));page.path("items").forEach(out::add);cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asString();}while(cursor!=null);return out;}
 void drain()throws Exception{for(int i=0;i<10;i++){var lease=worker.claim(uuid());if(lease==null)return;worker.execute(lease);}throw new IllegalStateException("Unexpected task backlog");}
 byte[] download(Browser b,String file)throws Exception{var response=b.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/files/"+file)).header("Authorization","Bearer "+b.token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(200);return response.body();}


 String destination(String source){return jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sn_factory WHERE id<>? ORDER BY id LIMIT 1",String.class,source);}
 Map<String,Object> transfer(String operation,String source,String to,String scope,String material,String sn){var body=new HashMap<String,Object>();body.putAll(Map.of("operationId",operation,"factoryId",source,"pi","PI-1","scope",scope,"toFactoryId",to,"toPi","PI-2","toCustomer","","toMaterial","","targetSource","MANUAL","reason","跨厂生产交接"));body.put("material",material);body.put("sn",sn);return body;}
 String submit(Browser b,String action,Map<String,Object> body)throws Exception{return data(b.post("/transfers"+(action.equals("DIRECT")?"/direct":""),body)).path("taskId").asString();}
 String decision(Browser b,String id,String action,String version)throws Exception{return data(b.post("/transfers/"+id+"/"+action,Map.of("operationId",uuid(),"version",version,"note","处理意见"))).path("taskId").asString();}
 List<JsonNode> transferItems(Browser b,String id)throws Exception{List<JsonNode> out=new ArrayList<>();String cursor=null;do{var page=data(b.get("/transfers/"+id+"/items?size=5"+(cursor==null?"":"&cursor="+cursor)));page.path("items").forEach(out::add);cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asString();}while(cursor!=null);return out;}
 @Test void applyFreezesMixedStatesAndAnotherSourceOperatorWithdrawsExactPointers()throws Exception{
  try(var hq=new Browser("sn_hq")){String factory=allocated(hq);String batch=acquire(hq,factory);String printed=uuid();jdbc.update("UPDATE sn_item SET status='PRINTED',current_print_id=?,printed_at=CURRENT_TIMESTAMP(6) WHERE sn='G0001'",printed);jdbc.update("UPDATE sn_item SET status='TO_ACQUIRE',current_batch_id=NULL,acquired_at=NULL WHERE sn='G0002'");
   var original=jdbc.queryForList("SELECT id,status,current_batch_id,current_print_id,acquired_at,printed_at FROM sn_item ORDER BY id");String id=submit(hq,"APPLY",transfer(uuid(),factory,destination(factory),"PI",null,null));run(id);
   assertThat(count("sn_transfer_member")).isEqualTo(13);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE status='APPLYING' AND current_transfer_id=?",Long.class,id)).isEqualTo(13);var fixed=transferItems(hq,id);assertThat(fixed).hasSize(13);assertThat(fixed.stream().map(n->n.path("fromStatus").asString())).contains("PRINTED","TO_ACQUIRE","TO_PRINT");
   user(11,"source_operator",3);jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)",factory);try(var local=new Browser("source_operator")){run(decision(local,id,"withdraw","1"));}
   assertThat(jdbc.queryForList("SELECT id,status,current_batch_id,current_print_id,acquired_at,printed_at FROM sn_item ORDER BY id")).isEqualTo(original);assertThat(data(hq.get("/transfers/"+id)).path("status").asString()).isEqualTo("WITHDRAWN");assertThat(members(hq,batch)).hasSize(13);assertThat(transferItems(hq,id).stream().map(n->n.path("snapshot"))).containsExactlyElementsOf(fixed.stream().map(n->n.path("snapshot")).toList());
  }
 }
 @Test void approvalMovesCurrentKeysAndCreatesOnlyExactAcquisitionExceptions()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b),target=destination(source),batch=acquire(b,source);var original=jdbc.queryForList("SELECT id,seq_pi,seq_dec,rule_version_id,source_system,source_account,source_bill_id,source_entry_id FROM sn_item ORDER BY id");var quotas=jdbc.queryForList("SELECT * FROM sn_source_entry_quota ORDER BY bill_id,source_entry_id");String id=submit(b,"APPLY",transfer(uuid(),source,target,"PI",null,null));run(id);run(decision(b,id,"approve","1"));
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE' AND factory_id=? AND pi='PI-2' AND current_batch_id IS NULL AND current_print_id IS NULL AND current_transfer_id IS NULL AND acquired_at IS NULL AND printed_at IS NULL",Long.class,target)).isEqualTo(13);assertThat(count("sn_acquire_exception")).isEqualTo(13);assertThat(last("PI-1")).isEqualTo(13);assertThat(last("PI-2")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key WHERE pi='PI-2'",Long.class)).isEqualTo(13);
   assertThat(jdbc.queryForList("SELECT id,seq_pi,seq_dec,rule_version_id,source_system,source_account,source_bill_id,source_entry_id FROM sn_item ORDER BY id")).isEqualTo(original);assertThat(jdbc.queryForList("SELECT * FROM sn_source_entry_quota ORDER BY bill_id,source_entry_id")).isEqualTo(quotas);assertThat(members(b,batch)).allMatch(n->!n.path("currentMember").asBoolean());
   jdbc.update("UPDATE sn_order_entry SET active=FALSE");String acquired=data(b.post("/acquisitions",take(uuid(),target,"PI-2"))).path("taskId").asString();run(acquired);assertThat(data(b.get("/acquisitions/results/"+acquired)).path("eligible").asString()).isEqualTo("13");assertThat(count("sn_acquire_batch")).isEqualTo(2);
  }
 }
 @Test void emptyMaterialAndSingleScopesPreservePerMemberValuesAndNoopRollsBack()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b),target=destination(source);var empty=transfer(uuid(),source,target,"MATERIAL","",null);String id=submit(b,"DIRECT",empty);run(id);assertThat(data(b.get("/transfers/"+id)).path("quantity").asString()).isEqualTo("2");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE factory_id=? AND material='' AND customer='C1'",Long.class,target)).isEqualTo(2);
   var single=transfer(uuid(),source,target,"SINGLE",null,"G0001");single.put("toCustomer","C-NEW");single.put("toMaterial","M-NEW");run(submit(b,"DIRECT",single));assertThat(jdbc.queryForObject("SELECT material FROM sn_item WHERE sn='G0001'",String.class)).isEqualTo("M-NEW");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE factory_id=?",Long.class,source)).isEqualTo(10);
   var noop=transfer(uuid(),source,source,"PI",null,null);noop.put("toPi","PI-1");String failed=submit(b,"DIRECT",noop);assertThat(fail(failed)).isEqualTo(1004000025);assertThat(count("sn_transfer")).isEqualTo(2);assertThat(count("sn_transfer_member")).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_pi_counter WHERE active_task_id IS NOT NULL",Long.class)).isZero();
  }
 }
 @Test void targetCollisionAfterApplicationRejectsWholeApprovalAndCanStillBeRejected()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b),target=destination(source);String id=submit(b,"APPLY",transfer(uuid(),source,target,"PI",null,null));run(id);run(data(b.post("/pi-initializations",init(uuid(),"PI-2",null,List.of("G0001")))).path("taskId").asString());String approve=decision(b,id,"approve","1");assertThat(fail(approve)).isEqualTo(1004000015);
   assertThat(data(b.get("/transfers/"+id)).path("status").asString()).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE status='APPLYING'",Long.class)).isEqualTo(13);assertThat(count("sn_acquire_exception")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key WHERE pi='PI-1'",Long.class)).isEqualTo(13);run(decision(b,id,"reject","1"));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE'",Long.class)).isEqualTo(13);
  }
 }
 @Test void factoryScopeAndMinimalTargetDirectoryDoNotGrantOtherInventoryOrWrites()throws Exception{
  try(var hq=new Browser("sn_hq")){String source=allocated(hq),target=destination(source);user(11,"source_operator",3);user(12,"target_operator",3);user(13,"outsider",3);user(14,"query_only",4);jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?),(12,?)",source,target);
   try(var local=new Browser("source_operator");var to=new Browser("target_operator");var stranger=new Browser("outsider");var reader=new Browser("query_only")){
    assertThat(data(local.get("/transfers/targets?size=1")).path("total").asString()).isEqualTo("3");assertThat(data(local.get("/transfers/factories?size=1&page=2")).path("items").size()).isEqualTo(1);assertThat(local.get("/transfers/candidates?factoryId="+target+"&pi=PI-2").statusCode()).isEqualTo(403);assertThat(reader.get("/transfers/targets").statusCode()).isEqualTo(403);assertThat(local.post("/transfers/direct",transfer(uuid(),source,target,"PI",null,null)).statusCode()).isEqualTo(403);
    String id=submit(local,"APPLY",transfer(uuid(),source,target,"PI",null,null));run(id);assertThat(data(local.get("/transfers")).path("items").get(0).path("direction").asString()).isEqualTo("OUT");assertThat(data(to.get("/transfers")).path("items").get(0).path("direction").asString()).isEqualTo("IN");assertThat(transferItems(to,id)).hasSize(13);assertThat(data(stranger.get("/transfers")).path("total").asString()).isEqualTo("0");assertThat(stranger.get("/transfers/"+id).statusCode()).isEqualTo(403);assertThat(to.post("/transfers/"+id+"/withdraw",Map.of("operationId",uuid(),"version","1")).statusCode()).isEqualTo(403);assertThat(local.post("/transfers/"+id+"/approve",Map.of("operationId",uuid(),"version","1")).statusCode()).isEqualTo(403);
   }
  }
 }
 @Test void replayAndCancellationKeepBothPisSerializedWithoutLeakingReservations()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b),target=destination(source);var body=transfer(uuid(),source,target,"PI",null,null);String id=submit(b,"APPLY",body);assertThat(submit(b,"APPLY",body)).isEqualTo(id);assertThat(count("sn_transfer_work")).isEqualTo(1);assertThat(b.post("/acquisitions",take(uuid(),source,"PI-1")).statusCode()).isEqualTo(409);assertThat(b.post("/acquisitions",take(uuid(),target,"PI-2")).statusCode()).isEqualTo(409);
   data(b.raw("POST","/tasks/"+id+"/cancel",Map.of(),b.token,b.csrf));assertThat(count("sn_transfer")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_pi_counter WHERE active_task_id IS NOT NULL",Long.class)).isZero();assertThat(submit(b,"APPLY",body)).isEqualTo(id);
   String next=submit(b,"APPLY",transfer(uuid(),source,target,"PI",null,null));run(next);assertThat(b.post("/transfers/"+next+"/approve",Map.of("operationId",uuid(),"version","2")).statusCode()).isEqualTo(409);String op=uuid();var decision=Map.of("operationId",op,"version","1","note","same");String task=data(b.post("/transfers/"+next+"/approve",decision)).path("taskId").asString();run(task);assertThat(data(b.post("/transfers/"+next+"/approve",decision)).path("taskId").asString()).isEqualTo(task);assertThat(data(b.get("/transfers/"+next)).path("version").asString()).isEqualTo("2");
  }
 }
 @Test void businessAuditFailureRollsBackHeaderMembersKeysAndExceptions()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b);String task=submit(b,"DIRECT",transfer(uuid(),source,destination(source),"PI",null,null));jdbc.execute("ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s6_transfer_audit CHECK(action<>'sn:transfer:direct' OR success=FALSE)");try{fail(task);}finally{jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s6_transfer_audit");}
   assertThat(count("sn_transfer")).isZero();assertThat(count("sn_transfer_member")).isZero();assertThat(count("sn_acquire_exception")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key WHERE pi='PI-1'",Long.class)).isEqualTo(13);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE' AND factory_id=?",Long.class,source)).isEqualTo(13);
  }
 }
 @Test void queuedRevocationFailsClosedAndFixedCursorRejectsTampering()throws Exception{
  try(var b=new Browser("sn_hq")){String source=allocated(b),target=destination(source);user(11,"local",3);jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)",source);String task;try(var local=new Browser("local")){task=submit(local,"APPLY",transfer(uuid(),source,target,"PI",null,null));}
   jdbc.update("DELETE FROM sn_user_factory WHERE user_id=11");jdbc.update("UPDATE sys_user SET security_version=security_version+1 WHERE id=11");fail(task);assertThat(count("sn_transfer")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_pi_counter WHERE active_task_id IS NOT NULL",Long.class)).isZero();String id=submit(b,"APPLY",transfer(uuid(),source,target,"PI",null,null));run(id);String cursor=data(b.get("/transfers/"+id+"/items?size=1")).path("nextCursor").asString();assertThat(b.get("/transfers/"+id+"/items?cursor="+cursor+"x").statusCode()).isEqualTo(400);
  }
 }
 @Test void laterSqlBlockCollisionRollsBackEarlierMembersAndDualGuardUpdates()throws Exception{
  try(var b=new Browser("sn_hq")){String bill=prepared(b);jdbc.update("UPDATE sn_order_entry SET qty=1001 WHERE bill_id=? AND source_entry_id='11'",bill);String source=jdbc.queryForObject("SELECT CAST(factory_id AS CHAR) FROM sn_order_bill WHERE id=?",String.class,bill);run(generate(b,preview(b,bill,"1001")));run(data(b.post("/allocations",Map.of("operationId",uuid(),"billId",bill,"expectedQuantity","1001"))).path("taskId").asString());String sn=jdbc.queryForObject("SELECT sn FROM sn_item ORDER BY gen_month DESC,id DESC LIMIT 1",String.class);run(data(b.post("/pi-initializations",init(uuid(),"PI-2",null,List.of(sn)))).path("taskId").asString());String task=submit(b,"DIRECT",transfer(uuid(),source,destination(source),"PI",null,null));assertThat(fail(task)).isEqualTo(1004000015);
   assertThat(count("sn_transfer_member")).isZero();assertThat(count("sn_acquire_exception")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_item WHERE pi='PI-1' AND status='TO_ACQUIRE'",Long.class)).isEqualTo(1001);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_key WHERE pi='PI-1'",Long.class)).isEqualTo(1001);assertThat(last("PI-1")).isEqualTo(1001);
  }
 }
 final class Browser implements AutoCloseable {
  final HttpClient client;final String token,csrf;
  Browser(String username)throws Exception{
   client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();var c=raw("GET","/system/auth/csrf",null,null,null);csrf=json.readTree(c.body()).path("data").path("token").asString();
   var login=raw("POST","/system/auth/login",Map.of("username",username,"password",PASSWORD),null,csrf);assertThat(login.statusCode()).isEqualTo(200);token=json.readTree(login.body()).path("data").path("accessToken").asString();
  }
  HttpResponse<String> get(String path)throws Exception{return raw("GET","/sn"+path,null,token,null);}
  HttpResponse<String> post(String path,Object body)throws Exception{return raw("POST","/sn"+path,body,token,csrf);}
  HttpResponse<String> raw(String method,String path,Object body,String bearer,String csrfToken)throws Exception{
   if("POST".equals(method) && csrfToken!=null) csrfToken=json.readTree(raw("GET","/system/auth/csrf",null,null,null).body()).path("data").path("token").asString();
   var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");if(bearer!=null)builder.header("Authorization","Bearer "+bearer);if(csrfToken!=null)builder.header("X-XSRF-TOKEN",csrfToken);
   builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
  @Override public void close(){client.close();}
 }
}

