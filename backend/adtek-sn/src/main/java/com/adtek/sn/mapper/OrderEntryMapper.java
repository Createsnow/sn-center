package com.adtek.sn.mapper;
import com.adtek.sn.domain.OrderEntryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
/** 分录读取按factory_id注入范围；写入只接受完整拉取校验后的服务器快照。 */
@Mapper
public interface OrderEntryMapper extends BaseMapper<OrderEntryEntity> {
 @Select("SELECT * FROM sn_order_entry WHERE bill_id=#{billId} AND source_entry_id=#{entryId}") OrderEntryEntity source(@Param("billId") long billId,@Param("entryId") String entryId);
 @Update("UPDATE sn_order_entry SET active=FALSE,snapshot_version=#{version} WHERE bill_id=#{billId}") int deactivate(@Param("billId") long billId,@Param("version") long version);
 @Update("<script>UPDATE sn_order_entry e JOIN sn_order_bill b ON b.id=e.bill_id SET e.active=FALSE,e.snapshot_version=#{version} WHERE b.source_system=#{system} AND b.source_account=#{account}<if test='billNo!=null'> AND b.bill_no=#{billNo}</if></script>")
 int deactivateSource(@Param("system") String system,@Param("account") String account,@Param("billNo") String billNo,@Param("version") long version);
}
