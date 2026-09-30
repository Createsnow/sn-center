package com.adtek.sn.mapper;

import com.adtek.sn.domain.*;
import com.adtek.sn.service.support.TransferInput;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.*;

/** 范围均由当前Actor构造；固定成员须先授权转厂头，写入只在双PI占用/安全/快照锁内。 */
@Mapper
public interface TransferMapper {
    record Scope(boolean all, List<Long> factories, Long factoryId) {}

    String SOURCE =
            " i.factory_id=#{c.factoryId} AND i.pi=#{c.pi}<if"
                    + " test='c.scope==&quot;MATERIAL&quot;'>"
                    + " AND i.material=#{c.material}</if><if"
                    + " test='c.scope==&quot;SINGLE&quot;'> AND"
                    + " i.sn=#{c.sn}</if> ";
    String ELIGIBLE =
            " AND i.status IN ('TO_ACQUIRE','TO_PRINT',"
                    + "'PRINTED') AND i.current_transfer_id IS NULL"
                    + " ";
    String TUPLES =
            "<foreach collection='rows' item='r' open='(' separator=','"
                    + " close=')'>(#{r.genMonth},#{r.id})</foreach>";

    @Select(
            "<script>SELECT COUNT(*) total,COALESCE(SUM(i.status IN"
                    + " ('TO_ACQUIRE','TO_PRINT','PRINTED') AND i.current_transfer_id IS NULL),0)"
                    + " transferable,COALESCE(SUM(i.status='TO_ACQUIRE'),0)"
                    + " to_acquire,COALESCE(SUM(i.status='TO_PRINT'),0)"
                    + " to_print,COALESCE(SUM(i.status='PRINTED'),0)"
                    + " printed,COALESCE(SUM(i.status='APPLYING'),0)"
                    + " applying,COALESCE(SUM(i.status='PENDING_ALLOC'),"
                    + "0) pending_allocation FROM"
                    + " sn_item i WHERE "
                    + SOURCE
                    + "</script>")
    TransferCandidateEntity candidates(@Param("c") TransferInput input);

    @Select(
            "<script>SELECT "
                    + AcquireMapper.CURRENT
                    + " NULL eligibility FROM sn_item i WHERE "
                    + SOURCE
                    + ELIGIBLE
                    + "<if test='month!=null'> AND (i.gen_month &gt; #{month} OR"
                    + " (i.gen_month=#{month} AND i.id &gt; #{after}))</if> ORDER BY"
                    + " i.gen_month,i.id LIMIT #{size}</script>")
    List<SnCurrentEntity> candidateItems(
            @Param("c") TransferInput input,
            @Param("month") LocalDate month,
            @Param("after") String after,
            @Param("size") int size);

    @Select("SELECT * FROM sn_transfer WHERE id=#{id}")
    TransferEntity get(String id);

    @Insert(
            "INSERT INTO"
                    + " sn_transfer(id,mode,status,from_factory_id,from_factory_code,"
                    + "from_factory_name,from_pi,scope,material,sn,to_factory_id,"
                    + "to_factory_code,to_factory_name,to_pi,to_customer,to_material,"
                    + "target_source,reason,quantity,actor_id,decided_by,decided_at)"
                    + " VALUES(#{id},#{mode},#{status},#{fromFactoryId},#{fromFactoryCode},"
                    + "#{fromFactoryName},#{fromPi},#{scope},#{material},#{sn},#{toFactoryId},"
                    + "#{toFactoryCode},#{toFactoryName},#{toPi},#{toCustomer},#{toMaterial},"
                    + "#{targetSource},#{reason},#{quantity},#{actorId},#{decidedBy},"
                    + "#{decidedAt})")
    int insert(TransferEntity row);

    @Insert(
            "<script>INSERT INTO"
                    + " sn_transfer_member(transfer_id,ordinal,gen_month,item_id,from_status,"
                    + "from_batch_id,from_print_id,snapshot)"
                    + " VALUES <foreach collection='rows' item='r'"
                    + " separator=','>(#{r.transferId},#{r.ordinal},#{r.genMonth},#{r.itemId},"
                    + "#{r.fromStatus},#{r.fromBatchId},#{r.fromPrintId},"
                    + "#{r.snapshot})</foreach></script>")
    int members(@Param("rows") List<TransferMemberEntity> rows);

