package com.adtek.sn.mapper;

import com.adtek.sn.domain.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 仅由已复核全部工厂的SN命令用例访问；所有写遵守控制→PI→额度→版本→成员顺序。 */
@Mapper
public interface SnWorkMapper {
    @Insert("INSERT INTO sn_generation_job(task_id) VALUES(#{taskId})")
    int generationNumber(String taskId);

    @Select("SELECT id FROM sn_generation_job WHERE task_id=#{taskId}")
    Long generationLabel(String taskId);

    @Select("SELECT * FROM sn_pi_counter WHERE pi=#{pi}")
    PiCounterEntity counter(String pi);

    @Insert("INSERT INTO sn_pi_counter(pi) VALUES(#{pi}) ON DUPLICATE KEY UPDATE pi=pi")
    int ensure(String pi);

    @Select("SELECT * FROM sn_pi_counter WHERE pi=#{pi} FOR UPDATE")
    PiCounterEntity lock(String pi);

    @Update(
            "UPDATE sn_pi_counter SET active_task_id=#{task} WHERE pi=#{pi} AND active_task_id IS"
                    + " NULL")
    int occupy(@Param("pi") String pi, @Param("task") String task);

    @Update(
            "UPDATE sn_pi_counter SET active_task_id=NULL WHERE pi=#{pi} AND"
                    + " active_task_id=#{task}")
    int release(@Param("pi") String pi, @Param("task") String task);

    @Update(
            "UPDATE sn_pi_counter SET"
                    + " last_seq=#{last},generated_qty=generated_qty+#{generated},"
                    + "imported_qty=imported_qty+#{imported},start_seq=COALESCE(start_seq,"
                    + "#{start}),start_locked=TRUE,locked_by=COALESCE(locked_by,#{actor}),"
                    + "locked_at=COALESCE(locked_at,CURRENT_TIMESTAMP(6))"
                    + " WHERE pi=#{pi}")
    int advance(
            @Param("pi") String pi,
            @Param("last") long last,
            @Param("generated") long generated,
            @Param("imported") long imported,
            @Param("start") Long start,
            @Param("actor") long actor);

    @Select("SELECT * FROM sn_order_bill WHERE id=#{id}")
    OrderBillEntity bill(long id);

    @Select("SELECT COUNT(*) FROM sn_order_bill WHERE pi=#{pi} AND active=TRUE")
    long inSnapshot(String pi);

    @Select(
            "SELECT * FROM sn_order_entry WHERE bill_id=#{bill} AND active=TRUE ORDER BY"
                    + " ordinal,id")
    List<OrderEntryEntity> lines(long bill);

    @Select(
            "SELECT * FROM sn_source_entry_quota WHERE bill_id=#{bill} ORDER BY"
                    + " source_entry_id FOR"
                    + " UPDATE")
    List<SourceQuotaMapper.Quota> lockQuota(long bill);

    @Update(
            "UPDATE sn_source_entry_quota SET"
                    + " generated_qty=generated_qty+#{generated},"
                    + "allocated_qty=allocated_qty+#{allocated}"
                    + " WHERE bill_id=#{bill} AND source_entry_id=#{entry}")
    int quota(
            @Param("bill") long bill,
            @Param("entry") String entry,
            @Param("generated") long generated,
            @Param("allocated") long allocated);

    @Insert(
            "INSERT INTO sn_preview(id,actor_id,pi,bill_id,payload,expires_at)"
                    + " VALUES(#{id},#{actorId},#{pi},#{billId},#{payload},#{expiresAt})")
    int preview(SnPreviewEntity preview);

    @Select("SELECT * FROM sn_preview WHERE id=#{id}")
    SnPreviewEntity previewById(String id);

    @Select("SELECT * FROM sn_preview WHERE task_id=#{id}")
    SnPreviewEntity previewForTask(String id);

    @Update("UPDATE sn_preview SET task_id=#{task} WHERE id=#{id} AND task_id IS NULL")
    int bind(@Param("id") String id, @Param("task") String task);

    @Insert(
            "INSERT INTO"
                    + " sn_work(task_id,kind,pi,bill_id,factory_id,actor_id,payload,quantity,"
                    + "file_name,actor_name)"
                    + " VALUES(#{taskId},#{kind},#{pi},#{billId},#{factoryId},#{actorId},"
                    + "#{payload},#{quantity},#{fileName},#{actorName})")
    int insert(SnWorkEntity work);

    @Select("SELECT * FROM sn_work WHERE task_id=#{task}")
    SnWorkEntity find(String task);

