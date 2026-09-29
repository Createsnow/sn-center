-- =====================================================================
-- 多工厂 SN 防重 · 总部发号中心  MySQL 8.0 初始表结构（V1）
--
-- 约定
--   * 引擎 InnoDB，字符集 utf8mb4；SN / PI / 各类编码一律 utf8mb4_bin
--     （区分大小写、按字节精确比较，避免 "a" 与 "A" 被当成同一个号）。
--   * 时间统一 DATETIME(3)，存 Asia/Shanghai 本地时间，由应用写入。
--   * 高频大表（sn_item / sn_audit）不建外键，完整性由应用在事务内保证；
--     主数据小表保留外键。
--   * 状态、角色等枚举用 VARCHAR 存英文码，取值见各列 COMMENT。
--   * 设计说明与待确认项见 docs/db/数据库设计-MySQL.md。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 主数据
-- ---------------------------------------------------------------------

CREATE TABLE sn_factory (
  factory_code   VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '工厂编码 = 金蝶生产组织编码 FNumber',
  factory_name   VARCHAR(100) NOT NULL COMMENT '工厂名称 = 金蝶生产组织名称',
  k3_org_id      BIGINT       NULL COMMENT '金蝶组织内码 FOrgID',
  has_mes        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有 MES（MES 接口领取/回写）；0=无 MES（Web 领取、导出即打印）',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（金蝶组织禁用）',
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (factory_code),
  UNIQUE KEY uk_factory_name (factory_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工厂（金蝶生产组织）';

CREATE TABLE sn_customer (
  customer_code  VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '客户编码（金蝶）；集团主/子客户编码不同，各自一行',
  customer_name  VARCHAR(200) NOT NULL DEFAULT '',
  short_code     VARCHAR(32)  NULL COMMENT '业务简码（如 0527）',
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户（不参与查重，仅展示/筛选/规则归属）';

CREATE TABLE sn_user (
  username             VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '公司工号；服务账号用 svc- 前缀',
  display_name         VARCHAR(64)  NOT NULL,
  password_hash        VARCHAR(160) NULL COMMENT 'PBKDF2 哈希；接 SSO 后可为空',
  role                 VARCHAR(20)  NOT NULL COMMENT 'admin / factory_operator / query / mes_client',
  account_type         VARCHAR(16)  NOT NULL DEFAULT 'HUMAN' COMMENT 'HUMAN 人员 / SERVICE 系统接口账号（MES）',
  factory_code         VARCHAR(32)  COLLATE utf8mb4_bin NULL COMMENT '绑定厂区；factory_operator / mes_client 必填',
  status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  lang                 VARCHAR(8)   NULL COMMENT 'zh-CN / en / vi；空=跟随浏览器',
  must_change_password TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '首次登录/被重置后必须改密',
  failed_login_count   INT          NOT NULL DEFAULT 0,
  locked_until         DATETIME(3)  NULL COMMENT '连续失败锁定截止时间',
  password_changed_at  DATETIME(3)  NULL,
  token_version        INT          NOT NULL DEFAULT 0 COMMENT '改密/停用/改角色时 +1，使旧令牌失效',
  last_login_at        DATETIME(3)  NULL,
  created_by           VARCHAR(32)  NULL,
  created_at           DATETIME(3)  NOT NULL,
  updated_at           DATETIME(3)  NOT NULL,
  PRIMARY KEY (username),
  KEY ix_user_factory (factory_code),
  CONSTRAINT fk_user_factory FOREIGN KEY (factory_code) REFERENCES sn_factory (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号';

CREATE TABLE sn_rule (
  rule_id        VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '= family_code + "-v" + version',
  family_code    VARCHAR(48)  COLLATE utf8mb4_bin NOT NULL COMMENT '规则族；PI 首次生成即锁定族，之后只能同族升版',
  version        INT          NOT NULL,
  bind_scope     VARCHAR(16)  NOT NULL DEFAULT 'customer' COMMENT 'general 通用 / customer 客户级 / pi 单 PI 专用',
  customer_code  VARCHAR(32)  COLLATE utf8mb4_bin NULL COMMENT 'bind_scope=customer/pi 时必填',
  pi_full        VARCHAR(64)  COLLATE utf8mb4_bin NULL COMMENT 'bind_scope=pi 时必填',
  rule_name      VARCHAR(100) NOT NULL,
  prefix         VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  suffix         VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  base           TINYINT      NOT NULL COMMENT '10 / 16 / 32',
  seq_len        TINYINT      NOT NULL COMMENT '流水体位数 1~12',
  charset        VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '字符表，长度 = base',
  charset_mode   VARCHAR(16)  NOT NULL DEFAULT 'system' COMMENT 'system 系统默认 / custom 客户自定义',
  start_seq_dec  BIGINT       NOT NULL DEFAULT 1 COMMENT '新 PI 首号（待确认是否生效，见设计文档 Q9）',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUPERSEDED（已被新版替代）/ DISABLED',
  created_by     VARCHAR(32)  NULL,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (rule_id),
  UNIQUE KEY uk_rule_family_ver (family_code, version),
  KEY ix_rule_customer (customer_code, status),
  CONSTRAINT fk_rule_customer FOREIGN KEY (customer_code) REFERENCES sn_customer (customer_code),
  CONSTRAINT ck_rule_base CHECK (base IN (10, 16, 32)),
  CONSTRAINT ck_rule_seq_len CHECK (seq_len BETWEEN 1 AND 12)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='编码规则模板（改规则 = 新增一版，旧版不改）';

-- ---------------------------------------------------------------------
-- 2. ERP 镜像（只读同步，不删行，只打标记，避免号池引用悬空）
-- ---------------------------------------------------------------------

CREATE TABLE sn_erp_prd_mo (
  mo_line_id       BIGINT        NOT NULL AUTO_INCREMENT,
  k3_entry_id      BIGINT        NOT NULL COMMENT '金蝶 FTreeEntity_FEntryId，同步主键',
  bill_no          VARCHAR(40)   COLLATE utf8mb4_bin NOT NULL COMMENT '生产订单单据编号 FBillNo',
  entry_seq        INT           NOT NULL COMMENT '明细序号 FSeq',
  mo_key           VARCHAR(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '生产订单+序号，MES 领取用的工单标识（口径待确认 Q3）',
  customer_code    VARCHAR(32)   COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'F_ora_kh.FNumber',
  pi_full          VARCHAR(64)   COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'F_HX_PI，必须为 ERP 全称',
  po               VARCHAR(64)   NOT NULL DEFAULT '' COMMENT 'F_HX_PO',
  material_number  VARCHAR(40)   COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  material_name    VARCHAR(200)  NOT NULL DEFAULT '',
  qty              DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '生产数量 FQty',
  erp_status       VARCHAR(8)    NOT NULL COMMENT '金蝶 FStatus：1 计划 2 计划确认 3 下达 4 开工 5 完工 6 结案 7 结算',
  factory_code     VARCHAR(32)   COLLATE utf8mb4_bin NOT NULL COMMENT '生产组织编码 FPrdOrgId.FNumber',
  prd_org_name     VARCHAR(100)  NOT NULL DEFAULT '',
  sale_order_no    VARCHAR(40)   NOT NULL DEFAULT '' COMMENT '需求单据 FSaleOrderNo',
  erp_present      TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '0 = 最近一次全量同步中已不存在（ERP 删除）',
  first_synced_at  DATETIME(3)   NOT NULL,
  synced_at        DATETIME(3)   NOT NULL,
  PRIMARY KEY (mo_line_id),
  UNIQUE KEY uk_prd_mo_entry (k3_entry_id),
  UNIQUE KEY uk_prd_mo_key (mo_key),
  KEY ix_prd_mo_bill (bill_no, entry_seq),
  KEY ix_prd_mo_pi (pi_full, erp_status),
  KEY ix_prd_mo_customer_pi (customer_code, pi_full),
  KEY ix_prd_mo_factory_pi (factory_code, pi_full)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='金蝶生产订单明细镜像：生成额度与领取工单的唯一来源';

CREATE TABLE sn_erp_sale_order (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  k3_entry_id      BIGINT        NOT NULL COMMENT '金蝶销售订单明细内码',
  bill_no          VARCHAR(40)   COLLATE utf8mb4_bin NOT NULL,
  entry_seq        INT           NOT NULL,
  pi_full          VARCHAR(64)   COLLATE utf8mb4_bin NOT NULL,
  customer_code    VARCHAR(32)   COLLATE utf8mb4_bin NOT NULL,
  material_number  VARCHAR(40)   COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  qty              DECIMAL(18,4) NOT NULL DEFAULT 0,
  close_status     VARCHAR(8)    NOT NULL DEFAULT 'A' COMMENT '金蝶关闭状态：A 未关闭 / B 已关闭',
  erp_present      TINYINT(1)    NOT NULL DEFAULT 1,
  synced_at        DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sale_entry (k3_entry_id),
  KEY ix_sale_pi (pi_full, close_status),
  KEY ix_sale_bill (bill_no, entry_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='金蝶销售订单明细镜像：仅用于校验 PI 存在且未关闭（口径待确认 Q10）';

CREATE TABLE sn_sync_log (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  source           VARCHAR(24)  NOT NULL COMMENT 'K3_PRD_MO / K3_SALE_ORDER / K3_ORG',
  mode             VARCHAR(8)   NOT NULL COMMENT 'ALL 全量 / ONE 单张 / INCR 增量',
  bill_no          VARCHAR(40)  NULL,
  status           VARCHAR(12)  NOT NULL COMMENT 'RUNNING / OK / FAIL',
  rows_fetched     INT          NOT NULL DEFAULT 0,
  rows_upserted    INT          NOT NULL DEFAULT 0,
  rows_absent      INT          NOT NULL DEFAULT 0 COMMENT '标记为 erp_present=0 的行数',
  error_msg        VARCHAR(1000) NULL,
  triggered_by     VARCHAR(32)  NOT NULL COMMENT '工号或 SCHEDULER',
  started_at       DATETIME(3)  NOT NULL,
  finished_at      DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY ix_sync_source_time (source, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP 同步记录';

-- ---------------------------------------------------------------------
-- 3. PI 与额度
-- ---------------------------------------------------------------------

CREATE TABLE sn_pi (
  pi_id            BIGINT       NOT NULL AUTO_INCREMENT,
  pi_full          VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT 'PI 全称（ERP 为准），全局唯一',
  customer_code    VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  rule_family      VARCHAR(48)  COLLATE utf8mb4_bin NULL COMMENT '首次生成锁定的规则族',
  current_rule_id  VARCHAR(64)  COLLATE utf8mb4_bin NULL COMMENT '最近一次生成使用的规则版本',
  last_seq_dec     BIGINT       NOT NULL DEFAULT 0 COMMENT 'PI 级计数器：已占用的最大十进制流水；生成时 SELECT ... FOR UPDATE',
  init_seq_dec     BIGINT       NULL COMMENT '上线导入的历史最大号；只允许设置一次',
  init_import_id   BIGINT       NULL,
  init_by          VARCHAR(32)  NULL,
  init_at          DATETIME(3)  NULL,
  item_qty         BIGINT       NOT NULL DEFAULT 0 COMMENT '当前挂在本 PI 的 SN 数（含转入、不含转出），与 sn_item 同事务维护',
  created_at       DATETIME(3)  NOT NULL,
  updated_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (pi_id),
  UNIQUE KEY uk_pi_full (pi_full),
  KEY ix_pi_customer (customer_code),
  CONSTRAINT fk_pi_customer FOREIGN KEY (customer_code) REFERENCES sn_customer (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PI 与 PI 级流水计数器（查重边界）';

CREATE TABLE sn_mo_quota (
  mo_line_id       BIGINT       NOT NULL COMMENT '= sn_erp_prd_mo.mo_line_id，一行生产订单明细一个额度桶',
  pi_id            BIGINT       NOT NULL,
  factory_code     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '生产组织；发号只能发到这个工厂',
  material_number  VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  plan_qty         BIGINT       NOT NULL COMMENT '生成上限（生产数量取整快照，同步时刷新）',
  generated_qty    BIGINT       NOT NULL DEFAULT 0 COMMENT '按本桶生成的号数',
  issued_qty       BIGINT       NOT NULL DEFAULT 0 COMMENT '已确认分配到工厂的号数',
  acquired_qty     BIGINT       NOT NULL DEFAULT 0 COMMENT '以本工单领取（占用）的号数',
  printed_qty      BIGINT       NOT NULL DEFAULT 0,
  updated_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (mo_line_id),
  KEY ix_quota_pi (pi_id),
  KEY ix_quota_factory_pi (factory_code, pi_id),
  CONSTRAINT fk_quota_mo FOREIGN KEY (mo_line_id) REFERENCES sn_erp_prd_mo (mo_line_id),
  CONSTRAINT fk_quota_pi FOREIGN KEY (pi_id) REFERENCES sn_pi (pi_id),
  CONSTRAINT ck_quota_nonneg CHECK (generated_qty >= 0 AND issued_qty >= 0 AND acquired_qty >= 0 AND printed_qty >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单额度桶：生成/发号/领取在事务内锁行并增减，避免对号池做 COUNT(*)';

-- ---------------------------------------------------------------------
-- 4. 号池与流水
-- ---------------------------------------------------------------------

CREATE TABLE sn_lot (
  lot_id           BIGINT       NOT NULL AUTO_INCREMENT,
  lot_no           VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL COMMENT 'LOT-yyyyMMdd-xxxxxxxx',
  request_key      VARCHAR(64)  COLLATE utf8mb4_bin NULL COMMENT '生成请求幂等键，防重复提交',
  pi_id            BIGINT       NOT NULL,
  customer_code    VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  rule_id          VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  mo_line_id       BIGINT       NOT NULL COMMENT '按哪个工单额度桶生成',
  material_number  VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  qty              INT          NOT NULL,
  start_seq_dec    BIGINT       NOT NULL,
  end_seq_dec      BIGINT       NOT NULL,
  start_sn         VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  end_sn           VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  over_limit_ack   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '超过单次默认上限时用户已二次确认（Q1）',
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (lot_id),
  UNIQUE KEY uk_lot_no (lot_no),
  UNIQUE KEY uk_lot_request (request_key),
  KEY ix_lot_pi_seq (pi_id, start_seq_dec),
  KEY ix_lot_mo (mo_line_id),
  KEY ix_lot_created (created_at),
  CONSTRAINT ck_lot_range CHECK (end_seq_dec >= start_seq_dec AND qty = end_seq_dec - start_seq_dec + 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='生成批次（一个批次 = 一个工单桶内一段连续流水）';

CREATE TABLE sn_item (
  pi_id               BIGINT       NOT NULL COMMENT '当前挂靠 PI；贸易转移后改为目标 PI',
  seq_dec             BIGINT       NOT NULL COMMENT '十进制流水，PI 内唯一',
  sn                  VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '完整 SN，写出后永不修改',
  customer_code       VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  rule_id             VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '生成时的规则版本（用于反算进制流水体）',
  lot_id              BIGINT       NULL COMMENT '生成批次；历史导入为空',
  src_mo_line_id      BIGINT       NULL COMMENT '生成时占用的工单额度桶，转厂不改',
  material_number     VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  status              VARCHAR(12)  NOT NULL COMMENT 'LEGACY 历史占用 / GENERATED 已生成 / ISSUED 已分配 / ACQUIRED 已领取(=激活) / PRINTED 已打印(=出货) / VOIDED 作废',
  factory_code        VARCHAR(32)  COLLATE utf8mb4_bin NULL COMMENT '当前所属工厂；GENERATED 时为空',
  issue_id            BIGINT       NULL,
  acquire_id          BIGINT       NULL COMMENT '最近一次领取批次',
  mo_line_id          BIGINT       NULL COMMENT '领取时绑定的工单（产品绑定），转厂随号走',
  print_id            BIGINT       NULL COMMENT '最近一次打印/导出批次',
  transfer_lock_id    BIGINT       NULL COMMENT '处于待审批转移申请中的申请 id；非空时禁止再申请/领取/打印',
  transferred_from    VARCHAR(32)  COLLATE utf8mb4_bin NULL COMMENT '最近一次转出工厂（转出厂查询显示"已转出"）',
  transfer_count      SMALLINT     NOT NULL DEFAULT 0,
  generated_at        DATETIME(3)  NULL,
  issued_at           DATETIME(3)  NULL,
  acquired_at         DATETIME(3)  NULL,
  printed_at          DATETIME(3)  NULL,
  voided_at           DATETIME(3)  NULL,
  void_reason         VARCHAR(200) NULL,
  updated_at          DATETIME(3)  NOT NULL,
  PRIMARY KEY (pi_id, seq_dec),
  UNIQUE KEY uk_item_pi_sn (pi_id, sn),
  KEY ix_item_sn (sn),
  KEY ix_item_factory_pi_status (factory_code, pi_id, status, seq_dec),
  KEY ix_item_acquire (acquire_id),
  KEY ix_item_mo_status (mo_line_id, status),
  KEY ix_item_src_mo (src_mo_line_id),
  KEY ix_item_transferred_from (transferred_from, pi_id),
  CONSTRAINT ck_item_status CHECK (status IN ('LEGACY','GENERATED','ISSUED','ACQUIRED','PRINTED','VOIDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SN 号池明细：一枚 SN 一行；聚簇键 (pi_id, seq_dec) 让号段查询走范围扫描';

CREATE TABLE sn_reserved_range (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  pi_id            BIGINT       NOT NULL,
  start_seq_dec    BIGINT       NOT NULL,
  end_seq_dec      BIGINT       NOT NULL,
  kind             VARCHAR(16)  NOT NULL COMMENT 'LEGACY_IMPORT 上线前历史号段 / SKIP 管理员跳号（若业务确认禁止跳号则不再产生）',
  reason           VARCHAR(200) NOT NULL,
  import_id        BIGINT       NULL,
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_reserved_pi (pi_id, start_seq_dec),
  CONSTRAINT fk_reserved_pi FOREIGN KEY (pi_id) REFERENCES sn_pi (pi_id),
  CONSTRAINT ck_reserved_range CHECK (end_seq_dec >= start_seq_dec)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='永不复用的封存号段';

CREATE TABLE sn_import (
  import_id        BIGINT       NOT NULL AUTO_INCREMENT,
  import_no        VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  kind             VARCHAR(16)  NOT NULL COMMENT 'MAX_SEQ 只导每张 PI 最大号 / DETAIL 导入历史 SN 明细（待确认 Q8）',
  file_name        VARCHAR(200) NOT NULL,
  file_sha256      CHAR(64)     NOT NULL COMMENT '同一文件不可重复导入',
  pi_count         INT          NOT NULL DEFAULT 0,
  qty              BIGINT       NOT NULL DEFAULT 0,
  status           VARCHAR(12)  NOT NULL COMMENT 'OK / FAIL',
  note             VARCHAR(500) NULL,
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (import_id),
  UNIQUE KEY uk_import_no (import_no),
  UNIQUE KEY uk_import_file (file_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上线历史号导入批次';

-- ---------------------------------------------------------------------
-- 5. 发号 / 领取 / 打印
-- ---------------------------------------------------------------------

CREATE TABLE sn_issue (
  issue_id         BIGINT       NOT NULL AUTO_INCREMENT,
  issue_no         VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  pi_id            BIGINT       NOT NULL,
  customer_code    VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  factory_code     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '必须等于工单桶的生产组织',
  mo_line_id       BIGINT       NOT NULL,
  material_number  VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  qty              INT          NOT NULL,
  start_seq_dec    BIGINT       NOT NULL,
  end_seq_dec      BIGINT       NOT NULL,
  start_sn         VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  end_sn           VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (issue_id),
  UNIQUE KEY uk_issue_no (issue_no),
  KEY ix_issue_factory_pi (factory_code, pi_id, created_at),
  KEY ix_issue_mo (mo_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='总部确认分配记录（确认后工厂才可领取）';

CREATE TABLE sn_acquire (
  acquire_id       BIGINT       NOT NULL AUTO_INCREMENT,
  acquire_no       VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  factory_code     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  pi_id            BIGINT       NOT NULL,
  mo_line_id       BIGINT       NOT NULL COMMENT '领取所用工单；同一工单允许多次领取（Q4）',
  material_number  VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  qty              INT          NOT NULL,
  channel          VARCHAR(8)   NOT NULL COMMENT 'MES / WEB',
  idempotency_key  VARCHAR(64)  COLLATE utf8mb4_bin NULL COMMENT 'MES 重试幂等键，同厂内唯一',
  ranges           JSON         NOT NULL COMMENT '本次领到的号段 [{start_seq_dec,end_seq_dec,start_sn,end_sn,qty}]',
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (acquire_id),
  UNIQUE KEY uk_acquire_no (acquire_no),
  UNIQUE KEY uk_acquire_idem (factory_code, idempotency_key),
  KEY ix_acquire_mo (mo_line_id, created_at),
  KEY ix_acquire_factory_pi (factory_code, pi_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='领取（=激活）批次';

CREATE TABLE sn_print (
  print_id         BIGINT       NOT NULL AUTO_INCREMENT,
  print_no         VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  factory_code     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  pi_id            BIGINT       NOT NULL,
  acquire_id       BIGINT       NULL,
  qty              INT          NOT NULL,
  channel          VARCHAR(16)  NOT NULL COMMENT 'MES_WRITEBACK MES 回写 / WEB_EXPORT 无 MES 导出即打印',
  is_reprint       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '重复导出/补打',
  file_name        VARCHAR(200) NULL,
  created_by       VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (print_id),
  UNIQUE KEY uk_print_no (print_no),
  KEY ix_print_factory_pi (factory_code, pi_id, created_at),
  KEY ix_print_acquire (acquire_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打印（=出货）批次';

-- ---------------------------------------------------------------------
-- 6. 转厂（申请 → 总部审批；总部直转也落一条 APPROVED）
-- ---------------------------------------------------------------------

CREATE TABLE sn_transfer (
  transfer_id      BIGINT       NOT NULL AUTO_INCREMENT,
  transfer_no      VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  status           VARCHAR(12)  NOT NULL COMMENT 'PENDING 待审批 / APPROVED 已转移 / REJECTED 驳回 / CANCELLED 申请人撤回',
  mode             VARCHAR(8)   NOT NULL COMMENT 'SINGLE 单枚 / RANGE 连续批次',
  from_factory     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  from_pi_id       BIGINT       NOT NULL,
  from_customer    VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  from_material    VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  start_seq_dec    BIGINT       NOT NULL COMMENT '转出 PI 内的流水区间（SN 不变，流水不变）',
  end_seq_dec      BIGINT       NOT NULL,
  start_sn         VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  end_sn           VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  qty              INT          NOT NULL,
  to_factory       VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  to_pi_id         BIGINT       NOT NULL COMMENT '与 from_pi_id 相同 = 仅换厂；不同 = 贸易转移（规则待确认 Q5）',
  to_customer      VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
  to_material      VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  to_mo_line_id    BIGINT       NULL COMMENT '已领取/已打印号转入后绑定的工单（Q6）',
  status_snapshot  JSON         NULL COMMENT '转移时各状态数量 {"ISSUED":n,"ACQUIRED":n,"PRINTED":n}',
  reason           VARCHAR(500) NOT NULL,
  applied_by       VARCHAR(32)  NOT NULL,
  applied_at       DATETIME(3)  NOT NULL,
  decided_by       VARCHAR(32)  NULL,
  decided_at       DATETIME(3)  NULL,
  decide_note      VARCHAR(500) NULL COMMENT '驳回原因 / 审批意见',
  PRIMARY KEY (transfer_id),
  UNIQUE KEY uk_transfer_no (transfer_no),
  KEY ix_transfer_status (status, applied_at),
  KEY ix_transfer_from (from_factory, applied_at),
  KEY ix_transfer_to (to_factory, applied_at),
  KEY ix_transfer_from_pi (from_pi_id, start_seq_dec),
  CONSTRAINT ck_transfer_range CHECK (end_seq_dec >= start_seq_dec)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='转厂申请与结果';

-- ---------------------------------------------------------------------
-- 7. 审计（按月分区；保存期到期直接 DROP PARTITION，不做大 DELETE）
-- ---------------------------------------------------------------------

CREATE TABLE sn_audit (
  audit_id         BIGINT        NOT NULL AUTO_INCREMENT,
  created_at       DATETIME(3)   NOT NULL,
  request_id       VARCHAR(64)   NULL,
  who              VARCHAR(32)   NOT NULL COMMENT '工号',
  who_name         VARCHAR(64)   NULL COMMENT '姓名快照',
  role             VARCHAR(20)   NULL,
  factory_code     VARCHAR(32)   NULL COMMENT '操作人厂区',
  target_factory   VARCHAR(32)   NULL COMMENT '被操作的厂区（发号到 / 转入 / 转出）',
  action           VARCHAR(32)   NOT NULL COMMENT 'USER_CREATE / RULE_* / GENERATE / ISSUE / ACQUIRE / PRINT / TRANSFER_APPLY / TRANSFER_APPROVE / TRANSFER_REJECT / IMPORT_LEGACY / ERP_SYNC / LOGIN ...',
  result           VARCHAR(8)    NOT NULL DEFAULT 'OK' COMMENT 'OK / FAIL',
  error_code       VARCHAR(64)   NULL,
  error_msg        VARCHAR(1000) NULL,
  customer_code    VARCHAR(32)   NULL,
  pi_full          VARCHAR(64)   COLLATE utf8mb4_bin NULL,
  pi_id            BIGINT        NULL,
  start_seq_dec    BIGINT        NULL COMMENT '与 pi_id 一起支持"某枚 SN 的全部操作记录"按区间反查',
  end_seq_dec      BIGINT        NULL,
  start_sn         VARCHAR(64)   COLLATE utf8mb4_bin NULL,
  end_sn           VARCHAR(64)   COLLATE utf8mb4_bin NULL,
  qty              BIGINT        NULL,
  ref_type         VARCHAR(16)   NULL COMMENT 'LOT / ISSUE / ACQUIRE / PRINT / TRANSFER / USER / RULE',
  ref_no           VARCHAR(64)   NULL,
  client_ip        VARCHAR(45)   NULL,
  payload          JSON          NULL,
  PRIMARY KEY (audit_id, created_at),
  KEY ix_audit_created (created_at),
  KEY ix_audit_factory (factory_code, created_at),
  KEY ix_audit_target_factory (target_factory, created_at),
  KEY ix_audit_pi_range (pi_id, start_seq_dec, end_seq_dec),
  KEY ix_audit_pi_full (pi_full, created_at),
  KEY ix_audit_action (action, created_at),
  KEY ix_audit_who (who, created_at),
  KEY ix_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作痕迹（成功与失败都记），保存 ≥3 年'
PARTITION BY RANGE COLUMNS (created_at) (
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
  PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
  PARTITION p202612 VALUES LESS THAN ('2027-01-01'),
  PARTITION p202701 VALUES LESS THAN ('2027-02-01'),
  PARTITION p202702 VALUES LESS THAN ('2027-03-01'),
  PARTITION p202703 VALUES LESS THAN ('2027-04-01'),
  PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);

-- ---------------------------------------------------------------------
-- 8. 系统参数
-- ---------------------------------------------------------------------

CREATE TABLE sn_config (
  cfg_key          VARCHAR(64)  NOT NULL,
  cfg_value        VARCHAR(500) NOT NULL,
  note             VARCHAR(200) NULL,
  updated_by       VARCHAR(32)  NULL,
  updated_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (cfg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运行参数（单次生成默认上限、审计保存期、schema 版本等）';

INSERT INTO sn_config (cfg_key, cfg_value, note, updated_by, updated_at) VALUES
  ('schema_version',           '1',     '表结构版本',                       'system', NOW(3)),
  ('generate_default_limit',   '10000', '单次生成默认上限；超过需二次确认（Q1）', 'system', NOW(3)),
  ('audit_retention_months',   '36',    '审计保存月数；0 = 永久',            'system', NOW(3)),
  ('login_max_failures',       '5',     '连续登录失败锁定阈值',              'system', NOW(3)),
  ('login_lock_minutes',       '15',    '锁定时长（分钟）',                  'system', NOW(3));
