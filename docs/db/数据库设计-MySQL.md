> **已被取代，请勿参照**：本文与 `mysql/V1__init_schema.sql` 是按旧版「需求确认表」（Q1–Q14）做的方案稿，未接入应用；文中的表名、字段、状态、「现有 SQLite」等描述均与当前代码不符。
> 应用实际使用的表结构见 [数据库表设计](../数据库表设计.md)，建表脚本为 `app/db/migration/V1__init.sql`，按《需求说明（修订版）》实现。主要差异：
> 领取按「工厂 + PI」整批（本文为按工单分次领取）；状态为 待分配 / 待领取 / 待打印 / 已打印 / 申请中；
> 转厂**不抬高**转入 PI 的计数器、流水归属不变（本文为抬高）；额度来自生产订单快照（不同步销售订单）；
> SN 明细按月分区，同 PI 查重由 `sn_key` 护栏表保证。本文保留作设计参考，可按需删除。

# 数据库设计（MySQL 8.0）

建表脚本：[`mysql/V1__init_schema.sql`](mysql/V1__init_schema.sql)（文件名按 Flyway 规范，后续接入时直接放进 `db/migration`）。

本设计替代现有 SQLite 演示库，针对上线前需求分析中发现的问题（领取数据源是演示数据、不能分次领、转厂后 PI 卡死、额度靠 COUNT(*) 等）重新组织。凡是依赖尚未确认业务口径的地方，都标了 **Qn**，对应文末"待确认项"。

## 1. 约定

| 项 | 约定 |
| --- | --- |
| 版本 / 引擎 | MySQL 8.0.16+（需要 CHECK 约束），InnoDB |
| 字符集 | 表默认 `utf8mb4_0900_ai_ci`；**SN、PI、各类编码列用 `utf8mb4_bin`**，按字节精确比较，大小写不同视为不同号 |
| 时间 | `DATETIME(3)`，存 Asia/Shanghai 本地时间，由应用写入 |
| 外键 | 主数据小表建外键；`sn_item`、`sn_audit` 这类大表不建，完整性由应用事务保证 |
| 枚举 | VARCHAR 存英文码，取值写在列 COMMENT；`sn_item.status` 另加 CHECK |
| 业务单号 | 自增 `BIGINT` 做主键，另有可读单号 `*_no`（如 `LOT-20260928-XXXXXXXX`）做唯一键 |

## 2. 表清单

| 分组 | 表 | 说明 | 预估量级 |
| --- | --- | --- | --- |
| 主数据 | `sn_factory` | 工厂 = 金蝶生产组织，编码用组织编码 | 十级 |
| | `sn_customer` | 客户，不参与查重 | 千级 |
| | `sn_user` | 账号（工号），含登录锁定、强制改密、令牌作废版本 | 百级 |
| | `sn_rule` | 编码规则，改规则 = 新增一版 | 百级 |
| ERP 镜像 | `sn_erp_prd_mo` | 金蝶生产订单明细，**生成额度和领取工单的唯一来源** | 十万级/年 |
| | `sn_erp_sale_order` | 金蝶销售订单明细，只用来判断 PI 存在且未关闭 | 十万级/年 |
| | `sn_sync_log` | 每次同步的结果 | |
| PI 与额度 | `sn_pi` | PI 与 PI 级流水计数器（查重边界） | 万级/年 |
| | `sn_mo_quota` | 工单额度桶：计划数 / 已生成 / 已分配 / 已领取 / 已打印 | 十万级/年 |
| 号池 | `sn_lot` | 生成批次 | |
| | `sn_item` | **一枚 SN 一行** | **≈5000 万/年** |
| | `sn_reserved_range` | 永不复用的封存号段（历史导入、跳号） | |
| | `sn_import` | 上线历史号导入批次 | |
| 流转 | `sn_issue` | 总部确认分配 | |
| | `sn_acquire` | 领取（=激活）批次 | |
| | `sn_print` | 打印（=出货）批次，含补打 | |
| | `sn_transfer` | 转厂申请与结果（待审批 / 已转移 / 驳回 / 撤回） | |
| 审计 | `sn_audit` | 成功失败都记，**按月分区** | 百万级/年 |
| 系统 | `sn_config` | 单次生成默认上限、审计保存期、登录锁定阈值等 | |

### 关系

```
sn_customer 1─n sn_pi 1─n sn_item
                  │  └─n sn_mo_quota 1─1 sn_erp_prd_mo n─1 sn_factory
                  └─n sn_reserved_range
sn_item.lot_id      → sn_lot        （生成）
sn_item.issue_id    → sn_issue      （分配）
sn_item.acquire_id  → sn_acquire    （领取）
sn_item.mo_line_id  → sn_erp_prd_mo （领取时绑定的工单，转厂随号走）
sn_item.print_id    → sn_print      （打印）
sn_item.transfer_lock_id → sn_transfer（待审批中）
```

