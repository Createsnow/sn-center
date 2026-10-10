package com.adtek.sn.mapper;

import com.adtek.sn.domain.*;
import com.adtek.sn.query.SnAuditQuery;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** 可信工厂范围包含原转出/转入双方；归档与在线使用相同过滤。SQL标识符仅来自常量白名单。 */
@Mapper
public interface SnAuditMapper {
    String SOURCE =
            "<choose><when test='q.storage==&quot;ONLINE&quot;'>sn_audit</when><when"
                    + " test='q.storage==&quot;ARCHIVE&quot;'>sn_audit_archive</when>"
                    + "<otherwise>(SELECT"
                    + " id,created_at,actor_id,operator,operator_name,roles,source,action,"
                    + "result,factory_id,factory_code,factory_name,pi,to_factory_id,"
                    + "to_factory_code,to_pi,customer,material,bill_no,start_sn,end_sn,"
                    + "quantity,before_status,after_status,batch_id,operation_id,transfer_id,"
                    + "reason,error_code,trace_id,detail,detail_text,error_message"
                    + " FROM sn_audit UNION ALL SELECT"
                    + " a.id,a.created_at,a.actor_id,a.operator,a.operator_name,a.roles,"
                    + "a.source,a.action,a.result,a.factory_id,a.factory_code,a.factory_name,"
                    + "a.pi,a.to_factory_id,a.to_factory_code,a.to_pi,a.customer,a.material,"
                    + "a.bill_no,a.start_sn,a.end_sn,a.quantity,a.before_status,a.after_status,"
                    + "a.batch_id,a.operation_id,a.transfer_id,a.reason,a.error_code,"
                    + "a.trace_id,a.detail,a.detail_text,a.error_message"
                    + " FROM sn_audit_archive a WHERE NOT EXISTS(SELECT 1 FROM sn_audit o WHERE"
                    + " o.created_at=a.created_at AND o.id=a.id))</otherwise></choose> i";
    String BATCH_NO = "(SELECT b.batch_no FROM sn_acquire_batch b WHERE b.id=i.batch_id)";
    String TRANSFER_NO = "(SELECT t.transfer_no FROM sn_transfer t WHERE t.id=i.transfer_id)";
    String PRESENTATION = "i.*," + BATCH_NO + " batch_no," + TRANSFER_NO + " transfer_no";
    String IDENTITY =
            " LEFT JOIN sn_audit_identity creation ON creation.audit_id=i.id"
                    + " AND creation.created_at=i.created_at";
    String EXPORT_ORDER = "COALESCE(creation.creation_order,0) DESC,i.created_at DESC,i.id DESC";
    String SCOPE =
            "<if test='!s.all'> AND <choose><when test='!s.factories.isEmpty()'>(i.factory_id IN"
                    + " <foreach collection='s.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach> OR i.to_factory_id IN <foreach"
                    + " collection='s.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach>)</when><otherwise>1=0</otherwise></choose></if>";
    String EXPORT_SCOPE =
            "<if test='!e.all'> AND <choose><when test='!e.factories.isEmpty()'>(i.factory_id IN"
                    + " <foreach collection='e.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach> OR i.to_factory_id IN <foreach"
                    + " collection='e.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach>)</when><otherwise>1=0</otherwise></choose></if>";
    String FILTER =
            " i.created_at &gt;= #{from} AND i.created_at &lt; #{to}<if test='q.factoryId!=null'>"
                    + " AND (i.factory_id=#{q.factoryId} OR i.to_factory_id=#{q.factoryId})</if><if"
                    + " test='q.action!=null'> AND i.action=#{q.action}</if><if"
                    + " test='q.operator!=null'> AND i.operator=#{q.operator}</if><if"
                    + " test='q.pi!=null'> AND i.pi=#{q.pi}</if><if test='q.source!=null'> AND"
                    + " i.source=#{q.source}</if><if test='q.result!=null'> AND"
                    + " i.result=#{q.result}</if><if test='q.batchId!=null'> AND"
                    + " (i.batch_id=#{q.batchId} OR "
                    + BATCH_NO
                    + "=#{q.batchId})</if><if test='keyword!=null'> AND (i.start_sn LIKE"
                    + " #{keyword} ESCAPE '!' OR i.end_sn LIKE #{keyword} ESCAPE '!' OR"
                    + " i.operation_id"
                    + " LIKE #{keyword} ESCAPE '!' OR i.transfer_id LIKE #{keyword} ESCAPE '!' OR"
                    + " "
                    + TRANSFER_NO
                    + " LIKE #{keyword} ESCAPE '!' OR i.bill_no LIKE #{keyword} ESCAPE '!' OR"
                    + " i.detail_text LIKE #{keyword} ESCAPE '!' OR"
                    + " (i.detail_text IS NULL AND CAST(i.detail AS CHAR) LIKE"
                    + " #{keyword} ESCAPE '!'))</if>";

