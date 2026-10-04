# SN 开发约定与框架版本

采用 Adtek Platform Template **0.1.0** 的工程约定，规范统一维护在独立模板。模板从本工程经过验证的公共能力整理而来，本轮没有用模板空库 SQL 替换 SN 历史。

- [Adtek 开发框架规范](../../../adtek-platform-template/docs/01-开发框架规范.md)
- [新项目创建指南](../../../adtek-platform-template/docs/02-新项目创建指南.md)
- [开发人员学习指引](../../../adtek-platform-template/docs/03-开发人员学习指引.md)
- [完整设备样板](../../../adtek-platform-template/docs/04-业务开发样例.md)
- [常用命令与问题排查](../../../adtek-platform-template/docs/05-常用命令与问题排查.md)
- [能力边界、v1.1 继承与公共修复方式](../../../adtek-platform-template/docs/06-能力边界与版本.md)

上述链接按同级 checkout 布局提供，仅为文档引用；SN 构建、启动和测试不读取模板文件。单独取得 SN 仓库时另取模板 0.1.0 文档，禁止复制一份漂移的通用规范。原三份 v1.1 快照见[归档](../archive/python-migration/README.md)。

## SN 业务差异

| 项目 | SN 保留的实现 |
| --- | --- |
| 业务模块 | sn + integration；模板的 equipment 不装入 SN |
| 资源资格 | FactoryMembershipPort / factoryIds / allFactories，由 SN 提供工厂归属；模板改为可扩展资源端口并默认空集合 |
| 身份 | USER 与独立 MES SERVICE；模板默认只装配 USER |
| 数据库 | SN 原 V1—V17 + 本轮 V18/V19；模板独立 V1/V2，仅初始化新项目空库 |
| 业务正确性 | PI 占用、双唯一护栏、来源额度、固定成员、预演失效和整批事务 |
| 集成 | K3、MES 显式配置与协议；模板不带这些业务适配器 |
| 运维 | 维护、备份恢复及隔离旧库 CLI 保留；不把兼容工具当作日常初始化步骤 |

修改前按 [当前业务契约](../business/SN补迁契约.md)明确行为，更新 DTO/VO 后从实际启动实例生成 OpenAPI，执行受影响的权限、并发与事务回归。格式、架构、Mapper 行宽门禁保持。迁移脚本不修改既有版本。

接收公共修复时记录来源模板版本/提交、受影响模块和 SN 差异，人工审查后选择性合入；不得覆盖 SN 工厂权限、Flyway 历史或业务协议。模板版本不意味着两个仓库发布同步。
