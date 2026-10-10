package com.adtek.sn.mapper;

import com.adtek.sn.domain.OrganizationMappingEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 来源记录与显式绑定；写入及订单归属重算必须持安全锁、快照发布锁。 */
@Mapper
public interface OrganizationMappingMapper {
    @Select(
            "SELECT source_system,source_account,org_id,org_code,org_name,"
                    + "factory_id,binding_kind FROM"
                    + " sn_org_mapping WHERE source_system=#{system} AND"
                    + " source_account=#{account}"
                    + " ORDER BY org_id")
    List<OrganizationMappingEntity> list(
            @Param("system") String system, @Param("account") String account);

    @Insert(
            "INSERT INTO"
                    + " sn_org_mapping(source_system,source_account,org_id,org_code,org_name,"
                    + "factory_id,binding_kind)"
                    + " VALUES(#{sourceSystem},#{sourceAccount},#{orgId},#{orgCode},#{orgName},"
                    + "#{factoryId},#{bindingKind})")
    int insert(OrganizationMappingEntity value);

    @Update(
            "UPDATE sn_org_mapping SET"
                    + " org_code=#{orgCode},org_name=#{orgName},factory_id=#{factoryId},"
                    + " binding_kind=#{bindingKind},updated_at=UTC_TIMESTAMP(6) WHERE"
                    + " source_system=#{sourceSystem} AND source_account=#{sourceAccount} AND"
                    + " org_id=#{orgId} AND binding_kind='AUTO'")
    int update(OrganizationMappingEntity value);

    /** 只改当前有效订单的归属；未变化行不抬版本，历史SN和额度不随目录改名重写。 */
    @Update(
            """
            UPDATE sn_order_bill b
            LEFT JOIN sn_org_mapping m ON m.source_system=b.source_system
              AND m.source_account=b.source_account AND m.org_id=b.org_id
              AND m.binding_kind='EXPLICIT'
            LEFT JOIN sn_factory f ON CAST(f.name AS BINARY)=CAST(b.org_name AS BINARY)
            SET b.factory_id=f.id,b.factory_code=f.code,b.snapshot_version=#{revision}
            WHERE b.active=TRUE AND m.org_id IS NULL
              AND (NOT(b.factory_id <=> f.id)
                OR NOT(CAST(b.factory_code AS BINARY) <=> CAST(f.code AS BINARY)))
            """)
    int projectAutomaticBills(@Param("revision") long revision);

    @Update(
            """
            UPDATE sn_order_entry e JOIN sn_order_bill b ON b.id=e.bill_id
            SET e.factory_id=b.factory_id,e.snapshot_version=#{revision}
            WHERE b.active=TRUE AND e.active=TRUE AND NOT(e.factory_id <=> b.factory_id)
            """)
    int projectAutomaticEntries(@Param("revision") long revision);

    @Update(
            "UPDATE sn_order_bill SET"
                    + " factory_id=#{factoryId},factory_code=#{factoryCode},"
                    + "snapshot_version=#{revision}"
                    + " WHERE source_system=#{system} AND source_account=#{account} AND"
                    + " org_id=#{orgId}")
    int mapBills(
            @Param("system") String system,
            @Param("account") String account,
            @Param("orgId") String orgId,
            @Param("factoryId") long factoryId,
            @Param("factoryCode") String factoryCode,
            @Param("revision") long revision);

    @Update(
            "UPDATE sn_order_entry e JOIN sn_order_bill b ON b.id=e.bill_id SET"
                    + " e.factory_id=b.factory_id,e.snapshot_version=#{revision} WHERE"
                    + " b.source_system=#{system} AND b.source_account=#{account} AND"
                    + " b.org_id=#{orgId}")
    int mapEntries(
            @Param("system") String system,
            @Param("account") String account,
            @Param("orgId") String orgId,
            @Param("revision") long revision);
}
