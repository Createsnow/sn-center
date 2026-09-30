package com.adtek.sn.mapper;

import com.adtek.sn.domain.RuleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

/** 全部工厂规则目录；用例验证共享规则管理权限，更新先锁规则头再锁当前版本。 */
@Mapper
public interface RuleMapper extends BaseMapper<RuleEntity> {
    @Select("SELECT * FROM sn_rule WHERE id=#{id} FOR UPDATE")
    RuleEntity lock(@Param("id") long id);

    @Select(
            "SELECT * FROM sn_rule WHERE (bind_scope='PI' AND bind_value=#{pi}) OR"
                    + " (bind_scope='CUSTOMER' AND bind_value=#{customer}) OR"
                    + " bind_scope='GENERAL'"
                    + " ORDER BY CASE bind_scope WHEN 'PI' THEN 1 WHEN 'CUSTOMER' THEN 2 ELSE"
                    + " 3 END"
                    + " LIMIT 1")
    RuleEntity effective(@Param("pi") String pi, @Param("customer") String customer);
}
