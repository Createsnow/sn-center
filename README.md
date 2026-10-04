# Adtek SN 中心

生产序列号管理系统，使用 Java 21 / Spring Boot / React / MySQL。支持订单同步、规则与首次选择、PI 初始化、生成与整单分配、领取打印、转厂、查询及审计。SN 新需求、修复和回归统一在本工程进行，Python 原型原库保留用于追溯。

2026-10-04 确认范围的功能补迁已通过本地验收。历史数据按用户决定不搬迁；生产切换尚未就绪。范围、准确版本和未执行事项见[本地交付](docs/releases/2026-10-04本地交付.md)。

## 日常启动

PowerShell 7，工作目录为本工程。已有开发环境使用当前管理员密码，不重新 bootstrap；初始密码文件不会随改密更新。

```powershell
./tools/infra.ps1 start
./tools/check-infra.ps1
./tools/start-test.ps1
# 另一个终端
./tools/start-web.ps1
```

前端 `http://127.0.0.1:15173`，API `http://127.0.0.1:18080`，MySQL/Redis 为回环端口 33306/36379。本轮未重启常驻环境，开发与 HTTPS 库仍是 V17；当前源代码包含 V18/V19。更新运行产物前，按[运维说明](docs/operations/部署与运维.md)完成备份、升级安排与恢复检查。

## 开发与使用

- [文档导航](docs/README.md)：架构、业务、开发、测试、运维。
- [用户手册](docs/business/用户手册.md)与[业务接口](docs/business/接口契约.md)。
- [开发约定与模板 0.1.0](docs/development/README.md)、[测试入口](docs/testing/README.md)、[命令手册](docs/operations/命令手册.md)。
- [当前待办与验收边界](docs/releases/待办与验收边界.md)：真实联调、容量、人工验收和生产条件。
- [迁移归档索引](docs/archive/python-migration/README.md)：来源、规则决定、历史报告、原始证据及文件摘要。

秘密、真实数据、备份与构建缓存不入库。所有本轮提交和标签仅在本地，未访问或更新 GitLab。
