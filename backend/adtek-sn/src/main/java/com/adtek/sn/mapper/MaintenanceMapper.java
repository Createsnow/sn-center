package com.adtek.sn.mapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 独立维护连接的JDBC映射；表集合和标识符封闭，DDL不能使用业务MyBatis事务/页面数据源。 */
public final class MaintenanceMapper {
    private static final Map<String,String> TABLES = Map.of(
            "sn_item", "gen_month", "sn_audit", "created_at", "sn_audit_archive", "created_at");
    private static final String AUDIT_COLUMNS = "id,created_at,actor_id,operator,operator_name,roles,source,action,result,factory_id,factory_code,factory_name,pi,to_factory_id,to_factory_code,to_pi,customer,material,bill_no,start_sn,end_sn,quantity,before_status,after_status,batch_id,operation_id,transfer_id,reason,error_code,trace_id,detail";
    public record Partition(String name, LocalDate upper) {}
    public record Digest(long rows, String sha256) {}
    public record Run(String digest, String state, String result) {}

    public String scalar(Connection c, String sql, Object... values) throws SQLException {
        try (var p=prepare(c,sql,values);var r=p.executeQuery()) { return r.next()?r.getString(1):null; }
    }
    public int update(Connection c, String sql, Object... values) throws SQLException {
        try(var p=prepare(c,sql,values)){return p.executeUpdate();}
    }
    private PreparedStatement prepare(Connection c,String sql,Object...values)throws SQLException{
        var p=c.prepareStatement(sql);p.setQueryTimeout(120);
        for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p;
    }
    public Run run(Connection c,String id)throws SQLException{
        try(var p=prepare(c,"SELECT request_digest,state,result FROM sn_maintenance_run WHERE run_id=?",id);var r=p.executeQuery()){
            return r.next()?new Run(r.getString(1),r.getString(2),r.getString(3)):null;
        }
    }
    /** 每条DDL由已校验元数据和LocalDate组成，返回顺序就是必须执行的重组顺序。 */
    public List<String> partitionPlan(Connection c,YearMonth month,int ahead)throws SQLException{
        var plan=new ArrayList<String>();
        for(String table:List.of("sn_item","sn_audit","sn_audit_archive")){
            var parts=partitions(c,table);
            for(int i=0;i<=ahead+1;i++){
                LocalDate boundary=month.plusMonths(i).atDay(1),lower=null;
                for(int n=0;n<parts.size();n++){
                    var part=parts.get(n);
                    if(boundary.equals(part.upper()))break;
                    if(part.upper()==null||boundary.isBefore(part.upper())){
                        String left=lower==null?"p_history":"p"+YearMonth.from(lower).toString().replace("-","");
                        String right=part.upper()==null?part.name():"p"+YearMonth.from(boundary).toString().replace("-","");
                        for(int j=0;j<parts.size();j++)if(j!=n&&(parts.get(j).name().equals(left)||parts.get(j).name().equals(right)))throw new IllegalStateException("PARTITION_NAME_CONFLICT");
                        String upper=part.upper()==null?"MAXVALUE":"'"+part.upper()+"'";
                        plan.add("ALTER TABLE "+table+" REORGANIZE PARTITION "+part.name()+" INTO (PARTITION "+left+" VALUES LESS THAN ('"+boundary+"'), PARTITION "+right+" VALUES LESS THAN ("+upper+"))");
                        parts.set(n,new Partition(left,boundary));parts.add(n+1,new Partition(right,part.upper()));break;
                    }
                    lower=part.upper();
                }
            }
        }
        return List.copyOf(plan);
    }
    private ArrayList<Partition> partitions(Connection c,String table)throws SQLException{
        if(!TABLES.containsKey(table))throw new IllegalArgumentException("MAINTENANCE_TABLE");
        var parts=new ArrayList<Partition>();
        try(var p=prepare(c,"SELECT partition_name,partition_description,partition_method,partition_expression FROM information_schema.partitions WHERE table_schema=DATABASE() AND table_name=? ORDER BY partition_ordinal_position",table);var r=p.executeQuery()){
            LocalDate previous=null;
            while(r.next()){
                String name=r.getString(1),description=r.getString(2);
                if(name==null||!name.matches("p_history|p_future|pmax|p[0-9]{6}")||!"RANGE COLUMNS".equals(r.getString(3))||!TABLES.get(table).equals(r.getString(4).replace("`","")))throw new IllegalStateException("PARTITION_STRUCTURE");
                LocalDate upper=null;
                if(!"MAXVALUE".equals(description)){
                    String value=description.replace("'","");
                    if(!value.matches("[0-9]{4}-[0-9]{2}-01( 00:00:00(\\.0+)?)?"))throw new IllegalStateException("PARTITION_BOUNDARY");
                    upper=LocalDate.parse(value.substring(0,10));
                    if(previous!=null&&!upper.isAfter(previous))throw new IllegalStateException("PARTITION_ORDER");
                }
                if(!parts.isEmpty()&&parts.getLast().upper()==null)throw new IllegalStateException("PARTITION_MAXVALUE");
                parts.add(new Partition(name,upper));previous=upper;
            }
        }
        if(parts.isEmpty()||parts.getLast().upper()!=null)throw new IllegalStateException("PARTITION_FALLBACK_MISSING");
        return parts;
    }
    public long expiredPreviews(Connection c)throws SQLException{
        return Long.parseLong(scalar(c,"SELECT COUNT(*) FROM sn_preview WHERE task_id IS NULL AND expires_at<UTC_TIMESTAMP(6)"));
    }
    public int cleanPreviews(Connection c,int limit)throws SQLException{
        // 与提交生成共用控制行；task_id条件再次核验，已绑定失败/取消预演也不能清理。
        scalar(c,"SELECT revision FROM sn_snapshot_control WHERE id=1 FOR UPDATE");
        return update(c,"DELETE FROM sn_preview WHERE task_id IS NULL AND expires_at<UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT ?",limit);
    }
    public long auditCount(Connection c,String table,YearMonth month)throws SQLException{
        auditTable(table);return Long.parseLong(scalar(c,"SELECT COUNT(*) FROM "+table+" WHERE created_at>=? AND created_at<?",month.atDay(1),month.plusMonths(1).atDay(1)));
    }
    public int copyAudit(Connection c,YearMonth month)throws SQLException{
        String cols=Arrays.stream(AUDIT_COLUMNS.split(",")).map(x->"s."+x).collect(java.util.stream.Collectors.joining(","));
        return update(c,"INSERT INTO sn_audit_archive ("+AUDIT_COLUMNS+") SELECT "+cols+" FROM sn_audit s WHERE s.created_at>=? AND s.created_at<? AND NOT EXISTS (SELECT 1 FROM sn_audit_archive a WHERE a.created_at=s.created_at AND a.id=s.id)",month.atDay(1),month.plusMonths(1).atDay(1));
    }
    /** 按复合主键流式读取；每个字段都有长度帧，NULL=-1，避免拼接歧义和内存收集整月。 */
    public Digest auditDigest(Connection c,String table,YearMonth month)throws SQLException{
        return auditDigest(c,table,month,false);
    }
    /** 终结归档持有两张表整月的行/间隙锁，直到删除与运行成功记录一起提交。 */
    public Digest auditDigest(Connection c,String table,YearMonth month,boolean lock)throws SQLException{
        auditTable(table);MessageDigest digest=sha256();long rows=0;
        try(var p=prepare(c,"SELECT "+AUDIT_COLUMNS+" FROM "+table+" WHERE created_at>=? AND created_at<? ORDER BY created_at,id"+(lock?" FOR UPDATE":""),month.atDay(1),month.plusMonths(1).atDay(1))){
            p.setFetchSize(Integer.MIN_VALUE);
            try(var r=p.executeQuery()){
                int columns=r.getMetaData().getColumnCount();
                while(r.next()){
                    for(int i=1;i<=columns;i++){byte[] bytes=r.getBytes(i);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
                    rows++;
                }
            }
        }
        return new Digest(rows,HexFormat.of().formatHex(digest.digest()));
    }
    private void auditTable(String table){if(!Set.of("sn_audit","sn_audit_archive").contains(table))throw new IllegalArgumentException("AUDIT_TABLE");}
    public static String digest(String value){return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
