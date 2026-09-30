# adtek-platform-sn 共同工作入口

用户已授权按照三份 v1.1 基线开始迁移并创建本工程。项目名以用户指定的 `adtek-platform-sn` 为准，替代迁移方案中的建议目录名。

先读 [项目状态](memory/项目状态.md)、[文档导航](docs/README.md) 和任务相关正文。有效来源为 `docs/references/baselines/` 中的三份 v1.1 来源快照；开发约束和引用方式见 `docs/05-开发与协作规范.md`。

- 保留相邻 `sn-center-python`、`bak` 和工作区根目录三份基线原件，只在本工程修改。
- Java 使用 `com.adtek` / `adtek-*` 六模块；遵守 server → system/sn → framework → common，integration 实现 sn 的 service.port。
- 使用 controller/service/service.impl/mapper/domain/dto/query/vo/convert，MapStruct、Result、BusinessException；domain 为 Entity。
- 主体可信装配、服务用例授权；数据库保证 SN 双唯一性、额度与事务，Redis 不承担业务最终状态。
- React 函数组件、TypeScript strict、TanStack Query 服务端状态和最小 Zustand 状态；业务 ID/流水保持精确字符串。
- 注释按职责与风险覆盖；公开 Service/端口有真实契约，不虚构实现、测试、作者或决定。
- 代码生成或修改后运行tools/format.ps1，再用-Check检查；Java/POM由父POM的Spotless统一，前端沿用Prettier，PowerShell使用固定PSScriptAnalyzer规则。禁止格式化冻结SQL、来源基线、历史证据或生成契约。
- 只运行明确隔离的测试设施；禁止连接业务库做破坏性测试、提交密钥。用户于2026-09-30明确授权从相邻sn-center-python对应功能查找K3连接配置，可只读提取其五个K3连接字段到新工程受保护目录；其他旧配置不导入、不回显，旧工程不修改。
- 新增功能同时交付对应测试、契约及状态。实际编译、单元、集成、浏览器和性能结果分别报告，跳过不当通过。
- 未获授权不提交、推送、发布或部署；原授权范围内的常规实现持续推进，不逐文件重复请示。
- memory 是共同状态，正式证据在 docs/reports；禁止以空页面、模拟成功、TODO 或关闭门禁冒充完成。
