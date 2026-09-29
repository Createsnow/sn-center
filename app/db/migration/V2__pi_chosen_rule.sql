-- 没有按 PI / 按客户绑定规则的 PI：首次生成时由用户选定的规则，之后该 PI 一直沿用（同一 PI 一套流水、一种格式）
ALTER TABLE sn_pi_counter
  ADD COLUMN rule_id BIGINT NULL COMMENT '无绑定时首次生成选定的规则（sn_rule.id）' AFTER start_seq_dec;
