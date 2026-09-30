package com.adtek.sn.mapper;

import com.adtek.sn.domain.OrderBillEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 公开列表/详情/统计/候选执行相同工厂范围；内部发布按来源命名空间整体变更，不删除历史头。 */
@Mapper
public interface OrderBillMapper extends BaseMapper<OrderBillEntity> {
    @Select(
            "SELECT * FROM sn_order_bill WHERE source_system=#{system} AND"
                    + " source_account=#{account} AND source_bill_id=#{billId}")
    OrderBillEntity source(
            @Param("system") String system,
            @Param("account") String account,
            @Param("billId") String billId);

    @Update(
            "<script>UPDATE sn_order_bill SET active=FALSE,snapshot_version=#{version} WHERE"
                    + " source_system=#{system} AND source_account=#{account}<if"
                    + " test='billNo!=null'>"
                    + " AND bill_no=#{billNo}</if></script>")
    int deactivate(
            @Param("system") String system,
            @Param("account") String account,
            @Param("billNo") String billNo,
            @Param("version") long version);

    @Select(
            "SELECT MAX(synced_at) synced_at,COUNT(CASE WHEN customer<>'' THEN 1 END)"
                    + " bills,COALESCE(SUM(line_count),0) rows_count,"
                    + "COUNT(CASE WHEN customer='' THEN 1"
                    + " END) excluded FROM sn_order_bill WHERE active=TRUE")
    Stats stats();

    @Select(
            "SELECT DISTINCT org_code,org_name FROM sn_order_bill WHERE active=TRUE AND"
                    + " customer<>'' AND factory_id IS NULL ORDER BY org_code,org_name LIMIT 1001")
    List<Unmapped> unmapped();

    @Select(
            "<script>SELECT DISTINCT customer FROM sn_order_bill WHERE active=TRUE AND"
                    + " customer&lt;&gt;''<if test='like!=null'> AND customer LIKE #{like} ESCAPE"
                    + " '!'</if> ORDER BY customer LIMIT #{limit} OFFSET #{offset}</script>")
    List<String> customers(
            @Param("like") String like, @Param("offset") int offset, @Param("limit") int limit);

    @Select(
            "<script>SELECT COUNT(DISTINCT customer) FROM sn_order_bill WHERE active=TRUE AND"
                    + " customer&lt;&gt;''<if test='like!=null'> AND customer LIKE #{like} ESCAPE"
                    + " '!'</if></script>")
    long customerCount(@Param("like") String like);

    @Select(
            "SELECT customer FROM sn_order_bill WHERE active=TRUE AND pi=#{pi} AND customer<>''"
                    + " ORDER BY bill_no,id LIMIT 1")
    String effectiveCustomer(@Param("pi") String pi);

    record Stats(java.time.Instant syncedAt, long bills, long rowsCount, long excluded) {}

    record Unmapped(String orgCode, String orgName) {}
}
