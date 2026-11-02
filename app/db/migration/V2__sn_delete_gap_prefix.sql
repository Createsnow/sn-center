-- =====================================================================
-- V2：删号重建、空号段复用、同前缀多版本
-- 1. 规则版本的前缀不再全局唯一：一个前缀只归属一张 PI（应用层校验），同一前缀可有多个版本（格式的米数、分隔符、
--    单位、后缀可改），同一前缀下进制、流水位数、字符表须相同；不同 PI 的前缀不能相同，任何两个不同的前缀都不能一个是另一个的开头。
-- 2. 删号存档：SN 删除后从号池移除（腾出完整 SN 与流水，可重新生成），删除前的号与挂靠存档备查。
-- =====================================================================

ALTER TABLE sn_rule_version DROP INDEX ux_rule_prefix, ADD KEY ix_rule_prefix (prefix);

CREATE TABLE sn_item_deleted (
  id                BIGINT       NOT NULL COMMENT '原号池 id（号池自增 id 不复用）',
  del_no            VARCHAR(40)  NOT NULL COMMENT '删除单号（一次删除一个）',
  sn                VARCHAR(64)  NOT NULL,
  factory_code      VARCHAR(64)  NULL,
  pi_no             VARCHAR(64)  NOT NULL,
  customer_code     VARCHAR(64)  NOT NULL DEFAULT '',
  bill_no           VARCHAR(64)  NOT NULL,
  bill_seq          INT          NOT NULL,
  material_code     VARCHAR(64)  NOT NULL DEFAULT '',
  rev               INT          NOT NULL,
  gen_org_code      VARCHAR(64)  NOT NULL DEFAULT '',
  gen_bill_no       VARCHAR(64)  NOT NULL,
  gen_bill_seq      INT          NOT NULL,
  seq_pi_no         VARCHAR(64)  NOT NULL,
  seq_dec           BIGINT       NOT NULL,
  length_text       VARCHAR(64)  NULL,
  attach_no         VARCHAR(64)  NOT NULL DEFAULT '',
  rule_version_id   BIGINT       NOT NULL,
  gen_batch         VARCHAR(40)  NOT NULL,
  status            VARCHAR(16)  NOT NULL COMMENT '删除时的状态',
  delivery_mode     VARCHAR(8)   NULL,
  delivery_batch_no VARCHAR(40)  NULL,
  mes_holder        VARCHAR(64)  NULL,
  created_at        DATETIME     NOT NULL COMMENT '生成时间',
  allocated_at      DATETIME     NULL,
  delivered_at      DATETIME     NULL,
  deleted_by        VARCHAR(32)  NOT NULL,
  deleted_at        DATETIME     NOT NULL,
  reason            VARCHAR(500) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_item_deleted_no (del_no),
  KEY ix_item_deleted_sn (sn),
  KEY ix_item_deleted_pi (pi_no, deleted_at),
  KEY ix_item_deleted_seq (seq_pi_no, seq_dec)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='删号存档';
