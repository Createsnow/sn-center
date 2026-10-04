package com.adtek.sn.mapper;

import com.adtek.sn.domain.OrderScheduleEntity;

import org.apache.ibatis.annotations.*;

/** 仅调度控制服务和已授权发布事务访问；先安全/快照协调锁，再锁本行。 */
@Mapper
public interface OrderScheduleMapper {
    String COLUMNS =
            "enabled,interval_minutes,next_run_at,owner_id,last_run_id,last_run_at,"
                    + "lease_until,last_state,last_error_code,last_bills,last_rows,updated_at";

    @Select("SELECT " + COLUMNS + " FROM sn_order_sync_schedule WHERE id=1")
    OrderScheduleEntity get();

    @Select("SELECT " + COLUMNS + " FROM sn_order_sync_schedule WHERE id=1 FOR UPDATE")
    OrderScheduleEntity lock();

    @Select("SELECT CURRENT_TIMESTAMP(6)")
    java.time.Instant now();

    @Update(
            "UPDATE sn_order_sync_schedule SET enabled=#{enabled},interval_minutes=#{minutes},"
                    + "next_run_at=#{next},owner_id=#{owner},"
                    + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=1")
    int save(boolean enabled, int minutes, java.time.Instant next, long owner);

    @Update(
            "UPDATE sn_order_sync_schedule SET last_run_id=#{runId},"
                    + "last_run_at=CURRENT_TIMESTAMP(6),last_state='FETCHING',last_error_code=NULL,"
                    + "lease_until=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)),"
                    + "next_run_at=TIMESTAMPADD(MINUTE,interval_minutes,CURRENT_TIMESTAMP(6))"
                    + " WHERE id=1")
    int claim(String runId);

    @Update(
            "UPDATE sn_order_sync_schedule SET last_state='SUCCESS',last_error_code=NULL,"
                    + "last_bills=#{bills},last_rows=#{rows},lease_until=NULL WHERE id=1"
                    + " AND last_run_id=#{runId} AND last_state='FETCHING'")
    int succeeded(String runId, long bills, long rows);

    @Update(
            "UPDATE sn_order_sync_schedule SET last_state='FAILED',last_error_code=#{code},"
                    + "lease_until=NULL WHERE id=1 AND last_run_id=#{runId}"
                    + " AND last_state='FETCHING'")
    int failed(String runId, int code);
}
