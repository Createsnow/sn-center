-- =====================================================================
-- 多生产组织 SN 防重管控 · MySQL 8 初始表结构（需求 PRD V31）
-- 约定：utf8mb4；编码类字段（SN、PI、物料、组织、单据）用 utf8mb4_bin，区分大小写、精确比较。
-- 时间一律按 UTC 存储（DATETIME 不带时区），页面按账户时区显示。
-- sn_audit 按月 RANGE 分区（过期整月归档后 DROP PARTITION）；分区由应用启动与每日任务维护（PartitionService），
-- 初始只有兜底分区 pmax。sn_item 不分区、不归档、不删除：查重唯一键直接建在它上面。
-- 本脚本独立完整：每张表的全部字段、索引都写在各自的 CREATE TABLE 里，不用 ALTER 补加字段或索引；
-- 表结构有变时直接改本脚本并清库重建（正式上线后才新增 V2__…）。
-- =====================================================================

-- 生产组织（金蝶组织机构同步，只读）+ 本系统唯一可维护的 MES 对接配置
CREATE TABLE sn_factory (
  factory_code     VARCHAR(64)   NOT NULL COMMENT '生产组织编码（金蝶 FNumber）',
  factory_name     VARCHAR(128)  NOT NULL COMMENT '名称（只显示）',
  enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '金蝶启用状态',
  mes_enabled      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '对接 MES',
  mes_url          VARCHAR(500)  NOT NULL DEFAULT '' COMMENT 'MES 接收推送的地址',
  mes_auth_type    VARCHAR(8)    NOT NULL DEFAULT 'TOKEN' COMMENT 'TOKEN 固定令牌 + 白名单 / HMAC 签名 / NONE 无认证（不需凭证）',
  mes_credential   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '凭证（加密保存，不回显）',
  mes_gzip         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '推送请求体 gzip 压缩（默认 0 = 原始 JSON）',
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

-- 账户：页面账户（admin 系统管理员（唯一）/ operator 普通操作员 / auditor 审计员）与 MES 接口账户（mes）；只停用不删除
CREATE TABLE sn_user (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  emp_no          VARCHAR(32)   NOT NULL COMMENT '工号，唯一，创建后不能改',
  name            VARCHAR(64)   NOT NULL,
  role            VARCHAR(32)   NOT NULL COMMENT 'admin 系统管理员（唯一）/ operator 普通操作员 / auditor 审计员 / mes MES 接口账户',
  factory_code    VARCHAR(64)   NULL COMMENT 'MES 接口账户的生产组织；普通操作员、审计员的授权组织见 sn_user_factory',
  status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  password_hash   VARCHAR(128)  NOT NULL COMMENT '密码；MES 接口账户为密钥',
  secret_sealed   VARCHAR(255)  NOT NULL DEFAULT '' COMMENT 'MES 接口账户密钥（加密保存，系统管理员可查看）',
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

-- 普通操作员、审计员授权的生产组织：一个或多个，至少一个（没有授权的账户不能登录）
CREATE TABLE sn_user_factory (
  user_id       BIGINT      NOT NULL,
  factory_code  VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, factory_code),
  KEY ix_user_factory_code (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='普通操作员、审计员的授权生产组织（至少一个）';

-- 规则 + 规则版本（编码参数；每个版本归属一张 PI）
CREATE TABLE sn_rule (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  rule_code       VARCHAR(64)  NOT NULL,
  rule_name       VARCHAR(128) NOT NULL,
  category_scope  VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE 未分类 / CUSTOMER 客户 / PI；只用于筛选和推荐',
  category_value  VARCHAR(64)  NOT NULL DEFAULT '',
  factory_code    VARCHAR(64)  NULL COMMENT '所属生产组织；NULL = 全局规则（只有系统管理员能改、删，普通操作员只能选用）',
  current_version INT          NOT NULL DEFAULT 1,
  created_by      VARCHAR(32)  NULL,
  created_at      DATETIME     NOT NULL,
  updated_by      VARCHAR(32)  NULL,
  updated_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_code (rule_code),
  KEY ix_rule_category (category_scope, category_value),
  KEY ix_rule_factory (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 规则';

CREATE TABLE sn_rule_version (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  rule_id      BIGINT      NOT NULL,
  version      INT         NOT NULL,
  prefix       VARCHAR(64) NOT NULL COMMENT '同一前缀下进制、流水位数、字符表相同；不同前缀不能一个是另一个的开头',
  suffix       VARCHAR(64) NOT NULL DEFAULT '',
  base         INT         NOT NULL COMMENT '10 / 16 / 32 / 36',
  seq_len      INT         NOT NULL COMMENT '流水位数',
  charset      VARCHAR(64) NOT NULL COMMENT '字符表，长度 = 进制',
  with_length  TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '带米数',
  length_pad   INT         NOT NULL DEFAULT 0 COMMENT '米数补 0 位数（左侧补 0 到该位数；0 = 不补）',
  length_sep_before VARCHAR(16) NOT NULL DEFAULT '' COMMENT '米数前分隔符',
  length_sep_after  VARCHAR(16) NOT NULL DEFAULT '' COMMENT '米数后分隔符',
  with_unit    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '带单位（物料行的产品单位）',
  used         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '生成过号（含之后撤销的）：再改格式必须另出一版',
  claimed_pi   VARCHAR(64) NULL COMMENT '归属 PI：首次用它生成的 PI，或换前缀另出时的 PI',
  derived_from BIGINT      NULL COMMENT '由哪一版换前缀而来',
  created_by   VARCHAR(32) NULL,
  created_at   DATETIME    NOT NULL,
  updated_at   DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_rule_version (rule_id, version),
  KEY ix_rule_prefix (prefix) COMMENT '前缀不全局唯一：一个前缀只归属一张 PI，可有多个版本（应用层校验，见 rules.check_prefix）',
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
  KEY ix_line_rule_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='物料行规则';

-- 金蝶生产订单快照：已审核单据的每条物料行一行（不存单据状态，均为 C）；表头字段写在每一行上；不存单据内码、分录内码
CREATE TABLE sn_prd_mo (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  bill_no       VARCHAR(64)   NOT NULL COMMENT '单据编号',
  seq           INT           NOT NULL COMMENT '序号 = 金蝶明细行号；物料行 = 单据编号 + 序号',
  customer_code VARCHAR(64)   NOT NULL DEFAULT '',
  pi            VARCHAR(64)   NOT NULL DEFAULT '',
  po            VARCHAR(128)  NOT NULL DEFAULT '',
  org_code      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '生产组织编码（号的归属按它）',
  org_name      VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '生产组织名称（只显示）',
  attach_no     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '附件项次（金蝶值；空时须补填）',
  material_code VARCHAR(64)   NOT NULL DEFAULT '',
  material_name VARCHAR(255)  NOT NULL DEFAULT '',
  length_raw    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '米数（金蝶原值，去首尾空格）',
  unit          VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '产品单位（金蝶 FProductUnit，去首尾空格）',
  qty           DECIMAL(18,4) NOT NULL DEFAULT 0,
  customer_pn   VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '客户 P/N（金蝶明细 FPN，只显示）',
  customer_desc VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '客户产品描述（金蝶明细 FCD，只显示）',
  spec_desc     VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '规格描述（金蝶明细 FAdtek，只显示）',
  material_spec VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '物料规格型号（FMaterialId.FSpecification，只显示）',
  dept_code     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '生产部门编码（金蝶明细生产车间 FWorkShopID，只显示）',
  dept_name     VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '生产部门名称（只显示）',
  modify_time   DATETIME      NULL COMMENT '金蝶修改日期 ModifyDate（比对同步、订单页排序用）',
  synced_at     DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_prd_mo_line (bill_no, seq),
  KEY ix_prd_mo_pi (pi),
  KEY ix_prd_mo_customer (customer_code),
  KEY ix_prd_mo_org (org_code, pi),
  KEY ix_prd_mo_modify (modify_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='生产订单快照';

-- 订单同步状态（一行）
CREATE TABLE sn_sync_state (
  id            TINYINT      NOT NULL,
  last_run_at   DATETIME     NULL COMMENT '最近一次比对同步',
  last_ok       TINYINT(1)   NULL,
  last_message  VARCHAR(500) NOT NULL DEFAULT '',
  last_bills    INT          NULL COMMENT '最近一次变动的单据数',
  last_full_at  DATETIME     NULL COMMENT '最近一次全量重建',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单同步状态';

INSERT INTO sn_sync_state (id) VALUES (1);

-- 金蝶 K3 Cloud 连接参数（生产订单页「金蝶连接」维护，只有一行 id = 1）；没有这一行时沿用 .env 的 K3_*。
CREATE TABLE sn_k3_config (
  id                 TINYINT       NOT NULL,
  server_url         VARCHAR(500)  NOT NULL COMMENT '服务地址（…/k3cloud/）',
  acct_id            VARCHAR(128)  NOT NULL COMMENT '账套 ID（数据中心 ID）',
  app_id             VARCHAR(255)  NOT NULL COMMENT '应用 ID',
  app_secret         VARCHAR(1024) NOT NULL COMMENT '应用密钥（加密保存，不回显）',
  username           VARCHAR(128)  NOT NULL COMMENT '集成用户',
  lcid               INT           NOT NULL DEFAULT 2052 COMMENT '语言：2052 中文简体 / 1033 英文',
  login_timeout      INT           NOT NULL DEFAULT 30 COMMENT '登录超时（秒）',
  query_timeout      INT           NOT NULL DEFAULT 120 COMMENT '查询超时（秒）',
  view_timeout       INT           NOT NULL DEFAULT 60 COMMENT '单据查看超时（秒）',
  page_retries       INT           NOT NULL DEFAULT 3 COMMENT '分页查询失败重试次数',
  page_retry_seconds INT           NOT NULL DEFAULT 10 COMMENT '分页重试间隔（秒）',
  updated_by         VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '最后修改人工号',
  updated_at         DATETIME      NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='金蝶连接参数';

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

-- 米数补填（快照之外；金蝶为空或含中文时由总部填写）
CREATE TABLE sn_line_length (
  bill_no        VARCHAR(64) NOT NULL,
  seq            INT         NOT NULL,
  manual_value   VARCHAR(64) NOT NULL COMMENT '手工值（米数，字符串原样）',
  material_code  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '填写时的物料编码；之后同序号物料变了视为对不上',
  filled_by      VARCHAR(32) NOT NULL,
  filled_at      DATETIME    NOT NULL,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='米数补填';

-- 产品单位补填（快照之外；金蝶为空或含中文（条、个等）时由总部填写，规则带单位时须填写才能生成）
CREATE TABLE sn_line_unit (
  bill_no        VARCHAR(64) NOT NULL,
  seq            INT         NOT NULL,
  manual_value   VARCHAR(64) NOT NULL COMMENT '手工值（产品单位，不含中文，字符串原样）',
  material_code  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '填写时的物料编码；之后同序号物料变了视为对不上',
  filled_by      VARCHAR(32) NOT NULL,
  filled_at      DATETIME    NOT NULL,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (bill_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='产品单位补填';

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
  last_seq_dec   BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 最大十进制流水（自己生成的号与号段预留中最大的；含已转出的，不含转入号）',
  generated_qty  BIGINT      NOT NULL DEFAULT 0 COMMENT '本 PI 自己生成的枚数',
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 流水';

-- PI 号段预留表头：本 PI 第一次生成时建立（同 PI 多单据、不同组织分开生成也按附件项次接号，见 services/reserve）
CREATE TABLE sn_pi_reserve_head (
  pi_no          VARCHAR(64) NOT NULL,
  start_seq      BIGINT      NOT NULL COMMENT '预留起号（十进制流水）：起始号平移的基准',
  created_by     VARCHAR(32) NOT NULL,
  created_at     DATETIME    NOT NULL,
  PRIMARY KEY (pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 号段预留表头';

-- PI 号段预留：物料行的预留号段（一行可有几段）；只在生成事务（PI 操作锁内）变动；PI 最大流水含预留号段
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='PI 号段预留';

-- 已出号单据台账：单据第一次生成号时建立；记最近一次在快照中时的表头与各物料行数量（是否在快照直接看 sn_prd_mo）
CREATE TABLE sn_bill_ledger (
  bill_no        VARCHAR(64)  NOT NULL,
  pi             VARCHAR(64)  NOT NULL DEFAULT '',
  customer_code  VARCHAR(64)  NOT NULL DEFAULT '',
  org_code       VARCHAR(64)  NOT NULL DEFAULT '',
  org_name       VARCHAR(128) NOT NULL DEFAULT '',
  leave_state    VARCHAR(16)  NULL COMMENT '离开快照：REVERSAL 反审核中 / VOID_CONFIRMED 已确认作废 / DELETED 已删除',
  left_at        DATETIME     NULL,
  suspend        VARCHAR(8)   NULL COMMENT '暂停交付：REVERSAL / CHANGE',
  pi_frozen      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'PI 变更未确认：冻结生成和分配',
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NOT NULL,
  PRIMARY KEY (bill_no),
  KEY ix_ledger_leave (leave_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='已出号单据台账';

-- 只用于发现数量下调（物料对应按 sn_line_count.material_code 判断）
CREATE TABLE sn_bill_ledger_line (
  bill_no        VARCHAR(64)   NOT NULL,
  seq            INT           NOT NULL,
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
  origin            VARCHAR(8)  NOT NULL DEFAULT 'GEN' COMMENT 'GEN 本系统生成 / IMPORT SN 导入：没有规则与流水（seq_pi_no 为空串、seq_dec 为导入记录 id、rule_version_id 为 0、单据序号为 0），只能分配，不能删号、转移',
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
  seq_pi_no         VARCHAR(64) NOT NULL COMMENT '流水归属 PI',
  seq_dec           BIGINT      NOT NULL COMMENT '十进制流水',
  length_text       VARCHAR(64) NULL COMMENT '米数（未补 0 的原值；规则不带米数时为空）',
  attach_no         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '附件项次（生成时写定，转单、转组织不变）',
  rule_version_id   BIGINT      NOT NULL,
  gen_batch         VARCHAR(40) NOT NULL,
  -- 交付信息
  status            VARCHAR(16) NOT NULL COMMENT 'PENDING_ALLOC / TO_PUSH / PUSHED / ALLOCATED',
  delivery_mode     VARCHAR(8)  NULL COMMENT 'PUSH 推送 / PULL 拉取 / PAGE 未对接 MES（SN 导出页下载）',
  push_type         VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '下次推送类型 NEW / REASSIGN',
  delivery_batch_no VARCHAR(40) NULL COMMENT '交付批次（推送或拉取建立）',
  suspend           VARCHAR(8)  NULL COMMENT '暂停交付：REVERSAL 单据反审核中 / CHANGE 订单变更待确认',
  mes_holder        VARCHAR(64) NULL COMMENT 'MES 持有组织',
  transfer_kind     VARCHAR(16) NULL COMMENT '最近一次转移：BILL_TRANSFER / ORG_TRANSFER（推送追溯用）',
  transfer_doc_no   VARCHAR(40) NULL,
  transfer_from_org VARCHAR(64) NULL COMMENT '转移时原组织 MES 已持有：原组织（决定批次类型 SAME_ORG_TRANSFER / TRANSFER_IN）',
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
  KEY ix_item_delivery (delivery_batch_no),
  KEY ix_item_holder (mes_holder, pi_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 号池';

-- 单据汇总：每个（PI，单据，号上的生产组织）一行，分配页、SN 导出页的单据列表查它，不对整个号池 GROUP BY。
-- 号变动时标脏（sn_bill_stat_dirty），按单据重算整行（app/services/bill_stats.py）；口径与按号池汇总相同。
CREATE TABLE sn_bill_stat (
  pi_no           VARCHAR(64) NOT NULL COMMENT '号现在挂的 PI',
  bill_no         VARCHAR(64) NOT NULL COMMENT '号现在挂的单据',
  org_key         VARCHAR(64) NOT NULL COMMENT '号上的生产组织；待分配（还没有生产组织）为空串',
  factory_code    VARCHAR(64) NULL COMMENT '号上的生产组织（NULL = 待分配），与号池同口径，授权范围条件直接套用',
  qty             BIGINT      NOT NULL COMMENT '枚数',
  pending         BIGINT      NOT NULL COMMENT '待分配枚数',
  to_push         BIGINT      NOT NULL COMMENT '待推送枚数',
  suspended       BIGINT      NOT NULL COMMENT '暂停交付枚数',
  handled_at      DATETIME    NOT NULL COMMENT '号最近一次变动（号池 updated_at 的最大值）',
  alloc_qty       BIGINT      NOT NULL COMMENT '已分配（不是待分配）的枚数：SN 导出单据列表',
  alloc_suspended BIGINT      NOT NULL COMMENT '已分配且暂停交付的枚数',
  alloc_customer  VARCHAR(64) NULL COMMENT '已分配的号上客户编码的最大值（导出列表显示）',
  refreshed_at    DATETIME    NOT NULL COMMENT '最近一次重算',
  PRIMARY KEY (pi_no, bill_no, org_key),
  KEY ix_bill_stat_bill (bill_no),
  KEY ix_bill_stat_factory (factory_code, pi_no),
  KEY ix_bill_stat_refreshed (refreshed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='单据汇总';

-- 单据汇总待重算：改号的事务提交前写入（同一单据 ver 加 1），重算后只删 ver 未变的行
CREATE TABLE sn_bill_stat_dirty (
  pi_no      VARCHAR(64) NOT NULL,
  bill_no    VARCHAR(64) NOT NULL,
  ver        BIGINT      NOT NULL DEFAULT 1,
  marked_at  DATETIME    NOT NULL,
  PRIMARY KEY (pi_no, bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='单据汇总待重算';

-- 删号存档：SN 删除后从号池移除（腾出完整 SN 与流水，可重新生成），删除前的号与挂靠存档备查
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

-- SN 导入记录：本系统之外已有的 SN 按导入模板（PI、单据编号、行号、完整 SN、物料编码、附件项次、米数、流水号、进制位数、
-- 流水位数、产品单位、生产组织编号、客户编码）一次导入一个导入单号，逐枚记在这里；导入功能自成一套，只用这张表：
-- 号同时写入号池（sn_item.origin = 'IMPORT'，号池结构不变：挂在单据序号 0 上，号池的 seq_dec 即本表 id，seq_pi_no 为空串），
-- 物料编码、附件项次、米数、客户编码号池里照常存（分配、转移、推送用）；行号、流水号、进制、流水位数、产品单位只存在本表，
-- 推送 / 拉取 / 转出的明细（billSeq、serial、serialDec、serialPi）与导出、查询从这里取。
-- 导入后不变，只有 cur_bill_seq 在导入的号转移时随号改到目标物料行。
-- 完整 SN 全局唯一由号池的 ux_item_sn 保证（导入撞上已有的号整次不导入，生成撞上导入的号整次回滚）。
-- 导入页可整单删除（一个导入单号的号全部待分配时）：号从号池移除，记录留在这里标为已删除，之后可重新导入同样的号
CREATE TABLE sn_import_item (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  import_no     VARCHAR(40)  NOT NULL COMMENT '导入单号（一次导入一个）',
  sn            VARCHAR(64)  NOT NULL COMMENT '完整 SN',
  pi_no         VARCHAR(64)  NOT NULL COMMENT 'PI（即流水所属 PI，推送的 serialPi）',
  bill_no       VARCHAR(64)  NOT NULL COMMENT '单据编号（导入时须在订单快照中，且 PI、生产组织、客户与之相同）',
  bill_seq      INT          NOT NULL COMMENT '行号（导入时填写，须为该单据金蝶明细行号，物料编码、附件项次与该行相同）',
  cur_bill_seq  INT          NOT NULL COMMENT '当前行号（推送的 billSeq）：导入时同 bill_seq，号转移时随号改到目标物料行',
  material_code VARCHAR(64)  NOT NULL COMMENT '物料编码（导入时）',
  attach_no     VARCHAR(64)  NOT NULL COMMENT '附件项次（导入时）',
  length_text   VARCHAR(64)  NULL     COMMENT '米数（原样；没填为 NULL）',
  serial_text   VARCHAR(12)  NOT NULL COMMENT '流水号（原样，推送的 serial）',
  seq_base      INT          NOT NULL COMMENT '进制位数 10 / 16 / 32 / 36',
  seq_len       INT          NOT NULL COMMENT '流水位数（= 流水号长度）',
  seq_dec       BIGINT       NOT NULL COMMENT '十进制流水（按进制的默认字符表从流水号换算，推送的 serialDec）',
  unit          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '产品单位（选填）',
  customer_code VARCHAR(64)  NOT NULL COMMENT '客户编码（导入时须与单据相同）',
  factory_code  VARCHAR(64)  NOT NULL COMMENT '生产组织编码（导入时须与单据在订单快照中的生产组织相同；授权范围按它）',
  file_name     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '导入文件名',
  row_no        INT          NOT NULL COMMENT '在导入文件中的行号',
  created_by    VARCHAR(32)  NOT NULL,
  created_at    DATETIME     NOT NULL,
  deleted_by    VARCHAR(32)  NULL COMMENT '整单删除人（未删除为 NULL）',
  deleted_at    DATETIME     NULL COMMENT '整单删除时间',
  PRIMARY KEY (id),
  KEY ix_import_sn (sn) COMMENT '不唯一：整单删除后可重新导入同样的号（唯一性由号池保证）',
  KEY ix_import_no (import_no),
  KEY ix_import_pi (pi_no, bill_no),
  KEY ix_import_bill (bill_no),
  KEY ix_import_factory (factory_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='SN 导入';

-- 交付批次（推送批次、拉取批次、转出批次共用）：内容建立后不变
CREATE TABLE sn_delivery_batch (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  batch_no         VARCHAR(40)   NOT NULL,
  source           VARCHAR(8)    NOT NULL COMMENT 'PUSH 推送任务建立 / PULL MES 拉取建立',
  batch_type       VARCHAR(24)   NOT NULL COMMENT '交付类：NEW 首次下发 / SAME_ORG_TRANSFER 同组织转移 / TRANSFER_IN 跨组织转入 / REMAP 改挂；TRANSFER_OUT 转出',
  factory_code     VARCHAR(64)   NOT NULL COMMENT '接收方组织：交付为号所属组织，转出为来源组织',
  pi_no            VARCHAR(64)   NULL COMMENT '交付批次只属一张 PI；转出批次涉及多张 PI 时为空',
  qty              INT           NOT NULL,
  items_digest     VARCHAR(80)   NOT NULL COMMENT 'sha256:64 位小写十六进制',
  body_gz          MEDIUMBLOB    NOT NULL COMMENT '推送请求体（gzip，逐字节固定）',
  result           VARCHAR(20)   NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED / SUCCESS / FAILED / VOIDED',
  confirm_method   VARCHAR(16)   NULL COMMENT 'RECEIPT MES 回执（接口 ③）/ MANUAL 人工确认',
  confirmed_at     DATETIME      NULL,
  fail_code        VARCHAR(40)   NULL,
  fail_reason      VARCHAR(1000) NULL,
  voidable         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '失败原因允许作废（整批拒绝、报文过大、认证或地址错误）',
  accepted_qty     INT           NOT NULL DEFAULT 0 COMMENT '回执确认接收的枚数',
  push_state       VARCHAR(12)   NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE / WAIT_RETRY 等自动重试 / WAIT_RECEIPT MES 已收到、等 ③ 回执 / EXHAUSTED 重试用尽',
  retry_count      INT           NOT NULL DEFAULT 0 COMMENT '本轮自动重试次数（没送达）',
  receipt_timeouts INT           NOT NULL DEFAULT 0 COMMENT '本轮等 ③ 回执超时次数（每次超时按原批次重推）',
  next_retry_at    DATETIME      NULL COMMENT 'WAIT_RETRY：下次重试时间；WAIT_RECEIPT：等回执截止时间',
  received_at      DATETIME      NULL COMMENT 'MES 最近一次应答已收到',
  push_count       INT           NOT NULL DEFAULT 0 COMMENT '累计发送次数（含自动重试、续推）',
  repush_count     INT           NOT NULL DEFAULT 0 COMMENT '续推次数',
  last_push_at     DATETIME      NULL,
  pulled_at        DATETIME      NULL COMMENT 'MES 最近一次取走',
  transfer_kind    VARCHAR(16)   NULL COMMENT '因转移建立：BILL_TRANSFER / ORG_TRANSFER',
  transfer_doc_no  VARCHAR(40)   NULL,
  rebuilt_from     VARCHAR(40)   NULL COMMENT '作废后重建（转出批次、当场建立的交付批次）：原批次号',
  rebuilt_to       VARCHAR(40)   NULL COMMENT '作废后重建的新批次号',
  manual_reason    VARCHAR(500)  NULL,
  manual_basis     VARCHAR(500)  NULL,
  manual_by        VARCHAR(32)   NULL,
  manual_at        DATETIME      NULL,
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

-- 交付批次明细（建批时写定）与接收情况；转出批次另记转出前挂靠与去向（号上不记）
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
  receipt_result   VARCHAR(12) NULL COMMENT 'ACCEPTED 回执已处理（整批记为接收）',
  PRIMARY KEY (batch_id, line_no),
  KEY ix_delivery_item_sn (sn),
  KEY ix_delivery_item_item (item_id),
  KEY ix_delivery_item_bill (batch_id, bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付批次明细与接收情况';

-- 收到的回执（推送应答、拉取回执，含被拒收和确认后再交的，全部存档）
CREATE TABLE sn_delivery_receipt (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  batch_id     BIGINT        NOT NULL,
  method       VARCHAR(16)   NOT NULL COMMENT 'RECEIPT MES 回执（接口 ③）',
  outcome      VARCHAR(16)   NOT NULL DEFAULT '' COMMENT 'PROCESSED / REJECTED',
  verdict      VARCHAR(16)   NOT NULL COMMENT 'CONFIRMED 生效 / REJECTED 整批拒绝 / MISMATCH 不符 / ARCHIVED 只存档',
  detail       VARCHAR(1000) NULL,
  body_gz      MEDIUMBLOB    NULL,
  created_by   VARCHAR(32)   NOT NULL,
  created_at   DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY ix_receipt_batch (batch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付回执存档';

-- 交付告警：每日自检发现的问题
CREATE TABLE sn_delivery_alert (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  alert_type        VARCHAR(16)   NOT NULL COMMENT 'CHECK 每日自检',
  factory_code      VARCHAR(64)   NOT NULL DEFAULT '',
  batch_no          VARCHAR(40)   NOT NULL DEFAULT '',
  sn                VARCHAR(64)   NULL,
  detail            VARCHAR(1000) NULL,
  status            VARCHAR(8)    NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
  closed_by         VARCHAR(32)   NULL,
  closed_at         DATETIME      NULL,
  note              VARCHAR(500)  NULL COMMENT '核查结论',
  created_at        DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY ix_dalert_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交付告警';

-- 推送任务：同组织 + 同一 PI（转出批次用 #XO）一个任务；推送进度按批次与号池实时统计，不在任务上累计
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
  message       VARCHAR(500)  NOT NULL DEFAULT '',
  started_by    VARCHAR(32)   NOT NULL DEFAULT '',
  started_at    DATETIME      NULL,
  updated_at    DATETIME      NOT NULL,
  PRIMARY KEY (factory_code, task_key),
  KEY ix_push_task_state (state, run_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='推送任务';

-- 转单单
CREATE TABLE sn_bill_transfer (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  transfer_no   VARCHAR(40)  NOT NULL,
  mode          VARCHAR(8)   NOT NULL COMMENT 'VOID 整单作废（范围为整张单据）/ SPLIT 拆单（按物料、单枚或号段）',
  scope         VARCHAR(16)  NOT NULL COMMENT 'BILL 整张单据 / MATERIAL 单据 + 物料 / RANGE 单据 + 物料 + 单枚或连续号段',
  from_bill     VARCHAR(64)  NOT NULL,
  to_bill       VARCHAR(64)  NOT NULL,
  from_pi       VARCHAR(64)  NOT NULL DEFAULT '',
  to_pi         VARCHAR(64)  NOT NULL DEFAULT '',
  to_factory    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '目标单据的生产组织',
  to_seq        INT          NULL COMMENT '目标物料行序号',
  to_material   VARCHAR(64)  NOT NULL DEFAULT '',
  scope_text    VARCHAR(500) NOT NULL DEFAULT '' COMMENT '范围说明：PI / 单据 / 物料 / 起止号',
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
  mode          VARCHAR(8)   NOT NULL COMMENT 'DIRECT 直接转移（没有申请、审批）',
  status        VARCHAR(16)  NOT NULL COMMENT 'APPROVED（生成即生效）',
  scope         VARCHAR(16)  NOT NULL COMMENT 'BILL 整张单据 / MATERIAL 单据 + 物料 / RANGE 单据 + 物料 + 单枚或连续号段',
  from_factory  VARCHAR(64)  NOT NULL,
  from_pi       VARCHAR(64)  NOT NULL,
  from_bill     VARCHAR(64)  NOT NULL DEFAULT '',
  from_material VARCHAR(64)  NOT NULL DEFAULT '',
  from_sn       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '号段的起号（单枚时即该枚）',
  from_end_sn   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '号段的止号（单枚时同起号）',
  qty           BIGINT       NOT NULL,
  to_factory    VARCHAR(64)  NOT NULL,
  to_pi         VARCHAR(64)  NOT NULL,
  to_customer   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '目标单据的客户编码',
  to_bill       VARCHAR(64)  NOT NULL DEFAULT '',
  to_seq        INT          NULL COMMENT '目标物料行序号',
  to_material   VARCHAR(64)  NOT NULL DEFAULT '',
  reason        VARCHAR(500) NOT NULL,
  applied_by    VARCHAR(32)  NOT NULL COMMENT '操作人',
  applied_at    DATETIME     NOT NULL COMMENT '操作时间',
  PRIMARY KEY (id),
  UNIQUE KEY ux_transfer_no (transfer_no),
  KEY ix_transfer_from (from_factory, applied_at),
  KEY ix_transfer_to (to_factory, applied_at),
  KEY ix_transfer_from_pi (from_pi),
  KEY ix_transfer_to_pi (to_pi),
  KEY ix_transfer_from_bill (from_bill),
  KEY ix_transfer_to_bill (to_bill)
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
  PRIMARY KEY (kind, transfer_id, item_id)
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
