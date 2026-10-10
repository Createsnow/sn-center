package com.adtek.sn.mapper;

import com.adtek.sn.domain.*;
import com.adtek.sn.query.AcquireQuery;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.*;

/** 参数化范围来自可信Actor，固定集合来自已授权头；只读跨厂例外绝不复用于命令或文件。 */
@Mapper
public interface AcquireMapper {
    @Insert(
            "<script>INSERT INTO"
                    + " sn_acquire_member(batch_id,ordinal,gen_month,item_id,sn,seq_pi,seq_dec,"
                    + "snapshot)"
                    + " VALUES <foreach collection='rows' item='r'"
                    + " separator=','>(#{r.batchId},#{r.ordinal},#{r.genMonth},#{r.itemId},"
                    + "#{r.sn},#{r.seqPi},#{r.seqDec},#{r.snapshot})</foreach></script>")
    int memberBatch(@Param("rows") List<AcquireMemberEntity> rows);

    @Insert(
            "<script>INSERT INTO sn_acquire_result(task_id,ordinal,sn,outcome) VALUES <foreach"
                    + " collection='rows' item='r'"
                    + " separator=','>(#{r.taskId},#{r.ordinal},#{r.sn},#{r.outcome})</foreach>"
                    + "</script>")
    int resultBatch(@Param("rows") List<AcquireResultEntity> rows);

    @Update(
            "<script>UPDATE sn_item SET"
                    + " status='TO_PRINT',current_batch_id=#{batch},current_print_id=NULL,"
                    + "acquired_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE status='TO_ACQUIRE' AND (gen_month,id) IN <foreach collection='rows'"
                    + " item='r' open='(' separator=','"
                    + " close=')'>(#{r.genMonth},#{r.id})</foreach></script>")
    int acquireBatch(@Param("rows") List<SnCurrentEntity> rows, @Param("batch") String batch);

    @Update(
            "<script>UPDATE sn_item SET"
                    + " status='PRINTED',current_print_id=#{print},"
                    + "printed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE status='TO_PRINT' AND (gen_month,id,current_batch_id) IN <foreach"
                    + " collection='rows' item='r' open='(' separator=','"
                    + " close=')'>(#{r.genMonth},#{r.id},#{r.currentBatchId})</foreach></script>")
    int printedBatch(@Param("rows") List<SnCurrentEntity> rows, @Param("print") String print);

    @Insert(
            "<script>INSERT INTO"
                    + " sn_print_member(print_id,ordinal,gen_month,item_id,batch_id,"
                    + "snapshot,batch_no) VALUES"
                    + " <foreach collection='rows' item='r'"
                    + " separator=','>(#{r.printId},#{r.ordinal},#{r.genMonth},#{r.itemId},"
                    + "#{r.batchId},#{r.snapshot},#{r.batchNo})</foreach></script>")
    int printMemberBatch(@Param("rows") List<PrintMemberEntity> rows);

    record Scope(boolean all, List<Long> factories, Long factoryId, String pi, boolean piAll) {}

