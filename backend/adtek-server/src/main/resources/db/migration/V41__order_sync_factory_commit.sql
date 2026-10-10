-- 工厂先独立提交；保留原始拉取基准，仅允许本次同步使用自己的工厂提交版本继续发布订单。
ALTER TABLE sn_order_sync ADD COLUMN factory_version BIGINT NULL;