## 3. 关键设计

### 3.1 号池 `sn_item`

- **聚簇主键 `(pi_id, seq_dec)`**：同一 PI 内流水天然唯一，号段查询（领取、转厂、导出）走主键范围扫描。
- **唯一键 `(pi_id, sn)`**：同一 PI 内完整 SN 不重复。不同 PI 允许同串（需求第 1 条"只考虑 PI 维度"）。
- `pi_id` 用 `sn_pi` 的自增代理键代替 64 字节的 PI 全称，主键和所有二级索引都更短。
- `ix_item_sn (sn)`：按 SN 全局反查（扫码追溯），不需要知道 PI。
- 状态只留 `LEGACY / GENERATED / ISSUED / ACQUIRED / PRINTED / VOIDED`。`ACTIVATED`、`SHIPPED` 只在接口层作为别名，不入库。
- `src_mo_line_id`（生成时占用哪个工单额度）与 `mo_line_id`（领取时绑定哪个工单）分开：前者转厂不变，保证额度不会因转厂被"退回"而超额生成；后者随号走（Q6）。

### 3.2 额度与并发

现有实现每次都 `COUNT(*)` 号池来算剩余额度，5000 万行后不可用。改为：

- `sn_pi.last_seq_dec`：PI 计数器。
- `sn_mo_quota`：每个工单明细一行，维护 `plan_qty / generated_qty / issued_qty / acquired_qty / printed_qty`，与 `sn_item` 在同一事务里增减，CHECK 保证不为负。

**固定加锁顺序**（避免死锁）：`sn_pi` → `sn_mo_quota`（按 `mo_line_id` 升序）→ `sn_item`。

| 动作 | 事务内步骤 |
| --- | --- |
| 生成 | `SELECT … FROM sn_pi WHERE pi_id=? FOR UPDATE` → 锁额度行、校验 `generated_qty + n ≤ plan_qty` → 批量插入 `sn_item` → 推进 `last_seq_dec`、`generated_qty` |
| 分配 | 锁额度行 → 校验目标工厂 = 额度行的 `factory_code` → 改 `sn_item` 状态 → `issued_qty += n` |
| 领取 | 锁额度行 → 校验 `acquired_qty + n ≤ plan_qty` → `SELECT … FOR UPDATE SKIP LOCKED` 取 n 枚 ISSUED → 更新 → `acquired_qty += n` |

多实例部署也成立，不再依赖进程内写锁。建议定时任务每天用 `COUNT(*)` 对账一次额度表。

### 3.3 ERP 镜像

- 以金蝶明细内码 `k3_entry_id` 为同步主键做 upsert，**不删行**；ERP 中消失的行置 `erp_present=0`。原因：号池引用了工单行，删掉会让追溯断链。
- 所有状态都同步，业务按 `erp_status` 过滤（生成只认 1/2，领取认哪些状态待定 Q2）。
- `mo_key` 是 MES 使用的"生产订单+序号"（Q3）。
- 原来的 `sn_mo`（演示 MES 工单）和 `sn_prd_mo` 合并到 `sn_erp_prd_mo`，生成和领取用同一份数据。

### 3.4 转厂

- `sn_transfer` 一张表记申请和结果，状态为 PENDING / APPROVED / REJECTED / CANCELLED。总部直转也记一条 APPROVED。
- 申请时把区间内 `sn_item.transfer_lock_id` 置为申请 id，驳回 / 撤回 / 通过后清空。不再用"号段首尾相同"判断锁，避免重叠申请。
- 贸易转移（换 PI）时 `sn_item.pi_id` 随之改变；目标 PI 的 `last_seq_dec` 同事务内抬到 `MAX(原值, end_seq_dec)`，杜绝现有"转入后该 PI 再也生成不了"的问题。是否允许换 PI、换客户待定（Q5）。

### 3.5 审计 `sn_audit`

- 按月 `RANGE COLUMNS(created_at)` 分区，主键 `(audit_id, created_at)`。保存期到期直接 `DROP PARTITION`，不做大 DELETE。需要一个月度任务预建下月分区（`REORGANIZE PARTITION pmax`）。
- 同时记 `pi_id + start_seq_dec + end_seq_dec`，"某枚 SN 的全部操作记录"用区间反查（`ix_audit_pi_range`），不依赖 payload 里的前 20 个 SN。
- 区分 `factory_code`（操作人厂区）和 `target_factory`（被操作厂区），厂区可见范围直接按这两列过滤。

### 3.6 账号安全

`sn_user` 增加 `must_change_password`、`failed_login_count`、`locked_until`、`token_version`（改密 / 停用 / 改角色后旧令牌立即失效）、`account_type`（MES 用 SERVICE 服务账号，角色 `mes_client`）。初始管理员由部署脚本创建并强制改密，**不再在代码里种演示账号**。