    @Update(
            "<script>UPDATE sn_item SET"
                    + " status='APPLYING',current_transfer_id=#{transfer},"
                    + "updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE status IN ('TO_ACQUIRE','TO_PRINT',"
                    + "'PRINTED') AND current_transfer_id IS"
                    + " NULL AND (gen_month,id) IN "
                    + TUPLES
                    + "</script>")
    int freeze(@Param("transfer") String transfer, @Param("rows") List<SnCurrentEntity> rows);

    @Select(
            "<script>SELECT COUNT(*) FROM sn_key target JOIN sn_item i ON i.sn=target.sn WHERE"
                    + " target.pi=#{pi} AND target.id&lt;&gt;i.id AND (i.gen_month,i.id) IN "
                    + TUPLES
                    + "</script>")
    long collisions(@Param("pi") String pi, @Param("rows") List<SnCurrentEntity> rows);

    @Update("<script>UPDATE sn_key SET pi=#{pi} WHERE (gen_month,id) IN " + TUPLES + "</script>")
    int moveKeys(@Param("pi") String pi, @Param("rows") List<SnCurrentEntity> rows);

    @Insert(
            "<script>INSERT INTO"
                    + " sn_acquire_exception(id,gen_month,item_id,factory_id,pi,transfer_id) VALUES"
                    + " <foreach collection='rows' item='r'"
                    + " separator=','>(#{transfer},#{r.genMonth},#{r.id},#{c.toFactoryId},"
                    + "#{c.toPi},#{transfer})</foreach></script>")
    int exceptions(
            @Param("transfer") String transfer,
            @Param("c") TransferInput input,
            @Param("rows") List<SnCurrentEntity> rows);

    @Update(
            "<script>UPDATE sn_item SET"
                    + " factory_id=#{c.toFactoryId},pi=#{c.toPi},"
                    + "customer=IF(#{c.toCustomer}='',customer,#{c.toCustomer}),"
                    + "material=IF(#{c.toMaterial}='',material,#{c.toMaterial}),"
                    + "status='TO_ACQUIRE',current_batch_id=NULL,current_print_id=NULL,"
                    + "current_transfer_id=NULL,acquired_at=NULL,printed_at=NULL,"
                    + "acquire_exception_id=#{transfer},updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE (gen_month,id) IN "
                    + TUPLES
                    + " AND <choose><when test='pending'>status='APPLYING' AND"
                    + " current_transfer_id=#{transfer}</when><otherwise>status IN"
                    + " ('TO_ACQUIRE','TO_PRINT','PRINTED') AND current_transfer_id IS"
                    + " NULL</otherwise></choose></script>")
    int move(
            @Param("transfer") String transfer,
            @Param("c") TransferInput input,
            @Param("rows") List<SnCurrentEntity> rows,
            @Param("pending") boolean pending);

