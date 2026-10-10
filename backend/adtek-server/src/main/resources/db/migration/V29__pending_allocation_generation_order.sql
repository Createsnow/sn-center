-- 改 PI 后同单可能有重叠流水；按生成时间保留原型先生成者优先的分配顺序。
-- 仅重建待分配查询索引，不改 SN、唯一护栏、已有固定成员或历史分配。
ALTER TABLE sn_item
    DROP INDEX ix_sn_item_pending,
    ADD INDEX ix_sn_item_pending(bill_id, status, seq_dec, created_at, id);
