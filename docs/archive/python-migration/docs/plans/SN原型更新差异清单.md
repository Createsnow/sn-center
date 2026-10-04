# SN 原型更新差异清单

日期：2026-10-04；状态：功能补迁本地验收通过，资料归档待步骤6。来源/目标提交及范围见[范围决定](SN补迁范围与规则决定.md)。93 文件逐项归属清单位于 `../reports/evidence/20261004-incremental/source-delta.json`。

下表数量是关联测试套件数量，不能逐行相加。完整后端尝试及两项打包入口复核分别记录，最终浏览器17项通过。统一结果与661文件摘要见[验收记录](../reports/2026-10-04-SN迁移总结与验收记录.md)。

| ID | 原型来源 | 目标位置 | 差异/处理 | 可执行验收场景 | 状态 |
| --- | --- | --- | --- | --- | --- |
| D01 | services/order_sync_schedule.py、routes/orders.py、Orders.vue、test_orders.py | OrderService/OrderPublishService、OrdersPage、新增调度组件及 SQL | 缺少持久开关、10—10080 分钟间隔、下次时间与最近结果；新增执行权与当前权限复核 | 开关/改间隔、到期双执行者、ERP失败、停用/撤权、失效执行者不发布、重启恢复 | 已实现；Foundation25与浏览器初批已验证，最终17项浏览器通过 |
| D02 | services/orders.py、Orders/Generate.vue、test_orders.py | OrderServiceImpl、OrderCandidateMapper、GenerationOrderPicker | 客户关键词、待生成/待分配筛选、PI 汇总候选缺口；保持精确分录额度，不照搬 FLOOR 数量 | 自有厂和跨厂候选；已生成未分配仍待办；分配后排除；小数订单禁止生成 | 已实现；Foundation25含候选/精度/权限验证，最终17项浏览器通过 |
| D03 | routes/orders.py、services/orders.py | OrderServiceImpl.lines/customers、SnDataPermissionRules | 已按同权限工厂范围限制分录和客户；404 代替旧越界提示属于既有防泄露契约 | 绑定用户读其他厂详情和候选不泄漏；总部全范围读取 | 复用；Foundation25/Work28权限场景通过 |
| D04 | services/rules.py、generate.py、rules_init tests、Generate.vue | RuleServiceImpl、SnWorkEngine/Service、GeneratePage | 无绑定 PI 首次规则选择和沿用与原通用回退不同 | 无选择拒绝；预演不绑定；成功首次生成绑定；并发其他选择导致预演失效；显式 PI 绑定优先 | 已实现V19；Work28与17→19升级通过，最终17项浏览器通过 |
| D05 | generate.next_start、pi_init.py、transfers.py、test_generate.py | SnWorkEngine、TransferEngine、预演/转厂 VO 和 React 页 | 转入及原未解析导入按当前规则反解最大号后避让；起号提示与预演失效 | 连续/不连续/不同格式转入、首次手工起号、批准前后提示；完整 SN 冲突整批回滚 | 已实现；Work28/Transfer13覆盖避让、回滚、预演与提示权限 |
| D06 | generate.recover_interrupted、test_generate.py | TaskCoordinator/TaskWorker、SnWorkEngine、SnWorkIT/DurableTaskIT | 原型启动失败回收；新工程原任务代次接管与同事务成功更完整，保留恢复契约 | 活跃工作者不被错误回收、迟到代次拒绝、成功与 SN 同事务、强杀后原号恢复 | 复用；事务/代次场景通过，当前验收JAR强杀恢复通过 |
| D07 | login_guard.py、auth.py、test_accounts.py | AuthenticationServiceImpl、认证防护组件、三语错误 | 现有请求速率限制不等于连续失败锁；增加跨实例失败计数、5 次/15 分钟、成功清零、未知账号失败审计 | 第5次仍401，第6次包括正确密码429；时间到期恢复；成功清零；未知账号审计无密码 | 已实现；Authentication23与初批三语浏览器通过 |
| D08 | acquire.py、Acquire.vue、test_acquire_transfer.py | AcquireEngine、PrintHandlers、AcquirePage | 已有固定成员、整 PI 操作、同操作号打印重放；核对等待和禁用反馈 | 并发同号只一份打印；文件恢复不重复写 PRINTED；转厂后旧固定成员可追溯 | 复用；Acquire15固定成员、重放与文件恢复通过 |
| D09 | files.py、utils.py、test_unit/test_acquire_transfer.py | SnAuditFiles/SnQueryFiles/PrintFiles、Query/Acquire 页面 | 沿用v1.1：CSV只防护自由文本、业务编码不加字符；XLSX显式文本，区别于原型对所有字符串加前缀的做法 | 危险备注带引号/控制前缀；XLSX 文本单元格；下载权限撤销 | 复用；Acquire15/Query8/Audit10格式、安全与撤权验证通过 |
| D10 | schemas/sn.py、transfers.py、rules/pi_init tests | DTO 校验、CommandValidator、SN Service | 目标PI字段与DTO原已为128字符（原型64），保留目标已发布契约；按各自上限映射过长输入场景 | 按目标既有128字符PI契约验证129拒绝且零写入、128成功；转厂字段超长返回校验错误 | 复用并补证；Work28的128/129与Transfer13超长零写入通过 |
| D11 | core/config.py、main.py、scheduler.py、users.py、audit.py | 安全配置、任务与审计底座、启动工具 | 沿用 JWT 秘密长度与隔离配置；新调度显式启用，不把原型内存保护复制为生产状态 | 无配置启动拒绝；测试仅隔离设施；调度未启用不访问 ERP | 复用安全底座；默认不触发ERP和秘密长度/身份检查通过 |
| D12 | V1__init.sql、db/mysql.py/session.py、test_unit.py | V18+、LegacySourceContract/旧库工具 | 原型增加表及字段；已应用 SQL 不改，旧来源白名单严格拒绝未知结构 | V1—V17 SHA 不变；旧工具不声称新 schema 兼容；新空库及17升级均验证 | V18/V19已实现；17→19升级1、Runtime6及冻结17份SHA核对通过 |
| D13 | frontend API、常量、三语、Login/Orders/Generate/Acquire/Transfer | frontend/src/api、i18n、对应 React 页面 | 按 D01/D02/D04/D05/D07 同步实际类型、交互和三语；契约生成而非手写 | 浏览器完整业务流程、三语提示、禁用/等待/失败、实际下载 | 三语与生成契约已更新；前端46通过，最终17项浏览器通过 |
| D14 | 原型 README、设计文档、图/文档生成工具、已删除旧DB说明 | 当前业务/架构文档及迁移归档索引 | 参考资料而非业务缺口，不移植 Python 文档构建器和 Vue 配置 | 全文件归属核对；原件只读；现行说明与最终实现一致 | 93文件均已归属；当前补迁契约已更新，最终归档仍待步骤6 |

运行报告将分别列出编译、单元、真实 MySQL/Redis、浏览器、外部联调、容量和人工验收，不能用历史报告数量关闭以上项目。
