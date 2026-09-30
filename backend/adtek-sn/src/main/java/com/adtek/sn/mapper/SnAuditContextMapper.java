package com.adtek.sn.mapper;
import org.apache.ibatis.annotations.*;
/** 审计只读固定成员，绝不从当前SN投影重建历史或锁住外层正在回滚的对象。 */
@Mapper public interface SnAuditContextMapper {
 record Bounds(String customer,String material,String billNo,String startSn,String endSn,String beforeStatus) {}
 String BASE="IF(COUNT(DISTINCT snapshot->>'$.customer')=1,MIN(snapshot->>'$.customer'),NULL) customer,IF(COUNT(DISTINCT snapshot->>'$.material')=1,MIN(snapshot->>'$.material'),NULL) material,IF(COUNT(DISTINCT snapshot->>'$.billNo')=1,MIN(snapshot->>'$.billNo'),NULL) bill_no,MIN(snapshot->>'$.sn') start_sn,MAX(snapshot->>'$.sn') end_sn";
 @Select("SELECT "+BASE+",'TO_ACQUIRE' before_status FROM sn_acquire_member WHERE batch_id=#{id}") Bounds batch(@Param("id") String id);
 @Select("SELECT "+BASE+",'TO_PRINT' before_status FROM sn_print_member WHERE print_id=#{id}") Bounds print(@Param("id") String id);
 @Select("SELECT "+BASE+",GROUP_CONCAT(DISTINCT from_status ORDER BY from_status) before_status FROM sn_transfer_member WHERE transfer_id=#{id}") Bounds transfer(@Param("id") String id);
 /**
  * 分配事务已锁定并更新固定成员，仅在提交前取当时投影。
  * JSON_TABLE的UUID须与sn_item主键同为ascii_bin，否则索引只能使用月份，每个成员重复扫描同月SN。
  */
 @Select("SELECT IF(COUNT(DISTINCT i.customer)=1,MIN(i.customer),NULL) customer,IF(COUNT(DISTINCT i.material)=1,MIN(i.material),NULL) material,IF(COUNT(DISTINCT i.bill_no)=1,MIN(i.bill_no),NULL) bill_no,MIN(i.sn) start_sn,MAX(i.sn) end_sn,'PENDING_ALLOC' before_status FROM sn_work w JOIN JSON_TABLE(w.payload,'$.members[*]' COLUMNS(gen_month DATE PATH '$.genMonth',id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PATH '$.id')) m JOIN sn_item i ON i.gen_month=m.gen_month AND i.id=m.id WHERE w.task_id=#{id}") Bounds allocation(@Param("id") String id);
}
