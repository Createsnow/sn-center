-- 无PI/客户绑定时，首次成功生成选择的规则；与SN、额度、水位在同一事务提交。
CREATE TABLE sn_pi_rule_choice (
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
 rule_id BIGINT NOT NULL,
 selected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_sn_pi_rule_choice CHECK (rule_id>0)
) ENGINE=InnoDB;

-- 升级已有Java工程时保留最近成功生成的规则，不改变已发SN或规则版本。
INSERT INTO sn_pi_rule_choice(pi,rule_id,selected_at)
SELECT pi,rule_id,finished_at FROM (
 SELECT pi,CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.ruleId')) AS UNSIGNED) rule_id,
 finished_at,ROW_NUMBER() OVER (PARTITION BY pi ORDER BY finished_at DESC,task_id DESC) rn
 FROM sn_work WHERE kind='GENERATE' AND state='SUCCESS'
) latest WHERE rn=1 AND rule_id IS NOT NULL AND rule_id>0;
