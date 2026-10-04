package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.*;

/** 规则解析只在已授权SN用例使用；选择写入由成功生成事务持快照/PI锁执行。 */
@Mapper
public interface PiRuleChoiceMapper {
    @Select("SELECT rule_id FROM sn_pi_rule_choice WHERE pi=#{pi}")
    Long chosen(String pi);

    @Select(
            "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.ruleId')) AS UNSIGNED)"
                    + " FROM sn_work WHERE pi=#{pi} AND kind='GENERATE' AND state='SUCCESS'"
                    + " ORDER BY finished_at DESC,task_id DESC LIMIT 1")
    Long previous(String pi);

    @Insert("INSERT INTO sn_pi_rule_choice(pi,rule_id) VALUES(#{pi},#{rule})")
    int choose(@Param("pi") String pi, @Param("rule") long rule);
}
