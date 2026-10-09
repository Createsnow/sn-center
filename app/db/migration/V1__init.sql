-- =====================================================================
-- 多工厂 SN 防重管控 · MySQL 8 初始表结构
-- 约定：utf8mb4；编码类字段（SN、PI、物料、工厂）用 utf8mb4_bin，区分大小写、精确比较。
-- sn_item / sn_audit 按月 RANGE 分区；分区由应用启动与每日任务维护（PartitionService），
-- 初始只有兜底分区 pmax。
-- =====================================================================

-- 工厂（= 金蝶生产组织）
CREATE TABLE sn_factory (
  factory_code  VARCHAR(64)  NOT NULL COMMENT '工厂编码（金蝶组织 FNumber，手工建档时自定）',
  factory_name  VARCHAR(128) NOT NULL COMMENT '工厂名称（= 快照中的生产组织名称）',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  source        VARCHAR(16)  NOT NULL DEFAULT 'K3' COMMENT 'K3 / MANUAL',
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  PRIMARY KEY (factory_code),
  UNIQUE KEY ux_factory_name (factory_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工厂';

-- 账户：只停用不删除
CREATE TABLE sn_user (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  emp_no          VARCHAR(32)  NOT NULL COMMENT '公司工号，唯一',
  name            VARCHAR(64)  NOT NULL,
  role            VARCHAR(32)  NOT NULL COMMENT 'admin / factory_operator / query',
  factory_code    VARCHAR(64)  NULL COMMENT '绑定工厂；厂区操作员必填，查询员可空',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  password_hash   VARCHAR(128) NOT NULL,
  must_change_pwd TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '首次登录 / 重置后必须改密',
  lang            VARCHAR(8)   NULL,
  last_login_at   DATETIME     NULL,
  pwd_changed_at  DATETIME     NULL,
  created_by      VARCHAR(32)  NULL,
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL,
  disabled_by     VARCHAR(32)  NULL,
  disabled_at     DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_user_emp_no (emp_no),
  KEY ix_user_factory (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='账户';

-- 规则（绑定对象）+ 规则版本（编码参数）
CREATE TABLE sn_rule (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  rule_code       VARCHAR(64)  NOT NULL,
  rule_name       VARCHAR(128) NOT NULL,
  bind_scope      VARCHAR(16)  NOT NULL COMMENT 'GENERAL / CUSTOMER / PI',
  bind_value      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '客户编码或 PI；通用为空串',
  bind_key        VARCHAR(64)  GENERATED ALWAYS AS (CASE WHEN bind_scope = 'GENERAL' THEN NULL ELSE bind_value END) VIRTUAL COMMENT '唯一约束用：通用规则为 NULL，可以有多条',
  current_version INT          NOT NULL DEFAULT 1,
  created_by      VARCHAR(32)  NULL,
  created_at      DATETIME     NOT NULL,
  updated_by      VARCHAR(32)  NULL,
  updated_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_code (rule_code),
  UNIQUE KEY ux_rule_bind (bind_scope, bind_key),
  KEY ix_rule_scope_value (bind_scope, bind_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 规则';

CREATE TABLE sn_rule_version (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  rule_id     BIGINT      NOT NULL,
  version     INT         NOT NULL,
  prefix      VARCHAR(64) NOT NULL DEFAULT '',
  suffix      VARCHAR(64) NOT NULL DEFAULT '',
  base        INT         NOT NULL COMMENT '10 / 16 / 32 / 36',
  seq_len     INT         NOT NULL COMMENT '流水位数',
  charset     VARCHAR(64) NOT NULL COMMENT '字符表，长度 = 进制',
  used        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '已生成过号：再改编码参数必须另出一版',
  created_by  VARCHAR(32) NULL,
  created_at  DATETIME    NOT NULL,
  updated_at  DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_version (rule_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 规则版本';

-- 金蝶生产订单快照：一张单的每条物料一行；不存单据头与内码
CREATE TABLE sn_prd_mo (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  bill_no         VARCHAR(64)   NOT NULL COMMENT '单据编号',
  line_seq        INT           NOT NULL COMMENT '单内物料行顺序（金蝶 FEntryId 顺序），号段按此切分',
  customer_number VARCHAR(64)   NOT NULL DEFAULT '',
  po              VARCHAR(128)  NOT NULL DEFAULT '',
  pi              VARCHAR(64)   NOT NULL DEFAULT '',
  material_number VARCHAR(64)   NOT NULL DEFAULT '',
  material_name   VARCHAR(255)  NOT NULL DEFAULT '',
  qty             DECIMAL(18,4) NOT NULL DEFAULT 0,
  status          VARCHAR(8)    NOT NULL COMMENT '1 计划 / 2 计划确认',
  demand_bill_no  VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '需求单据号（销售订单号）',
  prd_org_name    VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '生产组织 = 工厂名称',
  synced_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_prd_mo_line (bill_no, line_seq),
  KEY ix_prd_mo_pi (pi),
  KEY ix_prd_mo_customer (customer_number),
  KEY ix_prd_mo_org (prd_org_name, pi)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生产订单快照';

-- 生产订单定时全量同步：只有一行（id = 1）。多实例时靠「next_run_at 已到期才能抢到」的条件更新保证同一次只跑一份
CREATE TABLE sn_order_sync_schedule (
  id               TINYINT      NOT NULL,
  enabled          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否开启定时全量同步',
  interval_minutes INT          NOT NULL DEFAULT 1440 COMMENT '同步间隔（分钟）',
  next_run_at      DATETIME     NULL COMMENT '下次执行时间；关闭时为空',
  last_run_at      DATETIME     NULL COMMENT '最近一次定时执行的开始时间',
  last_ok          TINYINT(1)   NULL COMMENT '最近一次定时执行是否成功',
  last_bills       INT          NULL COMMENT '最近一次成功同步的单据数',
  last_rows        INT          NULL COMMENT '最近一次成功同步的物料行数',
  last_message     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最近一次失败的原因；成功为空',
  updated_by       VARCHAR(32)  NOT NULL DEFAULT '',
  updated_at       DATETIME     NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生产订单定时全量同步设置';

INSERT INTO sn_order_sync_schedule (id, enabled, interval_minutes) VALUES (1, 0, 1440);

-- PI 流水计数器：一张 PI 一行，生成时 FOR UPDATE
CREATE TABLE sn_pi_counter (
  pi_no          VARCHAR(64) NOT NULL,
  last_seq_dec   BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 已占用的最大十进制流水',
  generated_qty  BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 自己生成的枚数',
  imported_qty   BIGINT      NOT NULL DEFAULT 0 COMMENT '历史导入的枚数',
  start_seq_dec  BIGINT      NULL COMMENT '指定过的起始号',
  rule_id        BIGINT      NULL COMMENT '给该 PI 指定的通用规则（sn_rule.id），可解绑',
  start_locked   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = 已指定起始号或已生成，不再提供起始号',
  locked_by      VARCHAR(32) NULL,
  locked_at      DATETIME    NULL,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 流水计数器';

-- 生产订单维度的已生成 / 已分配数（转走的号仍计入来源订单）
CREATE TABLE sn_bill_gen (
  bill_no        VARCHAR(64) NOT NULL,
  pi_no          VARCHAR(64) NOT NULL,
  factory_code   VARCHAR(64) NOT NULL COMMENT '订单生产组织对应工厂（分配目标）',
  customer_code  VARCHAR(64) NOT NULL DEFAULT '',
  generated_qty  BIGINT      NOT NULL DEFAULT 0,
  allocated_qty  BIGINT      NOT NULL DEFAULT 0,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (bill_no),
  KEY ix_bill_gen_pi (pi_no, factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单生成统计';

-- 预演：不落 SN，只记起止与当时的最大号
CREATE TABLE sn_gen_preview (
  token           CHAR(32)    NOT NULL,
  bill_no         VARCHAR(64) NOT NULL,
  pi_no           VARCHAR(64) NOT NULL,
  factory_code    VARCHAR(64) NOT NULL,
  qty             BIGINT      NOT NULL,
  start_seq_dec   BIGINT      NOT NULL,
  end_seq_dec     BIGINT      NOT NULL,
  start_sn        VARCHAR(64) NOT NULL,
  end_sn          VARCHAR(64) NOT NULL,
  rule_version_id BIGINT      NOT NULL,
  base_last_seq   BIGINT      NOT NULL COMMENT '预演时该 PI 的最大号',
  start_override  BIGINT      NULL,
  segments_json   TEXT        NOT NULL,
  created_by      VARCHAR(32) NOT NULL,
  created_at      DATETIME    NOT NULL,
  expires_at      DATETIME    NOT NULL,
  used_at         DATETIME    NULL,
  PRIMARY KEY (token),
  KEY ix_preview_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生成预演';

-- 生成任务：running_pi 唯一 → 同一 PI 同一时刻只有一个生成
CREATE TABLE sn_gen_job (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  bill_no         VARCHAR(64)   NOT NULL,
  pi_no           VARCHAR(64)   NOT NULL,
  factory_code    VARCHAR(64)   NOT NULL,
  customer_code   VARCHAR(64)   NOT NULL DEFAULT '',
  rule_version_id BIGINT        NOT NULL,
  qty             BIGINT        NOT NULL,
  start_seq_dec   BIGINT        NOT NULL,
  end_seq_dec     BIGINT        NOT NULL,
  start_sn        VARCHAR(64)   NOT NULL,
  end_sn          VARCHAR(64)   NOT NULL,
  status          VARCHAR(16)   NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED',
  done_qty        BIGINT        NOT NULL DEFAULT 0,
  error_code      VARCHAR(64)   NULL,
  error_msg       VARCHAR(1000) NULL,
  running_pi      VARCHAR(64)   NULL COMMENT '运行中 = pi_no，结束置 NULL',
  preview_token   CHAR(32)      NOT NULL,
  segments_json   TEXT          NOT NULL,
  created_by      VARCHAR(32)   NOT NULL,
  created_at      DATETIME      NOT NULL,
  finished_at     DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_job_running_pi (running_pi),
  UNIQUE KEY ux_job_preview (preview_token),
  KEY ix_job_bill (bill_no),
  KEY ix_job_pi (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生成任务';

-- SN 明细：按生成月分区
CREATE TABLE sn_item (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  gen_month       INT         NOT NULL COMMENT '分区键 yyyymm',
  sn              VARCHAR(64) NOT NULL COMMENT '完整 SN，写出后不再修改',
  pi_no           VARCHAR(64) NOT NULL COMMENT '当前挂靠 PI（转厂后为转入 PI）',
  customer_code   VARCHAR(64) NOT NULL DEFAULT '',
  material_code   VARCHAR(64) NOT NULL DEFAULT '',
  factory_code    VARCHAR(64) NULL COMMENT '分配后才有',
  bill_no         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源生产订单单据编号',
  rule_version_id BIGINT      NULL,
  seq_pi_no       VARCHAR(64) NOT NULL COMMENT '流水归属 PI（转厂不变）',
  seq_dec         BIGINT      NULL COMMENT '十进制流水；历史导入无法解析时为空',
  status          VARCHAR(16) NOT NULL COMMENT 'PENDING_ALLOC/TO_ACQUIRE/TO_PRINT/PRINTED/APPLYING',
  prev_status     VARCHAR(16) NULL COMMENT '申请中之前的状态（驳回 / 撤回时恢复）',
  source          VARCHAR(8)  NOT NULL COMMENT 'GEN / IMPORT',
  job_id          BIGINT      NULL,
  batch_no        VARCHAR(40) NULL COMMENT '领取批次',
  print_no        VARCHAR(40) NULL COMMENT '页面打印单号',
  transfer_id     BIGINT      NULL COMMENT '未完成的转厂申请',
  created_at      DATETIME    NOT NULL,
  allocated_at    DATETIME    NULL,
  acquired_at     DATETIME    NULL,
  printed_at      DATETIME    NULL,
  updated_at      DATETIME    NOT NULL,
  PRIMARY KEY (id, gen_month),
  KEY ix_item_sn (sn),
  KEY ix_item_fac (factory_code, status, pi_no, material_code, seq_pi_no, seq_dec),
  KEY ix_item_status (status, factory_code),
  KEY ix_item_pi (pi_no, status, factory_code),
  KEY ix_item_bill (bill_no, status, seq_dec),
  KEY ix_item_batch (batch_no),
  KEY ix_item_print (print_no),
  KEY ix_item_transfer (transfer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 明细（号池）'
PARTITION BY RANGE (gen_month) (PARTITION pmax VALUES LESS THAN MAXVALUE);

-- 查重护栏（不分区）：同一 PI 完整 SN 唯一；同一流水归属 PI 的十进制流水唯一
CREATE TABLE sn_key (
  pi_no      VARCHAR(64) NOT NULL COMMENT '当前挂靠 PI',
  sn         VARCHAR(64) NOT NULL,
  seq_pi_no  VARCHAR(64) NULL,
  seq_dec    BIGINT      NULL,
  PRIMARY KEY (pi_no, sn),
  UNIQUE KEY ux_key_seq (seq_pi_no, seq_dec)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 查重护栏';

CREATE TABLE sn_allocation (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  bill_no      VARCHAR(64) NOT NULL,
  pi_no        VARCHAR(64) NOT NULL,
  factory_code VARCHAR(64) NOT NULL,
  qty          BIGINT      NOT NULL,
  start_sn     VARCHAR(64) NOT NULL,
  end_sn       VARCHAR(64) NOT NULL,
  created_by   VARCHAR(32) NOT NULL,
  created_at   DATETIME    NOT NULL,
  PRIMARY KEY (id),
  KEY ix_alloc_bill (bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='分配记录';

-- 领取批次：同厂同请求号幂等
CREATE TABLE sn_acquire_batch (
  batch_no     VARCHAR(40) NOT NULL,
  factory_code VARCHAR(64) NOT NULL,
  pi_no        VARCHAR(64) NOT NULL,
  request_no   VARCHAR(64) NOT NULL,
  source       VARCHAR(8)  NOT NULL COMMENT 'PAGE / API',
  qty          BIGINT      NOT NULL,
  created_by   VARCHAR(32) NOT NULL,
  created_at   DATETIME    NOT NULL,
  PRIMARY KEY (batch_no),
  UNIQUE KEY ux_batch_request (factory_code, request_no),
  KEY ix_batch_fac_pi (factory_code, pi_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='领取批次';

-- 页面打印单：待打印 → 已打印，并可重新下载打印文件
CREATE TABLE sn_print (
  print_no     VARCHAR(40) NOT NULL,
  factory_code VARCHAR(64) NOT NULL,
  pi_no        VARCHAR(64) NOT NULL,
  request_no   VARCHAR(64) NOT NULL,
  qty          BIGINT      NOT NULL,
  created_by   VARCHAR(32) NOT NULL,
  created_at   DATETIME    NOT NULL,
  PRIMARY KEY (print_no),
  UNIQUE KEY ux_print_request (factory_code, request_no),
  KEY ix_print_fac_pi (factory_code, pi_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='页面打印单';

-- 转厂
CREATE TABLE sn_transfer (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  transfer_no   VARCHAR(40)  NOT NULL,
  mode          VARCHAR(8)   NOT NULL COMMENT 'APPLY 申请 / DIRECT 总部直接',
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  scope         VARCHAR(16)  NOT NULL COMMENT 'PI / MATERIAL / SINGLE',
  from_factory  VARCHAR(64)  NOT NULL,
  from_customer VARCHAR(64)  NOT NULL DEFAULT '',
  from_pi       VARCHAR(64)  NOT NULL,
  from_material VARCHAR(64)  NOT NULL DEFAULT '',
  from_sn       VARCHAR(64)  NOT NULL DEFAULT '',
  qty           BIGINT       NOT NULL,
  to_factory    VARCHAR(64)  NOT NULL,
  to_customer   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '空 = 沿用原值',
  to_pi         VARCHAR(64)  NOT NULL,
  to_material   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '空 = 沿用原值',
  target_source VARCHAR(16)  NOT NULL COMMENT 'SNAPSHOT 快照选择 / MANUAL 手工填写',
  reason        VARCHAR(500) NOT NULL,
  applied_by    VARCHAR(32)  NOT NULL,
  applied_at    DATETIME     NOT NULL,
  decided_by    VARCHAR(32)  NULL,
  decided_at    DATETIME     NULL,
  decide_note   VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_transfer_no (transfer_no),
  KEY ix_transfer_status (status, applied_at),
  KEY ix_transfer_from (from_factory, applied_at),
  KEY ix_transfer_to (to_factory, applied_at),
  KEY ix_transfer_from_pi (from_pi),
  KEY ix_transfer_to_pi (to_pi)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='转厂申请 / 记录';

CREATE TABLE sn_transfer_item (
  transfer_id   BIGINT      NOT NULL,
  item_id       BIGINT      NOT NULL,
  gen_month     INT         NOT NULL,
  sn            VARCHAR(64) NOT NULL,
  seq_pi_no     VARCHAR(64) NOT NULL,
  seq_dec       BIGINT      NULL,
  from_status   VARCHAR(16) NOT NULL,
  from_customer VARCHAR(64) NOT NULL DEFAULT '',
  from_material VARCHAR(64) NOT NULL DEFAULT '',
  from_batch_no VARCHAR(40) NULL,
  PRIMARY KEY (transfer_id, item_id),
  KEY ix_transfer_item_sn (sn),
  KEY ix_transfer_item_batch (from_batch_no, sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='转厂明细（转出厂可查）';

CREATE TABLE sn_pi_import (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  pi_no         VARCHAR(64)  NOT NULL,
  start_seq_dec BIGINT       NULL,
  import_qty    BIGINT       NOT NULL DEFAULT 0,
  max_seq_dec   BIGINT       NULL,
  factory_code  VARCHAR(64)  NULL,
  file_name     VARCHAR(255) NULL,
  created_by    VARCHAR(32)  NOT NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY ix_import_pi (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='起始号 / 历史导入';

-- 审计：按月分区；过期分区整体移入归档表
CREATE TABLE sn_audit (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  created_at    DATETIME(3)   NOT NULL,
  operator      VARCHAR(32)   NOT NULL,
  operator_name VARCHAR(64)   NOT NULL DEFAULT '',
  role          VARCHAR(32)   NOT NULL DEFAULT '',
  source        VARCHAR(8)    NOT NULL COMMENT 'PAGE / API / SYSTEM',
  action        VARCHAR(32)   NOT NULL,
  result        VARCHAR(8)    NOT NULL COMMENT 'OK / FAIL',
  factory_code  VARCHAR(64)   NULL,
  pi_no         VARCHAR(64)   NULL,
  customer_code VARCHAR(64)   NULL,
  material_code VARCHAR(64)   NULL,
  bill_no       VARCHAR(64)   NULL,
  start_sn      VARCHAR(64)   NULL,
  end_sn        VARCHAR(64)   NULL,
  qty           BIGINT        NULL,
  before_status VARCHAR(16)   NULL,
  after_status  VARCHAR(16)   NULL,
  batch_no      VARCHAR(40)   NULL,
  request_no    VARCHAR(64)   NULL,
  transfer_no   VARCHAR(40)   NULL,
  reason        VARCHAR(500)  NULL,
  error_msg     VARCHAR(1000) NULL,
  trace_id      VARCHAR(64)   NULL,
  detail        VARCHAR(2000) NULL,
  PRIMARY KEY (id, created_at),
  KEY ix_audit_created (created_at),
  KEY ix_audit_action (action, created_at),
  KEY ix_audit_factory (factory_code, created_at),
  KEY ix_audit_pi (pi_no, created_at),
  KEY ix_audit_operator (operator, created_at),
  KEY ix_audit_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='操作痕迹'
PARTITION BY RANGE COLUMNS (created_at) (PARTITION pmax VALUES LESS THAN (MAXVALUE));

CREATE TABLE sn_audit_archive (
  id            BIGINT        NOT NULL,
  created_at    DATETIME(3)   NOT NULL,
  operator      VARCHAR(32)   NOT NULL,
  operator_name VARCHAR(64)   NOT NULL DEFAULT '',
  role          VARCHAR(32)   NOT NULL DEFAULT '',
  source        VARCHAR(8)    NOT NULL,
  action        VARCHAR(32)   NOT NULL,
  result        VARCHAR(8)    NOT NULL,
  factory_code  VARCHAR(64)   NULL,
  pi_no         VARCHAR(64)   NULL,
  customer_code VARCHAR(64)   NULL,
  material_code VARCHAR(64)   NULL,
  bill_no       VARCHAR(64)   NULL,
  start_sn      VARCHAR(64)   NULL,
  end_sn        VARCHAR(64)   NULL,
  qty           BIGINT        NULL,
  before_status VARCHAR(16)   NULL,
  after_status  VARCHAR(16)   NULL,
  batch_no      VARCHAR(40)   NULL,
  request_no    VARCHAR(64)   NULL,
  transfer_no   VARCHAR(40)   NULL,
  reason        VARCHAR(500)  NULL,
  error_msg     VARCHAR(1000) NULL,
  trace_id      VARCHAR(64)   NULL,
  detail        VARCHAR(2000) NULL,
  archived_at   DATETIME      NOT NULL,
  PRIMARY KEY (id, created_at),
  KEY ix_audit_arc_created (created_at),
  KEY ix_audit_arc_pi (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=COMPRESSED COMMENT='痕迹归档';