    @Select(
            "<script>SELECT * FROM sn_work WHERE pi=#{pi}<if test='bill!=null'> AND"
                    + " bill_id=#{bill}</if><if test='kind!=null'> AND kind=#{kind}</if> ORDER BY"
                    + " created_at DESC,task_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<SnWorkEntity> history(
            @Param("pi") String pi,
            @Param("bill") Long bill,
            @Param("kind") String kind,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM sn_work WHERE pi=#{pi} AND kind='PI_INIT'")
    long importCount(String pi);

    @Update(
            "UPDATE sn_work SET"
                    + " state='SUCCESS',start_seq=#{start},end_seq=#{end},"
                    + "decoded_qty=#{decoded},max_seq=#{max},last_seq=#{last},"
                    + "first_sn=#{first},last_sn=#{lastSn},finished_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE task_id=#{task} AND state='PENDING'")
    int success(
            @Param("task") String task,
            @Param("start") Long start,
            @Param("end") Long end,
            @Param("decoded") Long decoded,
            @Param("max") Long max,
            @Param("last") Long last,
            @Param("first") String first,
            @Param("lastSn") String lastSn);

    @Update(
            "UPDATE sn_work SET state=#{state},error_code=#{code},error_params=#{params},"
                    + "finished_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE task_id=#{task} AND state='PENDING'")
    int ended(
            @Param("task") String task,
            @Param("state") String state,
            @Param("code") Integer code,
            @Param("params") String params);

    @Select("SELECT COUNT(*) FROM sn_item WHERE bill_id=#{bill} AND status='PENDING_ALLOC'")
    long pendingCount(long bill);

    @Select("SELECT COUNT(*) FROM sn_work WHERE bill_id=#{bill} AND kind=#{kind}")
    long workCount(@Param("bill") long bill, @Param("kind") String kind);

    @Select(
            "SELECT * FROM sn_work WHERE bill_id=#{bill} AND kind=#{kind} ORDER BY created_at"
                    + " DESC,task_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<SnWorkEntity> billHistory(
            @Param("bill") long bill,
            @Param("kind") String kind,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 改 PI 后可能重新起号；相同流水按实际生成时间选旧成员，随机 UUID 只作末级稳定排序。 */
    @Select(
            "SELECT"
                    + " gen_month,id,sn,pi,seq_pi,seq_dec,customer,material,factory_id,source,"
                    + "status,rule_version_id,source_system,source_account,source_bill_id,"
                    + "source_entry_id,bill_id,bill_no,source_pi,source_org_id,"
                    + "source_factory_id,task_id"
                    + " FROM sn_item WHERE bill_id=#{bill} AND status='PENDING_ALLOC' ORDER BY"
                    + " seq_dec,created_at,id LIMIT #{quantity}")
    List<SnItemEntity> pendingBatch(@Param("bill") long bill, @Param("quantity") long quantity);

    @Insert(
            "<script>INSERT INTO sn_key(id,gen_month,pi,sn,seq_pi,seq_dec) VALUES <foreach"
                    + " collection='items' item='r'"
                    + " separator=','>(#{r.id},#{r.genMonth},#{r.pi},#{r.sn},#{r.seqPi},"
                    + "#{r.seqDec})</foreach></script>")
    int keys(@Param("items") List<SnItemEntity> items);

    /** 在初始化事务内逐块查重；只返回受控业务字段，唯一索引仍作最后防线。 */
    @Select(
            "<script>SELECT sn FROM sn_key WHERE pi=#{pi} AND sn IN"
                    + " <foreach collection='sns' item='sn' open='(' separator=',' close=')'>"
                    + "#{sn}</foreach> LIMIT 1</script>")
    String importedSnConflict(@Param("pi") String pi, @Param("sns") List<String> sns);

    @Select(
            "<script>SELECT seq_dec FROM sn_key WHERE seq_pi=#{pi} AND seq_dec IN"
                    + " <foreach collection='seqs' item='seq' open='(' separator=',' close=')'>"
                    + "#{seq}</foreach> LIMIT 1</script>")
    Long importedSequenceConflict(@Param("pi") String pi, @Param("seqs") List<Long> seqs);

    @Insert(
            "<script>INSERT INTO"
                    + " sn_item(gen_month,id,sn,pi,seq_pi,seq_dec,customer,material,factory_id,"
                    + "source,status,rule_version_id,source_system,source_account,"
                    + "source_bill_id,source_entry_id,bill_id,bill_no,source_pi,source_org_id,"
                    + "source_factory_id,task_id,printed_at)"
                    + " VALUES <foreach collection='items' item='r'"
                    + " separator=','>(#{r.genMonth},#{r.id},#{r.sn},#{r.pi},#{r.seqPi},"
                    + "#{r.seqDec},#{r.customer},#{r.material},#{r.factoryId},#{r.source},"
                    + "#{r.status},#{r.ruleVersionId},#{r.sourceSystem},#{r.sourceAccount},"
                    + "#{r.sourceBillId},#{r.sourceEntryId},#{r.billId},#{r.billNo},"
                    + "#{r.sourcePi},#{r.sourceOrgId},#{r.sourceFactoryId},#{r.taskId},CASE"
                    + " WHEN #{r.source}='IMPORT' THEN CURRENT_TIMESTAMP(6) ELSE NULL"
                    + " END)</foreach></script>")
    int items(@Param("items") List<SnItemEntity> items);

    @Update(
            "<script>UPDATE sn_item SET"
                    + " status='TO_ACQUIRE',factory_id=#{factory},allocation_id=#{task},"
                    + "updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE status='PENDING_ALLOC' AND (gen_month,"
                    + "id) IN <foreach collection='items'"
                    + " item='r' open='(' separator=','"
                    + " close=')'>(#{r.genMonth},#{r.id})</foreach></script>")
    int allocate(
            @Param("task") String task,
            @Param("factory") long factory,
            @Param("items") List<SnItemEntity> items);
}
