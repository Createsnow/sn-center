package com.adtek.sn.service.support;

import com.adtek.sn.dto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;

/**
 * 首个受支持库存转换：仅原IMPORT且当前PRINTED、无批次/打印/在途引用的号，其他类型明确阻断。
 * 最大25000枚/25000个PI，整阶段原子；不是1.5亿容量实现。源月份、流水归属与当前PI分别保留。
 */
public final class LegacyInventoryPlan {
    public static final String VERSION="legacy-v1-import-inventory-v1";
    private final ObjectMapper json=new ObjectMapper();private final ZoneId zone;
    private final List<LegacyTargetRowDTO> rows=new ArrayList<>();private final Map<String,Long> factories=new HashMap<>(),actors=new HashMap<>();
    private final Map<Long,SequenceSpec> rules=new HashMap<>();private final Set<Long> usedRules=new HashSet<>();
    private final Map<String,Long> imported=new HashMap<>(),maximum=new HashMap<>();
    public LegacyInventoryPlan(LegacySnapshotPackage snapshot,LegacyMasterPlan master,String run)throws IOException,SQLException{
        zone=ZoneId.of(snapshot.manifest().sourceZone());
        for(var t:snapshot.manifest().tables())if(Set.of("sn_item","sn_key","sn_pi_counter").contains(t.name())&&Long.parseLong(t.rows())>25000)throw fail("INVENTORY_RESOURCE_LIMIT");
        for(var issue:snapshot.manifest().issues())if(issue.code().startsWith("SN_")||issue.code().equals("PI_COUNTS_MISMATCH"))throw fail("INVENTORY_SOURCE_BLOCKED");
        for(var row:master.rows()){
            var v=row.values();if(row.table().equals("sn_factory"))factories.put((String)v.get("code"),(Long)v.get("id"));
            if(row.table().equals("sn_rule_version")){long id=(Long)v.get("id");rules.put(id,new SequenceSpec((String)v.get("prefix"),(String)v.get("suffix"),Math.toIntExact((Long)v.get("base")),Math.toIntExact((Long)v.get("seq_length")),(String)v.get("alphabet")));if((Boolean)v.get("used"))usedRules.add(id);}
        }
        for(var user:master.users())actors.put(user.username(),user.id());
        var sourceKeys=new HashMap<List<String>,LegacyStageRowDTO>();
        each(snapshot,"sn_key",row->{var v=json.readTree(row.payload());if(sourceKeys.put(List.of(text(v,"pi_no",128),text(v,"sn",64)),row)!=null)throw fail("INVENTORY_DUPLICATE");});
        var sequences=new HashSet<List<String>>();
        each(snapshot,"sn_item",row->{
            var v=json.readTree(row.payload());
            if(!"IMPORT".equals(text(v,"source",8))||!"PRINTED".equals(text(v,"status",24))||!text(v,"bill_no",64).isEmpty())throw fail("INVENTORY_UNSUPPORTED_SOURCE");
            for(String field:List.of("job_id","batch_no","print_no","transfer_id","prev_status","allocated_at","acquired_at"))if(nullable(v,field)!=null)throw fail("INVENTORY_HISTORY_EVIDENCE_REQUIRED");
            String pi=required(v,"pi_no",128),sn=required(v,"sn",64),originalPi=required(v,"seq_pi_no",128);Long seq=number(v,"seq_dec",true,false),rule=number(v,"rule_version_id",true,true);
            if(seq!=null){if(rule==null||!rules.containsKey(rule)||!usedRules.contains(rule)||!SequenceCodec.format(seq,rules.get(rule)).equals(sn))throw fail("INVENTORY_RULE_MISMATCH");if(!sequences.add(List.of(originalPi,seq.toString())))throw fail("INVENTORY_DUPLICATE");maximum.merge(originalPi,seq,Math::max);}
            else if(rule!=null)throw fail("INVENTORY_RULE_MISMATCH");
            imported.merge(originalPi,1L,Math::addExact);
            var sourceKey=sourceKeys.remove(List.of(pi,sn));if(sourceKey==null)throw fail("INVENTORY_KEY_MISMATCH");var key=json.readTree(sourceKey.payload());
            if(!Objects.equals(number(key,"seq_dec",true,false),seq)||!Objects.equals(nullable(key,"seq_pi_no"),seq==null?null:originalPi))throw fail("INVENTORY_KEY_MISMATCH");
            String month=required(v,"gen_month",6);LocalDate generated;try{if(!month.matches("[0-9]{6}"))throw fail("INVENTORY_MONTH_INVALID");generated=YearMonth.of(Integer.parseInt(month.substring(0,4)),Integer.parseInt(month.substring(4))).atDay(1);if(generated.getYear()<1970)throw fail("INVENTORY_MONTH_INVALID");}catch(DateTimeException e){throw fail("INVENTORY_MONTH_INVALID");}
            String id=UUID.nameUUIDFromBytes((snapshot.manifest().sourceFingerprint()+"\nsn_item\n"+row.key()).getBytes(StandardCharsets.UTF_8)).toString();String factory=nullable(v,"factory_code");Long factoryId=factory==null?null:factories.get(factory);if(factory!=null&&factoryId==null)throw fail("INVENTORY_FACTORY_ORPHAN");
            add("sn_item","sn_item",row,values("gen_month",generated,"id",id,"sn",sn,"pi",pi,"seq_pi",seq==null?null:originalPi,"seq_dec",seq,"customer",text(v,"customer_code",64),"material",text(v,"material_code",128),"factory_id",factoryId,"source","IMPORT","status","PRINTED","rule_version_id",rule,"source_system",null,"source_account",null,"source_bill_id",null,"source_entry_id",null,"bill_id",null,"bill_no","","source_pi",originalPi,"source_org_id",null,"source_factory_id",null,"task_id",null,"allocation_id",null,"created_at",time(v,"created_at",false),"printed_at",time(v,"printed_at",false),"updated_at",time(v,"updated_at",false),"current_batch_id",null,"current_print_id",null,"acquired_at",null,"acquire_exception_id",null,"current_transfer_id",null,"legacy_run_id",run));
            add("sn_key","sn_key",sourceKey,values("id",id,"gen_month",generated,"pi",pi,"sn",sn,"seq_pi",seq==null?null:originalPi,"seq_dec",seq));
        });
        if(!sourceKeys.isEmpty())throw fail("INVENTORY_KEY_MISMATCH");var counters=new HashSet<String>();
        each(snapshot,"sn_pi_counter",row->{
            var v=json.readTree(row.payload());String pi=required(v,"pi_no",128);if(!counters.add(pi))throw fail("INVENTORY_DUPLICATE");long last=number(v,"last_seq_dec",false,false),generated=number(v,"generated_qty",false,false),qty=number(v,"imported_qty",false,false);Long start=number(v,"start_seq_dec",true,true);
            if(generated!=0||qty!=imported.getOrDefault(pi,0L)||last<Math.max(maximum.getOrDefault(pi,0L),start==null?0:start-1))throw fail("INVENTORY_COUNTER_MISMATCH");
            String locked=required(v,"start_locked",1);if(!Set.of("0","1").contains(locked)||((qty>0||start!=null)&&!locked.equals("1")))throw fail("INVENTORY_COUNTER_MISMATCH");
            String actor=nullable(v,"locked_by");add("sn_pi_counter","sn_pi_counter",row,values("pi",pi,"last_seq",last,"generated_qty",0L,"imported_qty",qty,"start_seq",start,"start_locked",locked.equals("1"),"locked_by",actor==null?null:actors.get(actor),"locked_at",time(v,"locked_at",true),"active_task_id",null));
        });
        if(!counters.containsAll(imported.keySet()))throw fail("INVENTORY_COUNTER_MISSING");
    }
    private void each(LegacySnapshotPackage snapshot,String table,LegacySnapshotPackage.RowConsumer consumer)throws IOException,SQLException{snapshot.rows(snapshot.manifest().tables().stream().filter(t->t.name().equals(table)).findFirst().orElseThrow(),consumer);}
    private String nullable(JsonNode v,String field){var value=v.get(field);if(value==null)throw fail("INVENTORY_FIELD_INVALID");return value.isNull()?null:value.asString();}
    private String text(JsonNode v,String field,int max){String value=nullable(v,field);if(value==null||value.codePointCount(0,value.length())>max||value.codePoints().anyMatch(Character::isISOControl))throw fail("INVENTORY_FIELD_INVALID");return value;}
    private String required(JsonNode v,String field,int max){String value=text(v,field,max);if(value.isBlank())throw fail("INVENTORY_FIELD_INVALID");return value;}
    private Long number(JsonNode v,String field,boolean optional,boolean positive){String value=nullable(v,field);if(optional&&value==null)return null;if(value==null||!value.matches(positive?"[1-9][0-9]{0,18}":"0|[1-9][0-9]{0,18}"))throw fail("INVENTORY_NUMBER_INVALID");try{return Long.parseLong(value);}catch(NumberFormatException e){throw fail("INVENTORY_NUMBER_INVALID");}}
    private Instant time(JsonNode v,String field,boolean optional){String value=nullable(v,field);if(value==null&&optional)return null;try{if(value==null)throw fail("INVENTORY_TIME_INVALID");var local=LocalDateTime.parse(value.replace(' ','T'));var offsets=zone.getRules().getValidOffsets(local);if(offsets.size()!=1)throw fail("TIME_AMBIGUOUS");return local.toInstant(offsets.getFirst());}catch(DateTimeException e){throw fail("INVENTORY_TIME_INVALID");}}
    private void add(String target,String source,LegacyStageRowDTO row,Map<String,Object> values){rows.add(new LegacyTargetRowDTO(target,source,row.key(),hash(VERSION+"\n"+row.digest()),Collections.unmodifiableMap(values)));}
    private static Map<String,Object> values(Object...pairs){var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    public List<LegacyTargetRowDTO> rows(){return List.copyOf(rows);}
    public long count(String table){return rows.stream().filter(r->r.table().equals(table)).count();}
    public String digest(String fingerprint,String masterDigest){return hash(json.writeValueAsString(List.of(VERSION,fingerprint,masterDigest,rows)));}
    private static String hash(String value){return LegacySnapshotPackage.sha(value.getBytes(StandardCharsets.UTF_8));}
    private static IllegalStateException fail(String code){return new IllegalStateException("LEGACY_"+code);}
}
