# Adtek template 0.1.0

先读 README.md 与 docs/README.md。模板为独立工程，不读取其他项目、数据库或秘密。

- 模块方向为 server → system/equipment → framework → common；跨模块通过端口，禁止访问别的业务 Mapper。
- 保持 Controller/Service/impl/Mapper/Entity/DTO/Query/VO/MapStruct 分层，权限在服务用例验证。
- MySQL 是业务和任务最终状态，Redis 仅会话与入口预算。成功审计与业务同事务。
- React 函数组件、严格 TypeScript、TanStack Query；业务 ID 和版本使用精确字符串。
- 格式化 tools/format.ps1 后执行 -Check；已发布迁移不可改写。
- 测试使用临时 Testcontainers 或本工程隔离环境；不得连接业务库执行破坏性测试。
- 不提交秘密、数据、构建缓存。报告实际执行结果，远程 CI 不得冒充通过。用户已明确第五步按指南交付，学习练习仅供自查，不要求另一位人员执行或留下记录。