    @Select(
            "SELECT * FROM sn_transfer_member WHERE transfer_id=#{id} AND ordinal>"
                    + "#{after} ORDER BY"
                    + " ordinal LIMIT #{size}")
    List<TransferMemberEntity> page(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    @Select(
            "<script>SELECT "
                    + AcquireMapper.CURRENT
                    + " NULL eligibility FROM sn_item i JOIN sn_transfer_member m ON"
                    + " m.gen_month=i.gen_month AND m.item_id=i.id WHERE m.transfer_id=#{id} AND"
                    + " m.ordinal&gt;#{after} AND m.ordinal&lt;=#{end} ORDER BY"
                    + " m.ordinal</script>")
    List<SnCurrentEntity> current(
            @Param("id") String id, @Param("after") long after, @Param("end") long end);

    @Select("SELECT COUNT(*) FROM sn_item WHERE current_transfer_id=#{id} AND status='APPLYING'")
    long frozenCount(String id);

    @Update(
            "UPDATE sn_item i JOIN sn_transfer_member m ON m.gen_month=i.gen_month AND"
                    + " m.item_id=i.id SET"
                    + " i.status=m.from_status,i.current_transfer_id=NULL,"
                    + "i.updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE m.transfer_id=#{id} AND i.current_transfer_id=#{id} AND"
                    + " i.status='APPLYING' AND i.factory_id=#{factory} AND i.pi=#{pi} AND"
                    + " (i.current_batch_id <=> m.from_batch_id) AND (i.current_print_id <=>"
                    + " m.from_print_id)")
    int restore(@Param("id") String id, @Param("factory") long factory, @Param("pi") String pi);

    @Update(
            "UPDATE sn_transfer SET"
                    + " status=#{status},version=version+1,decided_by=#{actor},note=#{note},"
                    + "decided_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=#{id} AND status='PENDING' AND version=#{version}")
    int decide(
            @Param("id") String id,
            @Param("version") long version,
            @Param("status") String status,
            @Param("actor") long actor,
            @Param("note") String note);

    @Insert(
            "INSERT INTO"
                    + " sn_transfer_work(task_id,kind,transfer_id,from_factory_id,from_pi,"
                    + "to_pi,payload)"
                    + " VALUES(#{taskId},#{kind},#{transferId},#{fromFactoryId},#{fromPi},"
                    + "#{toPi},#{payload})")
    int work(TransferWorkEntity row);

    @Select("SELECT * FROM sn_transfer_work WHERE task_id=#{id}")
    TransferWorkEntity workById(String id);

    @Update(
            "UPDATE sn_transfer_work SET"
                    + " state=#{state},error_code=#{code},finished_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " task_id=#{id} AND state='PENDING'")
    int ended(@Param("id") String id, @Param("state") String state, @Param("code") Integer code);

    String VISIBLE =
            "<if test='!s.all'> AND <choose><when test='!s.factories.isEmpty()'>"
                    + "(t.from_factory_id"
                    + " IN <foreach collection='s.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach> OR t.to_factory_id IN <foreach"
                    + " collection='s.factories' item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach>)</when><otherwise>1=0</otherwise></choose></if><if"
                    + " test='s.factoryId!=null'> AND (t.from_factory_id=#{s.factoryId} OR"
                    + " t.to_factory_id=#{s.factoryId})</if><if test='pi!=null'> AND"
                    + " (t.from_pi=#{pi}"
                    + " OR t.to_pi=#{pi})</if><if test='status!=null'> AND t.status=#{status}</if>";

    @Select(
            "<script>SELECT t.* FROM sn_transfer t WHERE 1=1 "
                    + VISIBLE
                    + " ORDER BY t.created_at DESC,t.id DESC LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<TransferEntity> list(
            @Param("s") Scope scope,
            @Param("pi") String pi,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM sn_transfer t WHERE 1=1 " + VISIBLE + "</script>")
    long count(@Param("s") Scope scope, @Param("pi") String pi, @Param("status") String status);

    String TARGET =
            "SELECT DISTINCT b.factory_id,f.code factory_code,f.name"
                    + " factory_name,b.pi,b.customer,e.material FROM sn_order_bill b JOIN"
                    + " sn_order_entry e ON e.bill_id=b.id JOIN sn_factory f ON"
                    + " f.id=b.factory_id WHERE"
                    + " b.active=TRUE AND e.active=TRUE AND f.enabled=TRUE AND b.pi&lt;&gt;'' <if"
                    + " test='q!=null'> AND (b.pi LIKE #{q} ESCAPE '!' OR b.customer LIKE"
                    + " #{q} ESCAPE"
                    + " '!' OR e.material LIKE #{q} ESCAPE '!')</if>";

    @Select(
            "<script>"
                    + TARGET
                    + " ORDER BY b.factory_id,b.pi,b.customer,e.material LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<TransferTargetEntity> targets(
            @Param("q") String q, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM (" + TARGET + ") targets</script>")
    long targetCount(@Param("q") String q);

    @Select(
            "SELECT COUNT(*) FROM sn_order_bill b JOIN sn_order_entry e ON e.bill_id=b.id WHERE"
                    + " b.active=TRUE AND e.active=TRUE AND b.factory_id=#{c.toFactoryId} AND"
                    + " b.pi=#{c.toPi} AND b.customer=#{c.toCustomer} AND"
                    + " e.material=#{c.toMaterial}")
    long snapshotTarget(@Param("c") TransferInput input);

    @Select(
            "<script>SELECT id factory_id,code factory_code,name factory_name,'' pi,"
                    + "'' customer,''"
                    + " material FROM sn_factory WHERE enabled=TRUE <if test='q!=null'>AND"
                    + " (code LIKE"
                    + " #{q} ESCAPE '!' OR name LIKE #{q} ESCAPE '!')</if> ORDER BY id"
                    + " LIMIT #{size}"
                    + " OFFSET #{offset}</script>")
    List<TransferTargetEntity> factories(
            @Param("q") String q, @Param("offset") long offset, @Param("size") int size);

    @Select(
            "<script>SELECT COUNT(*) FROM sn_factory WHERE enabled=TRUE <if test='q!=null'>AND"
                    + " (code LIKE #{q} ESCAPE '!' OR name LIKE #{q} ESCAPE '!')</if></script>")
    long factoryCount(@Param("q") String q);
}
