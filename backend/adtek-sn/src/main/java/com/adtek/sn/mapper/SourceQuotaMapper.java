package com.adtek.sn.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 历史累计不随快照更新删除；读取billIds必须来自已按工厂授权的订单查询，禁止请求直接传入。 */
@Mapper
public interface SourceQuotaMapper {
 @Insert("INSERT INTO sn_source_entry_quota(bill_id,source_entry_id) VALUES(#{billId},#{entryId}) ON DUPLICATE KEY UPDATE bill_id=bill_id")
 int ensure(@Param("billId") long billId,@Param("entryId") String entryId);
 @Select("<script>SELECT bill_id,source_entry_id,generated_qty,allocated_qty FROM sn_source_entry_quota WHERE bill_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY bill_id,source_entry_id</script>")
 List<Quota> forBills(@Param("ids") List<Long> ids);
 /** long为数据库精确累计；只在DTO/VO边界转为十进制字符串。 */
 record Quota(long billId,String sourceEntryId,long generatedQty,long allocatedQty) {}
}
