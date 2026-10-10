# adtek-platform-sn 共同工作入口

先读 [README](README.md)、[当前状态](memory/项目状态.md)、[文档导航](docs/README.md)和任务相关正文。SN 新需求、缺陷与回归统一进入本工程。

- 用户确认本轮仅新建隔离测试数据；相邻 Python 原型、原库、秘密和历史备份保持不变，不执行旧数据搬迁。
- 通用规范引用 [模板 0.1.0 及 SN 差异](docs/development/README.md)；三份 v1.1 原文在 [归档](docs/archive/python-migration/README.md)，已批准的业务变更见当前 SN 补迁契约。
- 六模块方向 server → system/sn → framework → common，integration 实现 sn 的 service.port；禁止跨模块 Mapper/impl 引用。
- 保持 Controller/Service/impl/Mapper/Entity/DTO/Query/VO/MapStruct 分层；可信身份与当前权限在服务用例检查。
- MySQL 保证 SN 双唯一性、额度、固定成员、事务和任务终态；Redis 不承担业务最终状态。完整 SN 冲突整批回滚。
- React 函数组件、strict TypeScript、TanStack Query；业务 ID、版本、数量与流水保留精确字符串。
- V1—V19 已交付，V20—V37 已在独立一致性比较库应用，均保持原字节；新增结构取下一个未占用版本（当前 V38）。不要因已有常驻库仍为 V17 而改写历史迁移。
- 修改代码后执行 tools/format.ps1 并以 -Check 核对；禁止格式化冻结 SQL、来源基线、历史证据、归档原文或手改生成类型。
- 验收脚本使用独立 acceptance JAR；同仓库不并行运行两个 Maven。只运行隔离测试，保护已有服务和账号。
- 本轮仅本地交付；不访问或更新 GitLab，不将本地检查称为远程 CI 或生产验收。秘密与备份不入库。
- memory 只记录当前状态；正式证据在当前发布记录和只读归档。真实联调和生产待办不得隐藏或冒充通过。用户已明确第五步按指南交付，不要求另一位人员执行学习练习或留下记录。
