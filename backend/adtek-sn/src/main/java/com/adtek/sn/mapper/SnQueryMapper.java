package com.adtek.sn.mapper;

import com.adtek.sn.domain.*;
import com.adtek.sn.query.SnItemQuery;

import org.apache.ibatis.annotations.*;

import java.time.*;
import java.util.*;

/** 显式可信范围同用于读取、固定快照和原范围下载复核，不接收客户端SQL。 */
@Mapper
public interface SnQueryMapper {
    record Scope(boolean all, List<Long> factories, boolean piAll) {}

    String SCOPE =
            "<choose><when test='s.piAll'> AND i.factory_id IS NOT NULL</when>"
                    + "<when test='!s.all'>"
                    + " AND <choose><when test='!s.factories.isEmpty()'> i.factory_id IN <foreach"
                    + " collection='s.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach></when><otherwise>1=0</otherwise></choose>"
                    + "</when></choose>";
    String FILTER =
            "<if test='q.factoryId!=null'> AND i.factory_id=#{q.factoryId}</if><if"
                    + " test='q.pi!=null'> AND i.pi=#{q.pi}</if><if test='q.customer!=null'> AND"
                    + " i.customer=#{q.customer}</if><if"
                    + " test='q.materialMode==&quot;EMPTY&quot;'> AND"
                    + " i.material=''</if><if test='q.materialMode==&quot;VALUE&quot;'> AND"
                    + " i.material=#{q.material}</if><if test='q.status!=null'> AND"
                    + " i.status=#{q.status}</if><if test='q.sn!=null'> AND i.sn=#{q.sn}</if><if"
                    + " test='q.billNo!=null'> AND i.bill_no=#{q.billNo}</if><if"
                    + " test='q.batchId!=null'> AND i.current_batch_id=#{q.batchId}</if>"
                    + "<if test='q.batchNo!=null'> AND EXISTS (SELECT 1 FROM sn_acquire_batch b"
                    + " WHERE b.id=i.current_batch_id AND b.batch_no=#{q.batchNo})</if>";
    String COLUMNS =
            "i.gen_month,i.id,i.sn,i.pi,i.seq_pi,i.seq_dec,i.rule_version_id,"
                    + "i.customer,i.material,i.factory_id,f.code"
                    + " factory_code,f.name"
                    + " factory_name,i.status,i.source,i.bill_no,i.source_system,"
                    + "i.source_account,i.source_bill_id,i.source_entry_id,i.current_batch_id,"
                    + "i.current_print_id,i.current_transfer_id,i.created_at,i.acquired_at,"
                    + "i.printed_at,b.batch_no current_batch_no,k.creation_order";