    String SCOPE =
            "<if test='s.factoryId!=null'> AND i.factory_id=#{s.factoryId}</if><if"
                    + " test='s.pi!=null'> AND i.pi=#{s.pi}</if> AND i.factory_id IS NOT NULL <if"
                    + " test='!s.all and !s.piAll'> AND <choose><when"
                    + " test='!s.factories.isEmpty()'>i.factory_id IN <foreach"
                    + " collection='s.factories'"
                    + " item='f' open='(' separator=','"
                    + " close=')'>#{f}</foreach></when><otherwise>1=0</otherwise></choose></if>";
    String FILTER =
            "<if test='q.materialMode==\"EMPTY\"'> AND i.material=''</if><if"
                    + " test='q.materialMode==\"VALUE\"'> AND"
                    + " i.material=#{q.material}</if><choose><when test='q.status!=null and"
                    + " q.status!=\"\"'> AND i.status=#{q.status}</when><otherwise>"
                    + " AND i.status IN"
                    + " ('TO_ACQUIRE','TO_PRINT','APPLYING')</otherwise></choose>";
    String CURRENT =
            "i.gen_month,i.id,i.sn,i.pi,i.seq_pi,i.seq_dec,i.customer,i.material,"
                    + "i.factory_id,i.status,i.rule_version_id,i.source_system,"
                    + "i.source_account,i.source_bill_id,i.source_entry_id,i.bill_no,"
                    + "i.source_pi,i.source_org_id,i.current_batch_id,i.current_print_id,";
    String QUALIFICATION =
            " CASE WHEN x.id IS NOT NULL THEN 'ELIGIBLE' WHEN i.source_system IS NULL OR"
                    + " i.source_entry_id IS NULL THEN 'SOURCE_UNKNOWN' WHEN b.id IS NULL"
                    + " OR e.id IS"
                    + " NULL THEN 'SOURCE_MISSING' WHEN b.active=FALSE OR e.active=FALSE THEN"
                    + " 'SOURCE_INACTIVE' WHEN i.pi&lt;&gt;b.pi OR i.pi&lt;&gt;i.source_pi THEN"
                    + " 'PI_CHANGED' WHEN i.factory_id&lt;&gt;b.factory_id OR"
                    + " i.source_org_id&lt;&gt;b.org_id OR b.factory_id IS NULL THEN"
                    + " 'ORG_CHANGED' ELSE"
                    + " 'ELIGIBLE' END AS eligibility ";
    String SOURCE_JOINS =
            " LEFT JOIN sn_order_bill b ON b.source_system=i.source_system AND"
                    + " b.source_account=i.source_account AND"
                    + " b.source_bill_id=i.source_bill_id LEFT"
                    + " JOIN sn_order_entry e ON e.bill_id=b.id AND"
                    + " e.source_entry_id=i.source_entry_id"
                    + " LEFT JOIN sn_acquire_exception x ON x.id=i.acquire_exception_id AND"
                    + " x.gen_month=i.gen_month AND x.item_id=i.id AND"
                    + " x.factory_id=i.factory_id AND"
                    + " x.pi=i.pi ";
    String ORDER = " ORDER BY i.gen_month,i.id ";

    @Select(
            "<script>SELECT COUNT(*) FROM sn_item i WHERE i.pi=#{s.pi} AND"
                    + " i.factory_id IS NOT NULL"
                    + " "
                    + SCOPE
                    + "</script>")
    long owns(@Param("s") Scope scope);

    String SUMMARY =
            "SELECT i.factory_id,f.code AS factory_code,f.name AS factory_name,i.pi,"
                    + "MAX(i.customer)"
                    + " AS customer,SUM(i.status='TO_ACQUIRE') AS to_acquire,"
                    + "SUM(i.status='TO_PRINT')"
                    + " AS to_print,SUM(i.status='APPLYING') AS applying FROM sn_item i JOIN"
                    + " sn_factory"
                    + " f ON f.id=i.factory_id WHERE i.status IN ('TO_ACQUIRE','TO_PRINT',"
                    + "'APPLYING') "
                    + SCOPE
                    + " GROUP BY i.factory_id,f.code,f.name,i.pi";

