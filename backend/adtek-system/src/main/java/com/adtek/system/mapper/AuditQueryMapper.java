package com.adtek.system.mapper;

import com.adtek.system.domain.OperLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 列表、详情和候选元数据都经过同一审计范围拦截，不提供审计修改/删除用例。 */
@Mapper
public interface AuditQueryMapper extends BaseMapper<OperLogEntity> {
    List<String> actions(@Param("from") Instant from, @Param("to") Instant to);

    List<String> modules(@Param("from") Instant from, @Param("to") Instant to);
}
