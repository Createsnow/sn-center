package com.adtek.sn.mapper;

import com.adtek.sn.domain.RuleVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

/** 已用编码不可原位改；服务先锁头后锁本版本，后续生成需在业务事务持相同版本锁并标used。 */
@Mapper
public interface RuleVersionMapper extends BaseMapper<RuleVersionEntity> {
    @Select(
            "<script>SELECT v.* FROM sn_rule_version v JOIN sn_rule r ON r.id=v.rule_id AND"
                    + " r.current_version=v.version WHERE r.id IN <foreach"
                    + " collection='ids' item='id'"
                    + " open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<RuleVersionEntity> current(@Param("ids") java.util.List<Long> ids);

    @Select(
            "SELECT * FROM sn_rule_version WHERE rule_id=#{ruleId} AND version=#{version} FOR"
                    + " UPDATE")
    RuleVersionEntity lock(@Param("ruleId") long ruleId, @Param("version") long version);
}