    @Insert(
            "INSERT INTO"
                    + " sn_audit(id,created_at,actor_id,operator,operator_name,roles,source,"
                    + "action,result,factory_id,factory_code,factory_name,pi,to_factory_id,"
                    + "to_factory_code,to_pi,customer,material,bill_no,start_sn,end_sn,"
                    + "quantity,before_status,after_status,batch_id,operation_id,transfer_id,"
                    + "reason,error_code,trace_id,detail,detail_text,error_message)"
                    + " VALUES(#{r.id},#{r.createdAt},#{r.actorId},#{r.operator},"
                    + "#{r.operatorName},#{r.roles},#{r.source},#{r.action},#{r.result},"
                    + "#{r.factoryId},#{r.factoryCode},#{r.factoryName},#{r.pi},"
                    + "#{r.toFactoryId},#{r.toFactoryCode},#{r.toPi},#{r.customer},"
                    + "#{r.material},#{r.billNo},#{r.startSn},#{r.endSn},#{r.quantity},"
                    + "#{r.beforeStatus},#{r.afterStatus},#{r.batchId},#{r.operationId},"
                    + "#{r.transferId},#{r.reason},#{r.errorCode},#{r.traceId},"
                    + "#{r.detail},#{r.detailText},#{r.errorMessage})")
    int insert(@Param("r") SnAuditEntity row);

    @Select(
            "<script>SELECT "
                    + PRESENTATION
                    + " FROM "
                    + SOURCE
                    + " WHERE "
                    + FILTER
                    + SCOPE
                    + "<if test='time!=null'> AND (i.created_at &lt; #{time} OR"
                    + " (i.created_at=#{time} AND i.id &lt; #{id}))</if> ORDER BY i.created_at"
                    + " DESC,i.id DESC LIMIT #{size}</script>")
    List<SnAuditEntity> page(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword,
            @Param("time") Instant time,
            @Param("id") String id,
            @Param("size") int size);

