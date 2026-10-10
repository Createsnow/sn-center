package com.adtek.sn.mapper;

import com.adtek.sn.domain.BillGenerationEntity;
import com.adtek.sn.domain.OrderBillEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 只由具备全部工厂权限的生成/分配用例调用；写入加入控制锁和业务事务。 */
@Mapper
public interface BillGenerationMapper {
    @Select(
            "SELECT bill_id,first_pi,first_customer,factory_id,first_task_id,last_task_id"
                    + " FROM sn_bill_generation WHERE bill_id=#{bill}")
    BillGenerationEntity find(long bill);

    @Insert(
            "INSERT INTO sn_bill_generation"
                    + "(bill_id,first_pi,first_customer,factory_id,first_task_id,last_task_id)"
                    + " VALUES(#{bill},#{pi},#{customer},#{factory},#{task},#{task})"
                    + " ON DUPLICATE KEY UPDATE factory_id=#{factory},last_task_id=#{task},"
                    + "updated_at=CURRENT_TIMESTAMP(6)")
    int generated(
            @Param("bill") long bill,
            @Param("pi") String pi,
            @Param("customer") String customer,
            @Param("factory") long factory,
            @Param("task") String task);

    @Select(
            "SELECT * FROM sn_order_bill WHERE active=TRUE AND customer<>''"
                    + " AND pi=#{pi} AND CAST(org_name AS BINARY)=CAST(#{org} AS BINARY)"
                    + " ORDER BY bill_no,id")
    List<OrderBillEntity> current(@Param("pi") String pi, @Param("org") String org);

    @Select(
            "SELECT b.* FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id"
                    + " WHERE g.first_pi=#{pi} AND g.factory_id=#{factory} ORDER BY b.bill_no,b.id")
    List<OrderBillEntity> historical(@Param("pi") String pi, @Param("factory") long factory);
}
