package com.adtek.sn.mapper;
import org.apache.ibatis.annotations.*;
/** 快照/组织映射发布协调；先安全协调，再本控制行。不能覆盖并发完成的另一份拉取。 */
@Mapper
public interface SnControlMapper {
 @Select("SELECT revision FROM sn_snapshot_control WHERE id=1") long revision();
 @Select("SELECT revision FROM sn_snapshot_control WHERE id=1 FOR UPDATE") long lock();
 @Update("UPDATE sn_snapshot_control SET revision=revision+1 WHERE id=1") int advance();
}
