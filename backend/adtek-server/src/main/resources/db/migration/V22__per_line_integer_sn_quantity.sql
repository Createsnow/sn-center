-- 原始分录 qty 不变；订单的可生成枚数对齐原型 SUM(FLOOR(qty))，不能先相加再取整。
UPDATE sn_order_bill b
LEFT JOIN (
    SELECT bill_id,SUM(FLOOR(qty)) quantity FROM sn_order_entry WHERE active=TRUE GROUP BY bill_id
) e ON e.bill_id=b.id
SET b.total_qty=COALESCE(e.quantity,0),b.quantity_ready=TRUE
WHERE b.active=TRUE;