    @Select(
            "<script>SELECT "
                    + PRESENTATION
                    + " FROM "
                    + SOURCE
                    + IDENTITY
                    + " WHERE "
                    + FILTER
                    + SCOPE
                    + " ORDER BY i.created_at DESC,COALESCE(creation.creation_order,0) DESC,"
                    + "i.id DESC"
                    + " LIMIT #{size} OFFSET #{offset}</script>")
    List<SnAuditEntity> numberedPage(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM " + SOURCE + " WHERE " + FILTER + SCOPE + "</script>")
    long count(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword);

    @Select(
            "<script>SELECT "
                    + PRESENTATION
                    + " FROM "
                    + SOURCE
                    + " WHERE "
                    + FILTER
                    + SCOPE
                    + " AND i.created_at=#{time} AND i.id=#{id}</script>")
    SnAuditEntity get(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword,
            @Param("time") Instant time,
            @Param("id") String id);

    @Insert(
            "INSERT INTO sn_audit_export(task_id,actor_id,format,filters,quantity,format_version)"
                    + " VALUES(#{r.taskId},#{r.actorId},#{r.format},#{r.filters},0,"
                    + "#{r.formatVersion})")
    int export(@Param("r") SnAuditExportEntity row);

    @Insert("INSERT INTO sn_audit_identity(audit_id,created_at) VALUES(#{r.id},#{r.createdAt})")
    int creationIdentity(@Param("r") SnAuditEntity row);

    /** 格式5固定24列及详情类型标记；历史格式仍从已有行恢复，不重新投影。 */
    @Insert(
            "<script>INSERT INTO sn_audit_export_row"
                    + "(task_id,ordinal,factory_id,to_factory_id,cells) SELECT #{task},"
                    + "ROW_NUMBER() OVER(ORDER BY "
                    + EXPORT_ORDER
                    + "),i.factory_id,i.to_factory_id,"
                    + "JSON_ARRAY(DATE_FORMAT(DATE_ADD(i.created_at,INTERVAL 8 HOUR),"
                    + "'%Y-%m-%d %H:%i:%s'),"
                    + "i.operator,i.operator_name,i.roles,i.source,i.action,i.result,"
                    + "i.factory_code,"
                    + "i.pi,i.customer,i.material,i.bill_no,i.start_sn,i.end_sn,i.quantity,"
                    + "i.before_status,i.after_status,"
                    + BATCH_NO
                    + ",i.operation_id,"
                    + TRANSFER_NO
                    + ",i.reason,COALESCE(i.error_message,i.error_code),i.trace_id,"
                    + "COALESCE(i.detail_text,CAST(i.detail AS CHAR)),"
                    + "IF(i.detail_text IS NULL,'JSON','TEXT')) FROM "
                    + SOURCE
                    + IDENTITY
                    + " WHERE "
                    + FILTER
                    + SCOPE
                    + EXPORT_SCOPE
                    + " ORDER BY "
                    + EXPORT_ORDER
                    + " LIMIT #{limit}</script>")
    int prototypeSnapshot(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword,
            @Param("e") SnQueryMapper.Scope exporting,
            @Param("task") String task,
            @Param("limit") int limit);

    @Update("UPDATE sn_audit_export SET quantity=#{qty} WHERE task_id=#{id}")
    int quantity(@Param("id") String id, @Param("qty") long quantity);

    @Insert(
            "<script>INSERT INTO"
                    + " sn_audit_export_row(task_id,ordinal,factory_id,to_factory_id,cells) SELECT"
                    + " #{task},ROW_NUMBER() OVER(ORDER BY i.created_at DESC,i.id"
                    + " DESC),i.factory_id,i.to_factory_id,JSON_ARRAY(DATE_FORMAT(i.created_at,"
                    + "'%Y-%m-%dT%H:%i:%s.%fZ'),COALESCE(CAST(i.operator"
                    + " AS CHAR),''),COALESCE(CAST(i.operator_name AS CHAR),''),"
                    + "COALESCE(CAST(i.roles"
                    + " AS CHAR),''),COALESCE(CAST(i.source AS CHAR),''),COALESCE(CAST(i.action AS"
                    + " CHAR),''),COALESCE(CAST(i.result AS CHAR),''),"
                    + "COALESCE(CAST(i.factory_code AS"
                    + " CHAR),''),COALESCE(CAST(i.pi AS CHAR),''),COALESCE(CAST(i.customer AS"
                    + " CHAR),''),COALESCE(CAST(i.material AS CHAR),''),COALESCE(CAST(i.bill_no AS"
                    + " CHAR),''),COALESCE(CAST(i.start_sn AS CHAR),''),COALESCE(CAST(i.end_sn AS"
                    + " CHAR),''),COALESCE(CAST(i.quantity AS CHAR),''),"
                    + "COALESCE(CAST(i.before_status"
                    + " AS CHAR),''),COALESCE(CAST(i.after_status AS CHAR),''),"
                    + "COALESCE(CAST(i.batch_id"
                    + " AS CHAR),''),COALESCE(CAST(i.operation_id AS"
                    + " CHAR),''),COALESCE(CAST(i.transfer_id AS CHAR),''),"
                    + "COALESCE(CAST(i.reason AS"
                    + " CHAR),''),COALESCE(CAST(i.error_code AS CHAR),''),"
                    + "COALESCE(CAST(i.trace_id AS"
                    + " CHAR),''),COALESCE(CAST(i.detail AS CHAR),''),"
                    + "COALESCE(CAST(i.factory_name AS"
                    + " CHAR),''),COALESCE(CAST(i.to_factory_code AS CHAR),''),"
                    + "COALESCE(CAST(i.to_pi AS"
                    + " CHAR),''),COALESCE(CAST(i.id AS CHAR),'')) FROM "
                    + SOURCE
                    + " WHERE "
                    + FILTER
                    + SCOPE
                    + EXPORT_SCOPE
                    + " ORDER BY i.created_at DESC,i.id DESC LIMIT #{limit}</script>")
    int snapshot(
            @Param("s") SnQueryMapper.Scope scope,
            @Param("q") SnAuditQuery query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("keyword") String keyword,
            @Param("e") SnQueryMapper.Scope exporting,
            @Param("task") String task,
            @Param("limit") int limit);

    @Select("SELECT * FROM sn_audit_export WHERE task_id=#{id}")
    SnAuditExportEntity exportById(@Param("id") String id);

    @Select(
            "SELECT task_id,ordinal,factory_id,'' pi,cells FROM sn_audit_export_row WHERE"
                    + " task_id=#{id} AND ordinal>#{after} ORDER BY ordinal LIMIT #{size}")
    List<SnExportRowEntity> exportRows(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    @Select(
            "<script>SELECT COUNT(*) FROM sn_audit_export_row i WHERE i.task_id=#{id}"
                    + SCOPE
                    + "</script>")
    long exportVisible(@Param("s") SnQueryMapper.Scope scope, @Param("id") String id);

    @Select(
            "SELECT * FROM sn_audit_export WHERE actor_id=#{actor} ORDER BY"
                    + " created_at DESC,task_id"
                    + " DESC LIMIT #{offset},#{size}")
    List<SnAuditExportEntity> history(
            @Param("actor") long actor, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM sn_audit_export WHERE actor_id=#{actor}")
    long historyCount(@Param("actor") long actor);
}
