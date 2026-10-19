-- =====================================================================
-- 多生产组织 SN 防重管控 · MySQL 8 初始表结构（需求 PRD V30）
-- 约定：utf8mb4；编码类字段（SN、PI、物料、组织、单据）用 utf8mb4_bin，区分大小写、精确比较。
-- 时间一律按 UTC 存储（DATETIME 不带时区），页面按账户时区显示。
-- sn_audit 按月 RANGE 分区（过期整月归档后 DROP PARTITION）；分区由应用启动与每日任务维护（PartitionService），
-- 初始只有兜底分区 pmax。sn_item 不分区、不归档、不删除：查重唯一键直接建在它上面。
-- =====================================================================

-- 生产组织（金蝶组织机构同步，只读）+ 本系统唯一可维护的 MES 对接配置
CREATE TABLE sn_factory (
  factory_code     VARCHAR(64)   NOT NULL COMMENT '生产组织编码（金蝶 FNumber）',
  factory_name     VARCHAR(128)  NOT NULL COMMENT '名称（只显示）',
  enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '金蝶启用状态',
  mes_enabled      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '对接 MES',
  mes_url          VARCHAR(500)  NOT NULL DEFAULT '' COMMENT 'MES 接收推送的地址',
  mes_auth_type    VARCHAR(8)    NOT NULL DEFAULT 'TOKEN' COMMENT 'TOKEN 固定令牌 + 白名单 / HMAC 签名',
  mes_credential   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '凭证（加密保存，不回显）',
  batch_size       INT           NOT NULL DEFAULT 5000 COMMENT '单批推送枚数（也是 MES 拉取建批上限）',
  push_timeout_sec INT           NOT NULL DEFAULT 60 COMMENT '推送超时（秒）',
  push_concurrency INT           NOT NULL DEFAULT 2 COMMENT '同组织同时运行的推送任务数',
  tz_offset_min    INT           NOT NULL DEFAULT 480 COMMENT '时区（相对 UTC 的分钟数，UTC+8 = 480）',
  cfg_status       VARCHAR(16)   NOT NULL DEFAULT 'UNCONFIGURED' COMMENT 'UNCONFIGURED / OK / FAILED',
  cfg_tested       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '最近一次连接测试通过时的配置摘要（开启对接的条件）',
  cfg_message      VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '最近一次测试或推送失败的原因',
  cfg_checked_at   DATETIME      NULL,
  created_at       DATETIME      NOT NULL,
  updated_at       DATETIME      NOT NULL,
  PRIMARY KEY (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生产组织与 MES 对接配置';

-- 账户：页面账户（admin / factory_operator / query）与 MES 接口账户（mes）；只停用不删除
CREATE TABLE sn_user (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  emp_no          VARCHAR(32)   NOT NULL COMMENT '工号，唯一，创建后不能改',
  name            VARCHAR(64)   NOT NULL,
  role            VARCHAR(32)   NOT NULL COMMENT 'admin / factory_operator / query / mes',
  factory_code    VARCHAR(64)   NULL COMMENT '操作员、MES 接口账户的生产组织；查询员的见 sn_user_factory',
  status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  password_hash   VARCHAR(128)  NOT NULL COMMENT '密码；MES 接口账户为密钥',
  must_change_pwd TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '管理员重置后下次登录须改密',
  lang            VARCHAR(8)    NULL,
  last_login_at   DATETIME      NULL,
  pwd_changed_at  DATETIME      NULL COMMENT '改密 / 重置密钥时间：令牌版本',
  auth_failures   INT           NOT NULL DEFAULT 0 COMMENT 'MES 接口账户连续换取令牌失败次数',
  locked_until    DATETIME      NULL COMMENT 'MES 接口账户锁定到',
  ip_whitelist    VARCHAR(1000) NOT NULL DEFAULT '' COMMENT 'MES 接口账户来源地址白名单（逗号分隔，空 = 不限）',
  created_by      VARCHAR(32)   NULL,
  created_at      DATETIME      NOT NULL,
  updated_at      DATETIME      NOT NULL,
  disabled_by     VARCHAR(32)   NULL,
  disabled_at     DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_user_emp_no (emp_no),
  KEY ix_user_factory (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='账户';

-- 查询员绑定的生产组织：可绑一个或多个；没有记录 = 全部
CREATE TABLE sn_user_factory (
  user_id       BIGINT      NOT NULL,
  factory_code  VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, factory_code),
  KEY ix_user_factory_code (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='查询员绑定的生产组织';

-- 规则 + 规则版本（编码参数；每个版本归属一张 PI）
CREATE TABLE sn_rule (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  rule_code       VARCHAR(64)  NOT NULL,
  rule_name       VARCHAR(128) NOT NULL,
  category_scope  VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE 未分类 / CUSTOMER 客户 / PI；只用于筛选和推荐',
  category_value  VARCHAR(64)  NOT NULL DEFAULT '',
  current_version INT          NOT NULL DEFAULT 1,
  created_by      VARCHAR(32)  NULL,
  created_at      DATETIME     NOT NULL,
  updated_by      VARCHAR(32)  NULL,
  updated_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_code (rule_code),
  KEY ix_rule_category (category_scope, category_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 规则';

CREATE TABLE sn_rule_version (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  rule_id      BIGINT      NOT NULL,
  version      INT         NOT NULL,
  prefix       VARCHAR(64) NOT NULL,
  suffix       VARCHAR(64) NOT NULL DEFAULT '',
  base         INT         NOT NULL COMMENT '10 / 16 / 32 / 36',
  seq_len      INT         NOT NULL COMMENT '流水位数',
  charset      VARCHAR(64) NOT NULL COMMENT '字符表，长度 = 进制',
  with_length  TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '带长度',
  used         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '生成过号（含之后撤销的）：再改格式必须另出一版',
  claimed_pi   VARCHAR(64) NULL COMMENT '归属 PI：首次用它生成的 PI，或换前缀另出时的 PI',
  derived_from BIGINT      NULL COMMENT '由哪一版换前缀而来',
  created_by   VARCHAR(32) NULL,
  created_at   DATETIME    NOT NULL,
  updated_at   DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_version (rule_id, version),
  UNIQUE KEY ux_rule_prefix (prefix),
  KEY ix_rule_version_pi (claimed_pi)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 规则版本';

-- PI 绑定规则（各物料行的默认）。本 PI 用该规则的哪一版按规则版本的归属 PI 解析（见 rules.resolve）
CREATE TABLE sn_pi_rule (
  pi_no       VARCHAR(64) NOT NULL,
  rule_id     BIGINT      NOT NULL,
  bound_by    VARCHAR(32) NOT NULL,
  bound_at    DATETIME    NOT NULL,
  PRIMARY KEY (pi_no),
  KEY ix_pi_rule_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 绑定规则';

-- 按客户绑定规则（只能经接口设置，F-22）：作为该客户各 PI 的模板，每张 PI 首次使用须换前缀
CREATE TABLE sn_customer_rule (
  customer_code VARCHAR(64) NOT NULL,
  rule_id       BIGINT      NOT NULL,
  bound_by      VARCHAR(32) NOT NULL,
  bound_at      DATETIME    NOT NULL,
  PRIMARY KEY (customer_code),
  KEY ix_customer_rule_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按客户绑定规则';

-- 物料行规则：物料行已选的规则库规则或自定义规则；自定义规则生成成功后存入规则库并改记规则
CREATE TABLE sn_line_rule (
  bill_no     VARCHAR(64)  NOT NULL,
  seq         INT          NOT NULL,
  pi_no       VARCHAR(64)  NOT NULL,
  rule_id     BIGINT       NULL COMMENT '规则库规则；本 PI 用哪一版按归属 PI 解析',
  custom_json VARCHAR(500) NULL COMMENT '自定义规则参数（生成前）',
  set_by      VARCHAR(32)  NOT NULL,
  set_at      DATETIME     NOT NULL,
  PRIMARY KEY (bill_no, seq),
  KEY ix_line_rule_pi (pi_no),
  KEY ix_line_rule_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='物料行规则';

-- 金蝶生产订单快照：已审核单据的每条物料行一行；表头字段写在每一行上；不存单据内码、分录内码
CREATE TABLE sn_prd_mo (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  bill_no       VARCHAR(64)   NOT NULL COMMENT '单据编号',
  seq           INT           NOT NULL COMMENT '序号 = 金蝶明细行号；物料行 = 单据编号 + 序号',
  doc_status    VARCHAR(8)    NOT NULL COMMENT '单据状态（快照中均为 C 已审核）',
  customer_code VARCHAR(64)   NOT NULL DEFAULT '',
  pi            VARCHAR(64)   NOT NULL DEFAULT '',
  po            VARCHAR(128)  NOT NULL DEFAULT '',
  org_code      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '生产组织编码（号的归属按它）',
  org_name      VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '生产组织名称（只显示）',
  attach_no     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '附件项次（金蝶值；空时须补填）',
  material_code VARCHAR(64)   NOT NULL DEFAULT '',
  material_name VARCHAR(255)  NOT NULL DEFAULT '',
  length_raw    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '长度（金蝶原值，去首尾空格）',
  qty           DECIMAL(18,4) NOT NULL DEFAULT 0,
  modify_time   DATETIME      NULL COMMENT '金蝶修改时间（比对同步用）',
  synced_at     DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_prd_mo_line (bill_no, seq),
  KEY ix_prd_mo_pi (pi),
  KEY ix_prd_mo_customer (customer_code),
  KEY ix_prd_mo_org (org_code, pi)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生产订单快照';

-- 订单同步状态（一行）
CREATE TABLE sn_sync_state (
  id            TINYINT      NOT NULL,
  last_run_at   DATETIME     NULL COMMENT '最近一次比对同步',
  last_ok       TINYINT(1)   NULL,
  last_message  VARCHAR(500) NOT NULL DEFAULT '',
  last_bills    INT          NULL COMMENT '最近一次变动的单据数',
  last_rows     INT          NULL,
  last_full_at  DATETIME     NULL COMMENT '最近一次全量重建',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单同步状态';

INSERT INTO sn_sync_state (id) VALUES (1);

-- 按天汇总的计数（无变动的比对同步次数、MES 无批次可给的取批次数）
CREATE TABLE sn_daily_counter (
  day          DATE        NOT NULL,
  kind         VARCHAR(32) NOT NULL,
  factory_code VARCHAR(64) NOT NULL DEFAULT '',
  n            BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (day, kind, factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按天汇总计数';

-- 限流计数（按组织 + 分钟）
CREATE TABLE sn_rate_counter (
  rkey   VARCHAR(80) NOT NULL,
  win    DATETIME    NOT NULL,
  n      INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (rkey, win)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='限流计数';

-- 单号序列（批次号的每日序号）
CREATE TABLE sn_seq (
  name  VARCHAR(96) NOT NULL,
  val   BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='单号序列';

-- 附件项次补填（快照之外）
CREATE TABLE sn_line_attach (
  bill_no        VARCHAR(64) NOT NULL,
  seq            INT         NOT NULL,
  manual_value   VARCHAR(64) NOT NULL COMMENT '手工值',
  material_code  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '填写时的物料编码；之后同序号物料变了视为对不上',
  k3_value       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '金蝶后来返回的值',
  filled_by      VARCHAR(32) NOT NULL,
  filled_at      DATETIME    NOT NULL,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='附件项次补填';

-- 物料行计数（快照之外，快照覆盖或单据离开快照都不丢）
CREATE TABLE sn_line_count (
  bill_no        VARCHAR(64) NOT NULL,
  seq            INT         NOT NULL,
  material_code  VARCHAR(64) NOT NULL DEFAULT '',
  own_generated  BIGINT      NOT NULL DEFAULT 0 COMMENT '本单生成数（按号上的生成单据、生成序号）',
  gen_count      BIGINT      NOT NULL DEFAULT 0 COMMENT '已生成数（当前挂在本行下）',
  alloc_count    BIGINT      NOT NULL DEFAULT 0 COMMENT '已分配数（按当前挂靠）',
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='物料行计数';

-- PI 流水：一张 PI 一行，生成时 FOR UPDATE
CREATE TABLE sn_pi_counter (
  pi_no          VARCHAR(64) NOT NULL,
  last_seq_dec   BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 自己生成的最大十进制流水（含已转出的，不含转入号）',
  generated_qty  BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 自己生成的枚数',
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 流水';

-- 已出号单据台账：单据第一次生成号时建立；记最近一次在快照中时的表头与各物料行
CREATE TABLE sn_bill_ledger (
  bill_no        VARCHAR(64)  NOT NULL,
  pi             VARCHAR(64)  NOT NULL DEFAULT '',
  customer_code  VARCHAR(64)  NOT NULL DEFAULT '',
  org_code       VARCHAR(64)  NOT NULL DEFAULT '',
  org_name       VARCHAR(128) NOT NULL DEFAULT '',
  in_snapshot    TINYINT(1)   NOT NULL DEFAULT 1,
  leave_state    VARCHAR(16)  NULL COMMENT '离开快照：REVERSAL 反审核中 / VOID_CONFIRMED 已确认作废 / DELETED 已删除',
  left_at        DATETIME     NULL,
  suspend        VARCHAR(8)   NULL COMMENT '暂停交付：REVERSAL / CHANGE',
  pi_frozen      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'PI 变更未确认：冻结生成和分配',
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NOT NULL,
  PRIMARY KEY (bill_no),
  KEY ix_ledger_leave (leave_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='已出号单据台账';

CREATE TABLE sn_bill_ledger_line (
  bill_no        VARCHAR(64)   NOT NULL,
  seq            INT           NOT NULL,
  material_code  VARCHAR(64)   NOT NULL DEFAULT '',
  qty            DECIMAL(18,4) NOT NULL DEFAULT 0,
  PRIMARY KEY (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='已出号单据台账 · 物料行';

-- 订单变更提醒
CREATE TABLE sn_order_alert (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  bill_no       VARCHAR(64)  NOT NULL,
  alert_type    VARCHAR(24)  NOT NULL COMMENT 'PI / ORG / CUSTOMER / QTY / MATERIAL / ATTACH / REVERSAL / DELETED',
  seq           INT          NULL COMMENT '物料行（数量、物料、附件项次）',
  before_value  VARCHAR(255) NOT NULL DEFAULT '',
  after_value   VARCHAR(255) NOT NULL DEFAULT '',
  status        VARCHAR(8)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  closed_by     VARCHAR(32)  NULL,
  closed_at     DATETIME     NULL,
  close_action  VARCHAR(24)  NULL COMMENT 'CONFIRM / MAP / UNRELATED / VOID / ACK / AUTO',
  note          VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY ix_alert_status (status, created_at),
  KEY ix_alert_bill (bill_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单变更提醒';

-- 预演：不落 SN，只记范围、号段与当时的最大号
CREATE TABLE sn_gen_preview (
  token          CHAR(32)    NOT NULL,
  pi_no          VARCHAR(64) NOT NULL,
  qty            BIGINT      NOT NULL,
  base_last_seq  BIGINT      NOT NULL COMMENT '预演时该 PI 的最大号',
  plan_json      MEDIUMTEXT  NOT NULL COMMENT '范围、各物料行数量、号段、所用规则',
  created_by     VARCHAR(32) NOT NULL,
  created_at     DATETIME    NOT NULL,
  expires_at     DATETIME    NOT NULL,
  PRIMARY KEY (token),
  KEY ix_preview_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生成预演';

-- 生成任务：一次生成一行（= 生成批次）；running_pi 唯一 → 同一 PI 同一时刻只有一个生成
CREATE TABLE sn_gen_job (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  gen_batch      VARCHAR(40)   NOT NULL,
  pi_no          VARCHAR(64)   NOT NULL,
  scope          VARCHAR(8)    NOT NULL COMMENT 'BILLS 整单 / LINES 按物料行',
  qty            BIGINT        NOT NULL,
  start_seq_dec  BIGINT        NOT NULL,
  end_seq_dec    BIGINT        NOT NULL,
  start_sn       VARCHAR(64)   NOT NULL,
  end_sn         VARCHAR(64)   NOT NULL,
  status         VARCHAR(16)   NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED / CANCELED / REVOKED',
  done_qty       BIGINT        NOT NULL DEFAULT 0,
  current_sn     VARCHAR(64)   NULL COMMENT '已写到的号',
  error_code     VARCHAR(64)   NULL,
  error_msg      VARCHAR(1000) NULL,
  running_pi     VARCHAR(64)   NULL COMMENT '运行中 = pi_no，结束置 NULL',
  preview_token  CHAR(32)      NOT NULL,
  plan_json      MEDIUMTEXT    NOT NULL,
  created_by     VARCHAR(32)   NOT NULL,
  cancel_by      VARCHAR(32)   NULL,
  cancel_at      DATETIME      NULL,
  revoke_reason  VARCHAR(500)  NULL,
  created_at     DATETIME      NOT NULL,
  started_at     DATETIME      NULL,
  finished_at    DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_job_batch (gen_batch),
  UNIQUE KEY ux_job_running_pi (running_pi),
  UNIQUE KEY ux_job_preview (preview_token),
  KEY ix_job_pi (pi_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生成任务';

-- SN 号池
CREATE TABLE sn_item (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  sn                VARCHAR(64) NOT NULL COMMENT '完整 SN，写出后不再修改',
  -- 当前挂靠
  factory_code      VARCHAR(64) NULL COMMENT '生产组织（分配后才有）',
  pi_no             VARCHAR(64) NOT NULL,
  customer_code     VARCHAR(64) NOT NULL DEFAULT '',
  bill_no           VARCHAR(64) NOT NULL COMMENT '来源单据编号',
  bill_seq          INT         NOT NULL COMMENT '来源单据序号',
  material_code     VARCHAR(64) NOT NULL DEFAULT '',
  rev               INT         NOT NULL DEFAULT 1 COMMENT '挂靠版本号',
  -- 生成信息（之后不改）
  gen_org_code      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '生成时订单生产组织',
  gen_bill_no       VARCHAR(64) NOT NULL,
  gen_bill_seq      INT         NOT NULL,
  gen_material_code VARCHAR(64) NOT NULL DEFAULT '',
  seq_pi_no         VARCHAR(64) NOT NULL COMMENT '流水归属 PI',
  seq_dec           BIGINT      NOT NULL COMMENT '十进制流水',
  length_text       VARCHAR(32) NULL COMMENT '长度（规则不带长度时为空）',
  rule_version_id   BIGINT      NOT NULL,
  gen_batch         VARCHAR(40) NOT NULL,
  -- 交付信息
  status            VARCHAR(16) NOT NULL COMMENT 'PENDING_ALLOC / TO_PUSH / PUSHED / TO_ACQUIRE / ACQUIRED / APPLYING',
  delivery_mode     VARCHAR(8)  NULL COMMENT 'PUSH 推送 / PULL 拉取 / PAGE 页面领取',
  push_type         VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '下次推送类型 NEW / REASSIGN',
  delivery_batch_no VARCHAR(40) NULL COMMENT '交付批次（推送或拉取建立）',
  acquire_batch_no  VARCHAR(40) NULL COMMENT '领取批次',
  suspend           VARCHAR(8)  NULL COMMENT '暂停交付：REVERSAL 单据反审核中 / CHANGE 订单变更待确认',
  mes_holder        VARCHAR(64) NULL COMMENT 'MES 持有组织',
  transfer_id       BIGINT      NULL COMMENT '未完成的转组织申请',
  applying_from     VARCHAR(16) NULL COMMENT '申请前状态',
  transfer_kind     VARCHAR(16) NULL COMMENT '最近一次转移：BILL_TRANSFER / ORG_TRANSFER（推送追溯用）',
  transfer_doc_no   VARCHAR(40) NULL,
  transfer_from_org VARCHAR(64) NULL,
  created_at        DATETIME    NOT NULL,
  allocated_at      DATETIME    NULL,
  delivered_at      DATETIME    NULL,
  updated_at        DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_item_sn (sn) COMMENT '完整 SN 全局唯一（utf8mb4_bin，区分大小写）',
  UNIQUE KEY ux_item_seq (seq_pi_no, seq_dec) COMMENT '流水归属 PI 内十进制流水唯一',
  KEY ix_item_claim (pi_no, factory_code, status, suspend, delivery_batch_no, seq_pi_no, seq_dec) COMMENT '取号：过滤和排序都在索引内',
  KEY ix_item_status (status, factory_code, pi_no),
  KEY ix_item_bill (bill_no, bill_seq, status),
  KEY ix_item_gen_bill (gen_bill_no, gen_bill_seq),
  KEY ix_item_delivery (delivery_batch_no),
  KEY ix_item_acquire (acquire_batch_no),
  KEY ix_item_holder (mes_holder, pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 号池';

-- 交付批次（推送批次、拉取批次、转出批次共用）：内容建立后不变
CREATE TABLE sn_delivery_batch (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  batch_no         VARCHAR(40)   NOT NULL,
  source           VARCHAR(8)    NOT NULL COMMENT 'PUSH 推送任务建立 / PULL MES 拉取建立',
  batch_type       VARCHAR(16)   NOT NULL COMMENT 'DELIVERY 交付 / TRANSFER_OUT 转出',
  factory_code     VARCHAR(64)   NOT NULL COMMENT '接收方组织：交付为号所属组织，转出为来源组织',
  pi_no            VARCHAR(64)   NULL COMMENT '交付批次只属一张 PI；转出批次涉及多张 PI 时为空',
  qty              INT           NOT NULL,
  items_digest     VARCHAR(80)   NOT NULL COMMENT 'sha256:64 位小写十六进制',
  body_gz          MEDIUMBLOB    NOT NULL COMMENT '推送请求体（gzip，逐字节固定）',
  result           VARCHAR(20)   NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED / SUCCESS / SUCCESS_CONFLICT / FAILED / VOIDED',
  confirm_method   VARCHAR(16)   NULL COMMENT 'PUSH_RESPONSE / PULL_RECEIPT / MANUAL',
  confirmed_at     DATETIME      NULL,
  fail_code        VARCHAR(40)   NULL,
  fail_reason      VARCHAR(1000) NULL,
  voidable         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '失败原因允许作废（整批拒绝、报文过大、认证或地址错误）',
  accepted_qty     INT           NOT NULL DEFAULT 0,
  duplicate_qty    INT           NOT NULL DEFAULT 0,
  conflict_qty     INT           NOT NULL DEFAULT 0,
  stale_qty        INT           NOT NULL DEFAULT 0,
  push_state       VARCHAR(12)   NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE / WAIT_RETRY / EXHAUSTED',
  retry_count      INT           NOT NULL DEFAULT 0 COMMENT '本轮自动重试次数',
  next_retry_at    DATETIME      NULL,
  push_count       INT           NOT NULL DEFAULT 0 COMMENT '累计发送次数（含自动重试、续推）',
  repush_count     INT           NOT NULL DEFAULT 0 COMMENT '续推次数',
  last_push_at     DATETIME      NULL,
  pulled_at        DATETIME      NULL COMMENT 'MES 最近一次取走',
  transfer_kind    VARCHAR(16)   NULL COMMENT '因转移建立：BILL_TRANSFER / ORG_TRANSFER',
  transfer_doc_no  VARCHAR(40)   NULL,
  rebuilt_from     VARCHAR(40)   NULL COMMENT '转出批次作废后重建：原批次号',
  rebuilt_to       VARCHAR(40)   NULL COMMENT '作废后重建的新批次号',
  manual_reason    VARCHAR(500)  NULL,
  manual_basis     VARCHAR(500)  NULL,
  manual_by        VARCHAR(32)   NULL,
  manual_at        DATETIME      NULL,
  alerted_unconfirmed TINYINT(1) NOT NULL DEFAULT 0,
  created_by       VARCHAR(32)   NOT NULL,
  created_at       DATETIME      NOT NULL,
  updated_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_delivery_batch_no (batch_no),
  KEY ix_delivery_pick (factory_code, pi_no, result, created_at),
  KEY ix_delivery_type (factory_code, batch_type, result, created_at),
  KEY ix_delivery_result (result, created_at),
  KEY ix_delivery_doc (transfer_doc_no),
  KEY ix_delivery_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付批次';

-- 交付批次明细（建批时写定）与逐枚回执；转出批次另记转出前挂靠与去向（号上不记）
CREATE TABLE sn_delivery_item (
  batch_id         BIGINT      NOT NULL,
  line_no          INT         NOT NULL,
  item_id          BIGINT      NOT NULL,
  sn               VARCHAR(64) NOT NULL,
  rev              INT         NOT NULL,
  push_type        VARCHAR(16) NOT NULL COMMENT 'NEW / REASSIGN / TRANSFER_OUT',
  pi_no            VARCHAR(64) NOT NULL COMMENT '交付：当前 PI；转出：转出前 PI',
  bill_no          VARCHAR(64) NOT NULL,
  bill_seq         INT         NOT NULL,
  material_code    VARCHAR(64) NOT NULL DEFAULT '',
  customer_code    VARCHAR(64) NOT NULL DEFAULT '',
  seq_pi_no        VARCHAR(64) NOT NULL,
  seq_dec          BIGINT      NOT NULL,
  to_factory       VARCHAR(64) NULL COMMENT '转出：转入组织',
  receipt_result   VARCHAR(12) NULL COMMENT 'ACCEPTED / DUPLICATE / CONFLICT / STALE',
  existing_rev     INT         NULL,
  existing_batch_no VARCHAR(40) NULL,
  PRIMARY KEY (batch_id, line_no),
  KEY ix_delivery_item_sn (sn),
  KEY ix_delivery_item_item (item_id),
  KEY ix_delivery_item_bill (batch_id, bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付批次明细与逐枚回执';

-- 收到的回执（推送应答、拉取回执，含被拒收和确认后再交的，全部存档）
CREATE TABLE sn_delivery_receipt (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  batch_id     BIGINT        NOT NULL,
  method       VARCHAR(16)   NOT NULL COMMENT 'PUSH_RESPONSE / PULL_RECEIPT',
  outcome      VARCHAR(16)   NOT NULL DEFAULT '' COMMENT 'PROCESSED / REJECTED',
  verdict      VARCHAR(16)   NOT NULL COMMENT 'CONFIRMED 生效 / REJECTED 整批拒绝 / MISMATCH 不符 / ARCHIVED 只存档',
  detail       VARCHAR(1000) NULL,
  body_gz      MEDIUMBLOB    NULL,
  created_by   VARCHAR(32)   NOT NULL,
  created_at   DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY ix_receipt_batch (batch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付回执存档';

-- 交付告警：疑似重复交付（冲突）、版本过期、每日自检问题
CREATE TABLE sn_delivery_alert (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  alert_type        VARCHAR(16)   NOT NULL COMMENT 'CONFLICT / STALE / CHECK',
  factory_code      VARCHAR(64)   NOT NULL DEFAULT '',
  batch_no          VARCHAR(40)   NOT NULL DEFAULT '',
  item_id           BIGINT        NULL,
  sn                VARCHAR(64)   NULL,
  rev               INT           NULL,
  existing_rev      INT           NULL,
  existing_batch_no VARCHAR(40)   NULL,
  detail            VARCHAR(1000) NULL,
  status            VARCHAR(8)    NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  close_action      VARCHAR(16)   NULL COMMENT 'RESEND 以本系统为准重推 / CLOSE 关闭',
  closed_by         VARCHAR(32)   NULL,
  closed_at         DATETIME      NULL,
  note              VARCHAR(500)  NULL,
  created_at        DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY ix_dalert_status (status, created_at),
  KEY ix_dalert_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付告警';

-- 推送任务：同组织 + 同一 PI（转出批次用 #XO）一个任务
CREATE TABLE sn_push_task (
  factory_code  VARCHAR(64)   NOT NULL,
  task_key      VARCHAR(64)   NOT NULL COMMENT 'PI；转出批次为 #XO',
  state         VARCHAR(12)   NOT NULL COMMENT 'QUEUED / RUNNING / WAITING / STOPPED / DONE',
  run_after     DATETIME      NULL COMMENT 'WAITING：到这个时间再跑（等自动重试）',
  lease_until   DATETIME      NULL COMMENT 'RUNNING：执行实例的租约',
  runner        VARCHAR(64)   NULL,
  current_batch VARCHAR(40)   NULL,
  resend        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '续推：未确认、失败的批次（含拉取建立的）也重推',
  requeue       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '运行中又有新号可推：结束后再排队一次',
  pushed_batches INT          NOT NULL DEFAULT 0,
  pushed_qty    BIGINT        NOT NULL DEFAULT 0,
  message       VARCHAR(500)  NOT NULL DEFAULT '',
  started_by    VARCHAR(32)   NOT NULL DEFAULT '',
  started_at    DATETIME      NULL,
  updated_at    DATETIME      NOT NULL,
  PRIMARY KEY (factory_code, task_key),
  KEY ix_push_task_state (state, run_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='推送任务';

-- 领取批次：同组织同请求号幂等
CREATE TABLE sn_acquire_batch (
  batch_no     VARCHAR(40) NOT NULL,
  factory_code VARCHAR(64) NOT NULL,
  pi_no        VARCHAR(64) NOT NULL,
  request_no   VARCHAR(64) NOT NULL,
  mes_org      TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '领取时该组织对接 MES',
  status       VARCHAR(12) NOT NULL COMMENT 'INCOMPLETE 未完成 / DONE 已完成',
  qty          BIGINT      NOT NULL DEFAULT 0,
  created_by   VARCHAR(32) NOT NULL,
  created_at   DATETIME    NOT NULL,
  finished_at  DATETIME    NULL,
  PRIMARY KEY (batch_no),
  UNIQUE KEY ux_acquire_request (factory_code, request_no),
  KEY ix_acquire_fac_pi (factory_code, pi_no, created_at),
  KEY ix_acquire_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='领取批次';

-- 转单单
CREATE TABLE sn_bill_transfer (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  transfer_no   VARCHAR(40)  NOT NULL,
  mode          VARCHAR(8)   NOT NULL COMMENT 'VOID 整单作废 / SPLIT 拆单',
  from_bill     VARCHAR(64)  NOT NULL,
  to_bill       VARCHAR(64)  NOT NULL,
  from_pi       VARCHAR(64)  NOT NULL DEFAULT '',
  to_pi         VARCHAR(64)  NOT NULL DEFAULT '',
  scope_text    VARCHAR(500) NOT NULL DEFAULT '' COMMENT '范围说明（拆单：生产组织 + PI + 物料行 + 起止号）',
  qty           BIGINT       NOT NULL,
  reason        VARCHAR(500) NOT NULL,
  created_by    VARCHAR(32)  NOT NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_bill_transfer_no (transfer_no),
  KEY ix_bill_transfer_from (from_bill),
  KEY ix_bill_transfer_to (to_bill),
  KEY ix_bill_transfer_pi (from_pi, to_pi)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='转单单';

-- 转组织单
CREATE TABLE sn_transfer (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  transfer_no   VARCHAR(40)  NOT NULL,
  mode          VARCHAR(8)   NOT NULL COMMENT 'APPLY 申请 / DIRECT 总部直接',
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  scope         VARCHAR(16)  NOT NULL COMMENT 'PI / MATERIAL / SINGLE',
  from_factory  VARCHAR(64)  NOT NULL,
  from_pi       VARCHAR(64)  NOT NULL,
  from_material VARCHAR(64)  NOT NULL DEFAULT '',
  from_sn       VARCHAR(64)  NOT NULL DEFAULT '',
  qty           BIGINT       NOT NULL,
  to_factory    VARCHAR(64)  NOT NULL,
  to_pi         VARCHAR(64)  NOT NULL,
  to_customer   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '空 = 沿用原值',
  to_material   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '空 = 沿用原值',
  manual_fields VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '手工填写的目标字段：pi,customer,material',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='转组织单';

-- 转移明细（转单、转组织共用）：每枚号转移前后的挂靠
CREATE TABLE sn_transfer_item (
  kind           VARCHAR(4)  NOT NULL COMMENT 'BILL 转单 / ORG 转组织',
  transfer_id    BIGINT      NOT NULL,
  item_id        BIGINT      NOT NULL,
  sn             VARCHAR(64) NOT NULL,
  seq_pi_no      VARCHAR(64) NOT NULL,
  seq_dec        BIGINT      NOT NULL,
  from_status    VARCHAR(16) NOT NULL,
  from_factory   VARCHAR(64) NULL,
  from_pi        VARCHAR(64) NOT NULL,
  from_customer  VARCHAR(64) NOT NULL DEFAULT '',
  from_bill      VARCHAR(64) NOT NULL,
  from_seq       INT         NOT NULL,
  from_material  VARCHAR(64) NOT NULL DEFAULT '',
  from_delivery_batch VARCHAR(40) NULL COMMENT '原交付批次',
  from_acquire_batch  VARCHAR(40) NULL COMMENT '原领取批次',
  from_rev       INT         NOT NULL,
  to_status      VARCHAR(16) NULL,
  to_factory     VARCHAR(64) NULL,
  to_pi          VARCHAR(64) NULL,
  to_customer    VARCHAR(64) NULL,
  to_bill        VARCHAR(64) NULL,
  to_seq         INT         NULL,
  to_material    VARCHAR(64) NULL,
  to_rev         INT         NULL,
  out_batch_no   VARCHAR(40) NULL COMMENT '向来源 MES 推送的转出批次',
  PRIMARY KEY (kind, transfer_id, item_id),
  KEY ix_transfer_item_sn (sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='转移明细';

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
  KEY ix_audit_bill (bill_no, created_at),
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
