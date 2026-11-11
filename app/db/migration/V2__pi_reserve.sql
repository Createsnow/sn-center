-- PI 号段预留（生成排号：同 PI 多单据、不同组织分开生成时也按附件项次接号）
-- 第一次生成时把本 PI 全部还有额度、有附件项次的物料行按「附件项次 → 行号 → 单据（本次勾选的按勾选先后在前，其余按单据编号）」
-- 排好，从预留起号起逐行预留号段；之后各物料行生成用自己的预留号段。PI 最大号（sn_pi_counter.last_seq_dec）含预留号段。
CREATE TABLE sn_pi_reserve_head (
  pi_no          VARCHAR(64) NOT NULL,
  start_seq      BIGINT      NOT NULL COMMENT '预留表起号（十进制流水）：起始号平移的基准',
  created_by     VARCHAR(32) NOT NULL,
  created_at     DATETIME    NOT NULL,
  PRIMARY KEY (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 号段预留表头';

CREATE TABLE sn_pi_reserve (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  pi_no          VARCHAR(64) NOT NULL,
  bill_no        VARCHAR(64) NOT NULL,
  seq            INT         NOT NULL COMMENT '单据序号（行号）',
  start_seq      BIGINT      NOT NULL COMMENT '预留起（十进制流水，含）',
  end_seq        BIGINT      NOT NULL COMMENT '预留止（十进制流水，含）',
  created_at     DATETIME    NOT NULL,
  PRIMARY KEY (id),
  KEY ix_reserve_pi (pi_no, start_seq),
  KEY ix_reserve_line (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 号段预留：物料行的预留号段（一行可有几段）';
