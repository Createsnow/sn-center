package com.adtek.system.mapper;
import com.adtek.system.domain.OperLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 列表、详情和候选元数据都经过同一审计范围拦截，不提供审计修改/删除用例。 */
@Mapper
public interface AuditQueryMapper extends BaseMapper<OperLogEntity> {
    @Select("SELECT DISTINCT action FROM sys_oper_log WHERE create_time >= #{from} AND create_time < #{to} ORDER BY action LIMIT 201")
    List<String> actions(@Param("from") Instant from,@Param("to") Instant to);
    @Select("SELECT DISTINCT module_name FROM sys_oper_log WHERE create_time >= #{from} AND create_time < #{to} ORDER BY module_name LIMIT 201")
    List<String> modules(@Param("from") Instant from,@Param("to") Instant to);
}
