package com.adtek.sn.mapper;
import com.adtek.sn.domain.OrganizationMappingEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 稳定来源命名空间的显式映射；增改必须持安全/快照发布锁，禁止用名称自动关联手工工厂。 */
@Mapper
public interface OrganizationMappingMapper {
 @Select("SELECT source_system,source_account,org_id,org_code,org_name,factory_id FROM sn_org_mapping WHERE source_system=#{system} AND source_account=#{account} ORDER BY org_id")
 List<OrganizationMappingEntity> list(@Param("system") String system,@Param("account") String account);
 @Insert("INSERT INTO sn_org_mapping(source_system,source_account,org_id,org_code,org_name,factory_id) VALUES(#{sourceSystem},#{sourceAccount},#{orgId},#{orgCode},#{orgName},#{factoryId})")
 int insert(OrganizationMappingEntity value);
 @Update("UPDATE sn_org_mapping SET org_code=#{orgCode},org_name=#{orgName},updated_at=UTC_TIMESTAMP(6) WHERE source_system=#{sourceSystem} AND source_account=#{sourceAccount} AND org_id=#{orgId} AND factory_id=#{factoryId}")
 int update(OrganizationMappingEntity value);
 @Update("UPDATE sn_order_bill SET factory_id=#{factoryId},factory_code=#{factoryCode},snapshot_version=#{revision} WHERE source_system=#{system} AND source_account=#{account} AND org_id=#{orgId}")
 int mapBills(@Param("system") String system,@Param("account") String account,@Param("orgId") String orgId,@Param("factoryId") long factoryId,@Param("factoryCode") String factoryCode,@Param("revision") long revision);
 @Update("UPDATE sn_order_entry e JOIN sn_order_bill b ON b.id=e.bill_id SET e.factory_id=b.factory_id,e.snapshot_version=#{revision} WHERE b.source_system=#{system} AND b.source_account=#{account} AND b.org_id=#{orgId}")
 int mapEntries(@Param("system") String system,@Param("account") String account,@Param("orgId") String orgId,@Param("revision") long revision);
}