## 4. 容量

在本机 MySQL 8.0.46 上实测：1,000,000 枚 SN 单事务插入约 34 秒（批量插入，含全部索引），占用数据 117 MB + 索引 199 MB，**约 330 字节/枚**。

| 规模 | sn_item 占用 |
| --- | --- |
| 1 个月（400 万枚） | ≈ 1.3 GB |
| 1 年（≈5000 万枚） | ≈ 16 GB |
| 3 年 | ≈ 48 GB |

单表可以支撑。需要注意：

- 单次生成 1 万枚为一个事务，量级合适；更大批次要分批提交。
- 分页查询避免深 `OFFSET`，改为按 `(pi_id, seq_dec)` 游标翻页。
- SN 模糊查询（`LIKE '%x%'`）无法走索引，界面改为精确或前缀匹配。
- 超过 3 年的 PRINTED 号是否归档到历史表，届时再评估。

## 5. 已验证

在 MySQL 8.0.46 上执行建表脚本并测试：

- 19 张表全部创建成功，中文注释存储正常。
- 同 PI 重复流水、同 PI 重复 SN → 被主键 / 唯一键拒绝；不同 PI 相同 SN、大小写不同的 SN → 允许。
- 非法状态、额度为负 → 被 CHECK 拒绝。
- 同厂同幂等键重复领取 → 被拒绝；幂等键为空可多次领取。
- 审计按月 `DROP PARTITION` 清理正常。
- 执行计划：按工厂+PI+状态取号走 `ix_item_factory_pi_status`；号段查询走主键范围；按 SN 反查走 `ix_item_sn`（覆盖索引）；审计区间反查走 `ix_audit_pi_range`。

## 6. 与现有 SQLite 表的对应

| 现有表 | 新表 | 变化 |
| --- | --- | --- |
| `sn_item` | `sn_item` | PI 全称改为 `pi_id`；新增 `issue_id / acquire_id / mo_line_id / print_id / transfer_lock_id / src_mo_line_id`；去掉 `ACTIVATED / SHIPPED` 状态 |
| `sn_pi_counter` | `sn_pi` | 增加历史导入起始号（只允许设置一次）、`item_qty` |
| `sn_prd_mo` + `sn_mo` | `sn_erp_prd_mo` + `sn_mo_quota` | 合并为一份工单数据，额度单独成表 |
| `sn_erp_order` | `sn_erp_sale_order` | 改为真实同步，增加关闭状态 |
| `sn_acquire` | `sn_acquire` | 去掉"同一工单只能领一次"的唯一约束；增加渠道 |
| `sn_transfer` + `sn_transfer_request` | `sn_transfer` | 合并；增加驳回 / 撤回 |
| `sn_skip` | `sn_reserved_range` | 增加类型 |
| `sn_audit` | `sn_audit` | 分区；增加区间反查列、目标厂区 |
| `sn_meta` | `sn_config` | |
| — | `sn_print`、`sn_sync_log` | 新增 |

## 7. 待确认项对表结构的影响

表结构已经按下列**默认假设**设计。改口径时多数只改应用逻辑；标"改表"的会影响结构。

| # | 问题 | 当前默认假设 | 改口径是否要改表 |
| --- | --- | --- | --- |
| Q1 | 单次 / 累计上限 | 不设累计上限；单次默认 1 万，超过需二次确认（`sn_lot.over_limit_ack`、`sn_config`） | 否 |
| Q2 | 领取允许的工单状态 | 待定，按 `erp_status` 过滤 | 否 |
| Q3 | MES 工单标识 | `mo_key` = 单据编号 + "-" + 序号 | 否（只改拼接规则） |
| Q4 | 同一工单分次领取 | 允许多次、任意数量 | 否 |
| Q5 | 转到其他 PI / 其他客户 | 表支持；应用层可禁用 | 否 |
| Q6 | 已领取号转厂后的绑定 | 保持已领取，`mo_line_id` 改为转入厂工单（`sn_transfer.to_mo_line_id`） | 否 |
| Q8 | 历史号导入方式 | 两种都支持（`sn_import.kind`） | 否 |
| Q9 | 规则"起始流水"是否生效 | 保留列，待定 | 否 |
| Q10 | "ERP 已关闭"看哪张单 | 同步销售订单关闭状态 | 若看生产订单结案则 **可不建 `sn_erp_sale_order`** |
| Q12 | 工厂只读能看的 PI 范围 | 应用层控制 | 否 |
| Q14 | SSO | `password_hash` 可空，预留 | 接 SSO 时可能加外部账号列 |
| — | 一个账号绑多个厂区 | 一人一厂（`sn_user.factory_code`） | **改表**：要拆出用户-厂区关联表 |
