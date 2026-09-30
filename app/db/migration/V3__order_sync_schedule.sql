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
