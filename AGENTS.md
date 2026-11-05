# Adtek template 0.1.0

先读 README.md 与 docs/README.md。模板为独立工程，只含架构、规范与代码结构，不读取其他项目、数据库或秘密。

- 模块方向为 server → system/业务模块 → framework → common；业务模块之间无编译期依赖，跨模块只经 framework 端口（`*Port`，实现放提供方 service.impl 并有契约测试），禁止访问别的模块 Mapper 与 service.impl。
- 修改一个业务只改其后端模块与同名前端功能目录；全局扩展点只在 framework/server；路由在 `/api/v1/{module}/` 与 `/{feature}` 下。boundary 门禁不得删除或放宽。
- 保持 Controller/Service/impl/Mapper/Entity/DTO/Query/VO/MapStruct 分层，权限在服务用例验证；ArchitectureTest 不得删除或放宽。
- 每个模块只访问以 `{module}_` 为前缀的自有表，迁移放本模块 `db/migration/{module}/`，跨模块数据经 framework 端口；不跨模块联表。
- MySQL 是业务和任务最终状态，Redis 仅会话与入口预算。成功审计与业务同事务。
- React 函数组件、严格 TypeScript、TanStack Query；业务 ID 和版本使用精确字符串；文案三语同步。
- 不使用 PowerShell 脚本：Windows 用 tools\*.bat，Linux 用 deploy/ 下 Compose 与 sh。格式化 tools\format.bat 后执行 tools\format.bat check；已发布迁移不可改写。
- 容器保持无状态、配置走环境变量、秘密走 /run/secrets、API 不持有 DDL 账号，以便直接扩展到 K8s。
- 测试使用临时 Testcontainers 或本工程隔离环境；不得连接业务库执行破坏性测试。
- 不提交秘密、数据、构建缓存。报告实际执行结果，远程 CI 不得冒充通过。
