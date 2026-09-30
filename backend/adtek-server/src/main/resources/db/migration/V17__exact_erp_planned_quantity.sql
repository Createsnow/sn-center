-- ERP计划量按迁移基线保留4位精确小数；SN计数/历史额度仍为BIGINT。旧整数无损扩展，不回写旧库。
-- quantity_ready=false标识含小数分录的来源订单，在单位口径确认前不得生成SN；不以汇总整数推断分录可生成。
ALTER TABLE sn_order_bill
 MODIFY total_qty DECIMAL(23,4) NOT NULL,
 ADD quantity_ready BOOLEAN NOT NULL DEFAULT TRUE AFTER total_qty,
 ADD CONSTRAINT ck_sn_bill_qty_max CHECK(total_qty<=9223372036854775807);
ALTER TABLE sn_order_entry
 MODIFY qty DECIMAL(23,4) NOT NULL,
 ADD CONSTRAINT ck_sn_entry_qty_max CHECK(qty<=9223372036854775807);