    @Select(
            "<script>"
                    + SUMMARY
                    + " ORDER BY i.factory_id,i.pi LIMIT #{size} OFFSET #{offset}</script>")
    List<AcquireSummaryEntity> summaries(
            @Param("s") Scope scope, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM (" + SUMMARY + ") counted</script>")
    long summaryCount(@Param("s") Scope scope);

    // NULL流水使用唯一ID独立分组；已知流水以精确DECIMAL减ROW_NUMBER，不发生unsigned减法溢出。
    String SEGMENTS =
            "SELECT z.factory_id,z.factory_code,z.pi,z.material,z.status,z.seq_pi,"
                    + "MIN(z.seq_dec) AS"
                    + " start_seq,MAX(z.seq_dec) AS"
                    + " end_seq,SUBSTRING(MIN(CONCAT(LPAD(COALESCE(z.seq_dec,0),20,'0'),"
                    + "z.sn)),21) AS"
                    + " start_sn,SUBSTRING(MAX(CONCAT(LPAD(COALESCE(z.seq_dec,0),20,'0'),"
                    + "z.sn)),21) AS"
                    + " end_sn,COUNT(*) AS quantity,z.grp AS group_id FROM (SELECT i.*,f.code AS"
                    + " factory_code,CASE WHEN i.seq_dec IS NULL THEN i.id ELSE"
                    + " CAST(CAST(i.seq_dec AS"
                    + " DECIMAL(21,0))-ROW_NUMBER() OVER(PARTITION BY"
                    + " i.factory_id,i.pi,i.material,i.status,i.seq_pi,(i.seq_dec IS NULL) ORDER BY"
                    + " i.seq_dec,i.id) AS CHAR) END AS grp FROM sn_item i JOIN sn_factory f ON"
                    + " f.id=i.factory_id WHERE 1=1 "
                    + SCOPE
                    + FILTER
                    + ") z GROUP BY"
                    + " z.factory_id,z.factory_code,z.pi,z.material,z.status,z.seq_pi,z.grp";

    @Select(
            "<script>"
                    + SEGMENTS
                    + " ORDER BY factory_id,pi,material,status,seq_pi,start_seq,group_id LIMIT"
                    + " #{size} OFFSET #{offset}</script>")
    List<AcquireSegmentEntity> segments(
            @Param("s") Scope scope,
            @Param("q") AcquireQuery query,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM (" + SEGMENTS + ") counted</script>")
    long segmentCount(@Param("s") Scope scope, @Param("q") AcquireQuery query);

    @Select(
            "SELECT COUNT(*) FROM sn_item WHERE factory_id=#{factory} AND pi=#{pi} AND"
                    + " status=#{status}")
    long count(
            @Param("factory") long factory, @Param("pi") String pi, @Param("status") String status);

    @Select(
            "<script>SELECT "
                    + CURRENT
                    + QUALIFICATION
                    + " FROM sn_item i "
                    + SOURCE_JOINS
                    + " WHERE i.factory_id=#{factory} AND i.pi=#{pi} AND i.status=#{status}<if"
                    + " test='month!=null'> AND (i.gen_month &gt; #{month} OR"
                    + " (i.gen_month=#{month}"
                    + " AND i.id &gt; #{after}))</if>"
                    + ORDER
                    + "LIMIT #{size}</script>")
    List<SnCurrentEntity> candidates(
            @Param("factory") long factory,
            @Param("pi") String pi,
            @Param("status") String status,
            @Param("month") LocalDate month,
            @Param("after") String after,
            @Param("size") int size);

    /** 同事务写入的自增护栏顺序对应原型创建 ID；游标不受打印状态更新影响。 */
    @Select(
            "<script>SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_item i "
                    + "JOIN sn_key k ON k.id=i.id AND k.gen_month=i.gen_month "
                    + "WHERE i.factory_id=#{factory} AND i.pi=#{pi} AND i.status='TO_PRINT' "
                    + "<if test='after!=null'>AND k.creation_order &gt; "
                    + "(SELECT creation_order FROM sn_key WHERE id=#{after}) </if>"
                    + "ORDER BY k.creation_order LIMIT #{size}</script>")
    List<SnCurrentEntity> printCandidates(
            @Param("factory") long factory,
            @Param("pi") String pi,
            @Param("after") String after,
            @Param("size") int size);

    @Select(
            "SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_item i WHERE i.gen_month=#{month} AND"
                    + " i.id=#{id}")
    SnCurrentEntity current(@Param("month") LocalDate month, @Param("id") String id);

    @Select(
            "<script>SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_acquire_member m JOIN sn_item i ON"
                    + " i.gen_month=m.gen_month AND i.id=m.item_id WHERE m.batch_id=#{batch} AND"
                    + " m.sn IN <foreach collection='sns' item='sn' open='(' separator=','"
                    + " close=')'>#{sn}</foreach>"
                    + ORDER
                    + "</script>")
    List<SnCurrentEntity> currentFor(@Param("batch") String batch, @Param("sns") List<String> sns);

    @Update(
            "UPDATE sn_item SET"
                    + " status='TO_PRINT',current_batch_id=#{batch},current_print_id=NULL,"
                    + "acquired_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE gen_month=#{row.genMonth} AND id=#{row.id} AND"
                    + " factory_id=#{row.factoryId} AND pi=#{row.pi} AND status='TO_ACQUIRE'")
    int acquire(@Param("row") SnCurrentEntity row, @Param("batch") String batch);

    @Update(
            "UPDATE sn_item SET"
                    + " status='PRINTED',current_print_id=#{print},"
                    + "printed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE gen_month=#{row.genMonth} AND id=#{row.id} AND"
                    + " factory_id=#{row.factoryId} AND pi=#{row.pi} AND status='TO_PRINT' AND"
                    + " current_batch_id=#{row.currentBatchId}")
    int printed(@Param("row") SnCurrentEntity row, @Param("print") String print);

    String WORK =
            "SELECT task_id,kind,factory_id,pi,actor_id,origin,operation_id,CAST(payload AS CHAR)"
                    + " AS payload,state,quantity,eligible,blocked,updated_qty,already_qty,"
                    + "batch_id,error_code,created_at,finished_at"
                    + " FROM sn_acquire_work ";

    @Insert(
            "INSERT INTO"
                    + " sn_acquire_work(task_id,kind,factory_id,pi,actor_id,origin,"
                    + "operation_id,payload)"
                    + " VALUES(#{taskId},#{kind},#{factoryId},#{pi},#{actorId},#{origin},"
                    + "#{operationId},#{payload})")
    int work(AcquireWorkEntity work);

    @Select(WORK + "WHERE task_id=#{id}")
    AcquireWorkEntity workById(String id);

    @Update(
            "UPDATE sn_acquire_work SET"
                    + " state='SUCCESS',quantity=#{quantity},eligible=#{eligible},"
                    + "blocked=#{blocked},updated_qty=#{updated},already_qty=#{already},"
                    + "batch_id=#{batch},finished_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE task_id=#{id} AND state='PENDING'")
    int complete(
            @Param("id") String id,
            @Param("quantity") long quantity,
            @Param("eligible") long eligible,
            @Param("blocked") long blocked,
            @Param("updated") long updated,
            @Param("already") long already,
            @Param("batch") String batch);

    @Update(
            "UPDATE sn_acquire_work SET"
                    + " state=#{state},error_code=#{code},finished_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " task_id=#{id} AND state='PENDING'")
    int ended(@Param("id") String id, @Param("state") String state, @Param("code") Integer code);

    @Insert(
            "INSERT INTO sn_acquire_result(task_id,ordinal,sn,outcome)"
                    + " VALUES(#{taskId},#{ordinal},#{sn},#{outcome})")
    int result(AcquireResultEntity result);

    @Select(
            "SELECT task_id,ordinal,sn,outcome FROM sn_acquire_result WHERE task_id=#{id} AND"
                    + " ordinal>#{after} ORDER BY ordinal LIMIT #{size}")
    List<AcquireResultEntity> results(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    String BATCH =
            "SELECT"
                    + " i.id,i.factory_id,i.factory_code,i.factory_name,i.pi,i.quantity,"
                    + "i.actor_id,i.origin,i.operation_id,i.created_at,i.batch_no,i.actor_name"
                    + " FROM sn_acquire_batch i ";

    @Insert(
            "INSERT INTO"
                    + " sn_acquire_batch(id,factory_id,factory_code,factory_name,pi,quantity,"
                    + "actor_id,origin,operation_id,batch_no,actor_name)"
                    + " VALUES(#{id},#{factoryId},#{factoryCode},#{factoryName},#{pi},"
                    + "#{quantity},#{actorId},#{origin},#{operationId},#{batchNo},#{actorName})")
    int batch(AcquireBatchEntity batch);

    @Select(BATCH + "WHERE i.id=#{id}")
    AcquireBatchEntity batchById(String id);

    /** 当前指针决定页面成员；原始成员表继续保存回调及审计事实。 */
    @Select(
            "SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_item i "
                    + "JOIN sn_key k ON k.id=i.id AND k.gen_month=i.gen_month "
                    + "WHERE i.current_batch_id=#{batch} "
                    + "ORDER BY i.seq_pi,i.seq_dec,k.creation_order LIMIT #{size} OFFSET #{offset}")
    List<SnCurrentEntity> currentBatchItems(
            @Param("batch") String batch, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM sn_item WHERE current_batch_id=#{batch}")
    long currentBatchCount(@Param("batch") String batch);

    /** 页面重新下载按当前打印指针读取，创建顺序不随转厂或重领改变。 */
    @Select(
            "<script>SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_item i "
                    + "JOIN sn_key k ON k.id=i.id AND k.gen_month=i.gen_month "
                    + "WHERE i.current_print_id=#{print} "
                    + "<if test='after!=null'>AND k.creation_order &gt; "
                    + "(SELECT creation_order FROM sn_key WHERE id=#{after}) </if>"
                    + "ORDER BY k.creation_order LIMIT #{size}</script>")
    List<SnCurrentEntity> currentPrintItems(
            @Param("print") String print, @Param("after") String after, @Param("size") int size);

    @Select(
            "<script>"
                    + BATCH
                    + "WHERE 1=1 "
                    + SCOPE
                    + " ORDER BY i.created_at DESC,i.id DESC LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<AcquireBatchEntity> batches(
            @Param("s") Scope scope, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM sn_acquire_batch i WHERE 1=1 " + SCOPE + "</script>")
    long batchCount(@Param("s") Scope scope);

    @Insert(
            "INSERT INTO"
                    + " sn_acquire_member(batch_id,ordinal,gen_month,item_id,sn,seq_pi,seq_dec,"
                    + "snapshot)"
                    + " VALUES(#{batchId},#{ordinal},#{genMonth},#{itemId},#{sn},#{seqPi},"
                    + "#{seqDec},#{snapshot})")
    int member(AcquireMemberEntity member);

    String MEMBER =
            "SELECT batch_id,ordinal,gen_month,item_id,sn,seq_pi,seq_dec,"
                    + "CAST(snapshot AS CHAR) AS"
                    + " snapshot FROM sn_acquire_member ";

    @Select(
            "<script>"
                    + MEMBER
                    + "WHERE (batch_id,gen_month,item_id) IN <foreach collection='rows' item='r'"
                    + " open='(' separator=','"
                    + " close=')'>(#{r.currentBatchId},#{r.genMonth},#{r.id})</foreach></script>")
    List<AcquireMemberEntity> membersForRows(@Param("rows") List<SnCurrentEntity> rows);

    @Select(MEMBER + "WHERE batch_id=#{id} AND ordinal>#{after} ORDER BY ordinal LIMIT #{size}")
    List<AcquireMemberEntity> members(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    @Select(MEMBER + "WHERE batch_id=#{batch} AND sn=#{sn}")
    AcquireMemberEntity memberBySn(@Param("batch") String batch, @Param("sn") String sn);

    @Select(
            "<script>"
                    + MEMBER
                    + "WHERE batch_id=#{batch} AND sn IN <foreach collection='sns' item='sn'"
                    + " open='(' separator=',' close=')'>#{sn}</foreach>"
                    + " ORDER BY ordinal</script>")
    List<AcquireMemberEntity> membersBySn(
            @Param("batch") String batch, @Param("sns") List<String> sns);

    @Select(
            MEMBER
                    + "WHERE batch_id=#{batch} AND seq_pi=#{pi} AND seq_dec BETWEEN #{lo} AND"
                    + " #{hi}"
                    + " ORDER BY seq_dec LIMIT #{size}")
    List<AcquireMemberEntity> range(
            @Param("batch") String batch,
            @Param("pi") String pi,
            @Param("lo") long lo,
            @Param("hi") long hi,
            @Param("size") int size);

    String PRINT =
            "SELECT"
                    + " i.id,i.factory_id,i.factory_code,i.factory_name,i.pi,i.quantity,"
                    + "i.actor_id,i.operation_id,i.csv_task_id,i.created_at,"
                    + "i.print_no,i.actor_name,i.format_version"
                    + " FROM sn_print i ";

    @Insert(
            "INSERT INTO"
                    + " sn_print(id,factory_id,factory_code,factory_name,pi,quantity,actor_id,"
                    + "operation_id,csv_task_id,print_no,actor_name,format_version)"
                    + " VALUES(#{id},#{factoryId},#{factoryCode},#{factoryName},#{pi},"
                    + "#{quantity},#{actorId},#{operationId},#{csvTaskId},#{printNo},"
                    + "#{actorName},#{formatVersion})")
    int print(PrintEntity print);

    @Select(PRINT + "WHERE i.id=#{id} OR i.csv_task_id=#{id}")
    PrintEntity printByTask(String id);

    @Select(
            "<script>"
                    + PRINT
                    + "WHERE 1=1 "
                    + SCOPE
                    + " ORDER BY i.created_at DESC,i.id DESC LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<PrintEntity> prints(
            @Param("s") Scope scope, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM sn_print i WHERE 1=1 " + SCOPE + "</script>")
    long printCount(@Param("s") Scope scope);

    @Insert(
            "INSERT INTO sn_print_member(print_id,ordinal,gen_month,item_id,batch_id,"
                    + "snapshot,batch_no)"
                    + " VALUES(#{printId},#{ordinal},#{genMonth},#{itemId},#{batchId},"
                    + "#{snapshot},#{batchNo})")
    int printMember(PrintMemberEntity member);

    @Select(
            "SELECT print_id,ordinal,gen_month,item_id,batch_id,"
                    + "CAST(snapshot AS CHAR) AS snapshot,batch_no"
                    + " FROM sn_print_member WHERE print_id=#{id} AND ordinal>"
                    + "#{after} ORDER BY ordinal"
                    + " LIMIT #{size}")
    List<PrintMemberEntity> printMembers(
            @Param("id") String id, @Param("after") long after, @Param("size") int size);

    @Select(
            "<script>SELECT "
                    + CURRENT
                    + "'NOT_CHECKED' AS eligibility FROM sn_item i WHERE 1=1 "
                    + SCOPE
                    + "<if test='customer!=null'> AND i.customer=#{customer}</if><if"
                    + " test='mode==\"EMPTY\"'> AND i.material=''</if><if test='mode==\"VALUE\"'>"
                    + " AND i.material=#{material}</if><if test='month!=null'> AND (i.gen_month"
                    + " &gt; #{month} OR (i.gen_month=#{month} AND i.id &gt; #{after}))</if>"
                    + ORDER
                    + " LIMIT #{size}</script>")
    List<SnCurrentEntity> items(
            @Param("s") Scope scope,
            @Param("customer") String customer,
            @Param("mode") String mode,
            @Param("material") String material,
            @Param("month") LocalDate month,
            @Param("after") String after,
            @Param("size") int size);
}
