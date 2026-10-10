-- 生成归属与可变 ERP 表头分离；累计数量仍由稳定来源额度台账唯一保存。
CREATE TABLE sn_bill_generation (
    bill_id BIGINT NOT NULL PRIMARY KEY,
    first_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    first_customer VARCHAR(128) COLLATE utf8mb4_0900_bin NULL
        COMMENT '缺少唯一可信成功审计的旧记录保留 NULL，不从当前 ERP 或转厂后的 SN 猜测',
    factory_id BIGINT NOT NULL,
    first_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_bill_generation_scope(first_pi,factory_id,bill_id)
) ENGINE=InnoDB COMMENT='首次生成 PI/客户不变；工厂只随成功生成更新，失败整批回滚';

-- 历史来源是成功任务及同一主体/操作的成功审计（含归档），不使用可变订单头。
INSERT INTO sn_bill_generation
    (bill_id,first_pi,first_customer,factory_id,first_task_id,last_task_id,updated_at)
WITH ranked AS (
    SELECT w.*,
        ROW_NUMBER() OVER (PARTITION BY bill_id ORDER BY finished_at,created_at,task_id) AS first_rank,
        ROW_NUMBER() OVER (PARTITION BY bill_id ORDER BY finished_at DESC,created_at DESC,task_id DESC) AS last_rank
    FROM sn_work w
    WHERE kind='GENERATE' AND state='SUCCESS' AND bill_id IS NOT NULL AND factory_id IS NOT NULL
), facts AS (
    SELECT actor_id,operation_id,pi,factory_id,quantity,customer
    FROM sn_audit WHERE action='sn:generation:execute' AND result='SUCCESS'
    UNION ALL
    SELECT actor_id,operation_id,pi,factory_id,quantity,customer
    FROM sn_audit_archive WHERE action='sn:generation:execute' AND result='SUCCESS'
), first_customer AS (
    SELECT f.task_id,
        CASE WHEN COUNT(*)=COUNT(a.customer) AND COUNT(DISTINCT CAST(a.customer AS BINARY))=1
            THEN MAX(a.customer) ELSE NULL END AS customer
    FROM ranked f
    LEFT JOIN sys_operation o ON o.task_id=f.task_id AND o.principal_id=f.actor_id AND o.action='sn:generation:execute'
    LEFT JOIN facts a ON a.actor_id=f.actor_id AND a.operation_id=o.operation_id
        AND CAST(a.pi AS BINARY)=CAST(f.pi AS BINARY) AND a.factory_id=f.factory_id AND a.quantity=f.quantity
    WHERE f.first_rank=1
    GROUP BY f.task_id
)
SELECT f.bill_id,f.pi,c.customer,l.factory_id,f.task_id,l.task_id,l.finished_at
FROM ranked f JOIN ranked l ON l.bill_id=f.bill_id AND l.last_rank=1
LEFT JOIN first_customer c ON c.task_id=f.task_id
WHERE f.first_rank=1;