    String ITEM_FROM =
            " FROM sn_item i JOIN sn_key k ON k.sn=i.sn"
                    + " LEFT JOIN sn_factory f ON f.id=i.factory_id"
                    + " LEFT JOIN sn_acquire_batch b ON b.id=i.current_batch_id WHERE 1=1 ";

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + ITEM_FROM
                    + SCOPE
                    + FILTER
                    + "<if test='month!=null'> AND (i.gen_month&gt;#{month} OR"
                    + " (i.gen_month=#{month} AND i.id&gt;#{id}))</if> ORDER BY i.gen_month,i.id"
                    + " LIMIT #{size}</script>")
    List<SnQueryItemEntity> items(
            @Param("s") Scope scope,
            @Param("q") SnItemQuery query,
            @Param("month") LocalDate month,
            @Param("id") String after,
            @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM sn_item i WHERE 1=1 " + SCOPE + FILTER + "</script>")
    long count(@Param("s") Scope scope, @Param("q") SnItemQuery query);

    @Select(
            "<script>SELECT COUNT(*) FROM (SELECT 1 FROM sn_item i WHERE 1=1 "
                    + SCOPE
                    + FILTER
                    + " LIMIT 100001) capped</script>")
    long cappedCount(@Param("s") Scope scope, @Param("q") SnItemQuery query);

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + ITEM_FROM
                    + SCOPE
                    + FILTER
                    + " ORDER BY <choose><when test='q.pi!=null'>"
                    + " i.material,i.seq_pi,i.seq_dec,k.creation_order"
                    + "</when><otherwise>k.creation_order DESC</otherwise></choose>"
                    + " LIMIT #{size} OFFSET #{offset}</script>")
    List<SnQueryItemEntity> page(
            @Param("s") Scope scope,
            @Param("q") SnItemQuery query,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + ITEM_FROM
                    + SCOPE
                    + FILTER
                    + " AND k.creation_order&gt;#{after} ORDER BY k.creation_order"
                    + " LIMIT #{size}</script>")
    List<SnQueryItemEntity> exportItems(
            @Param("s") Scope scope,
            @Param("q") SnItemQuery query,
            @Param("after") long after,
            @Param("size") int size);

    @Insert(
            "INSERT INTO sn_query_export(task_id,actor_id,format,filters,quantity,format_version)"
                    + " VALUES(#{taskId},#{actorId},#{format},#{filters},"
                    + "#{quantity},#{formatVersion})")
    int export(SnQueryExportEntity row);

    @Insert(
            "<script>INSERT INTO sn_query_export_row(task_id,ordinal,factory_id,pi,cells) VALUES"
                    + " <foreach collection='rows' item='r'"
                    + " separator=','>(#{r.taskId},#{r.ordinal},#{r.factoryId},#{r.pi},"
                    + "#{r.cells})</foreach></script>")
    int rows(@Param("rows") List<SnExportRowEntity> rows);

    @Select(
            "SELECT task_id,actor_id,format,CAST(filters AS CHAR) filters,quantity,"
                    + "created_at,format_version FROM"
                    + " sn_query_export WHERE task_id=#{id}")
    SnQueryExportEntity exportById(String id);

    @Select(
            "SELECT task_id,ordinal,factory_id,pi,CAST(cells AS CHAR) cells FROM"
                    + " sn_query_export_row WHERE task_id=#{id} AND ordinal>#{after} ORDER"
                    + " BY ordinal"
                    + " LIMIT #{size}")
    List<SnExportRowEntity> exportRows(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    @Select(
            "<script>SELECT COUNT(*) FROM sn_query_export_row i WHERE i.task_id=#{id} "
                    + SCOPE
                    + "<if test='s.piAll'> AND i.pi=#{pi}</if></script>")
    long exportVisible(@Param("s") Scope scope, @Param("id") String id, @Param("pi") String pi);

    @Select(
            "SELECT task_id,actor_id,format,CAST(filters AS CHAR) filters,quantity,"
                    + "created_at,format_version FROM"
                    + " sn_query_export WHERE actor_id=#{actor} ORDER BY created_at DESC,"
                    + "task_id DESC"
                    + " LIMIT #{size} OFFSET #{offset}")
    List<SnQueryExportEntity> history(
            @Param("actor") long actor, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM sn_query_export WHERE actor_id=#{actor}")
    long historyCount(long actor);

    @Select(
            "<script>SELECT i.status name,COUNT(*) quantity FROM sn_item i WHERE"
                    + " i.status IN ('PENDING_ALLOC','TO_ACQUIRE','TO_PRINT','APPLYING') "
                    + SCOPE
                    + " GROUP BY i.status</script>")
    List<SnCountEntity> states(@Param("s") Scope scope);

    @Select("SELECT COALESCE(SUM(generated_qty),0) FROM sn_pi_counter")
    long generated();

    @Select("SELECT COALESCE(SUM(imported_qty),0) FROM sn_pi_counter")
    long imported();

    String TODAY =
            "SELECT CASE action WHEN 'sn:generation:execute' THEN 'GENERATE'"
                    + " WHEN 'sn:allocation:execute' THEN 'ALLOCATE'"
                    + " WHEN 'sn:acquire:take' THEN 'TAKE'"
                    + " WHEN 'sn:print:execute' THEN 'PRINT'"
                    + " WHEN 'sn:acquire:callback' THEN 'CALLBACK' END name,"
                    + " quantity,factory_id,created_at at_time FROM sn_audit"
                    + " WHERE result='SUCCESS' AND action IN ('sn:generation:execute',"
                    + " 'sn:allocation:execute','sn:acquire:take','sn:print:execute',"
                    + " 'sn:acquire:callback')";

    @Select(
            "<script>SELECT i.name,SUM(i.quantity) quantity FROM ("
                    + TODAY
                    + ") i WHERE i.at_time&gt;=#{from} AND i.at_time&lt;#{to} "
                    + SCOPE
                    + " GROUP BY i.name ORDER BY i.name</script>")
    List<SnCountEntity> today(
            @Param("s") Scope scope, @Param("from") Instant from, @Param("to") Instant to);

    @Select(
            "<script>SELECT i.* FROM sn_work i JOIN sn_generation_job j ON j.task_id=i.task_id"
                    + " WHERE i.kind='GENERATE' "
                    + SCOPE
                    + " ORDER BY j.id DESC LIMIT 8</script>")
    List<SnWorkEntity> recent(@Param("s") Scope scope);

    @Select("SELECT id FROM sn_generation_job WHERE task_id=#{taskId}")
    long generationNumber(String taskId);
}
