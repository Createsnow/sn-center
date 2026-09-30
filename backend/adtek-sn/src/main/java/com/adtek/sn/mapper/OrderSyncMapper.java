package com.adtek.sn.mapper;

import com.adtek.sn.domain.OrderSyncEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

/** 仅全部工厂同步管理用例可读记录；发布结果同快照事务，失败登记为独立事务。 */
@Mapper
public interface OrderSyncMapper extends BaseMapper<OrderSyncEntity> {
    @Select("SELECT MAX(finished_at) FROM sn_order_sync WHERE state='SUCCESS'")
    java.time.Instant lastSuccess();
}
