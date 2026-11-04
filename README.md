# Adtek Platform Template 0.1.0

用于启动 Adtek 新项目的独立 React / Spring Boot 工程。包含登录与会话、用户/角色/部门/菜单/字典、数据范围、操作审计、可恢复导出任务和设备台账样板。没有默认账号或密码。

设备样板支持分页查询、详情、新增、编辑、部门/本人范围、唯一编码、版本冲突与同事务审计。外部系统集成（ERP/MES 等）和旧库导入不在模板装配中。

## 开始使用

Windows PowerShell 7、Node 24、Git、Docker Desktop 的 Linux 引擎、Edge。所有命令在本工程根目录执行，脚本也支持从其他目录调用。

```powershell
./tools/bootstrap-build.ps1
./tools/bootstrap-format.ps1
./tools/pnpm.ps1 install --frozen-lockfile
./tools/verify.ps1 -Integration
./tools/check-contract.ps1
./tools/verify-web.ps1
./tools/verify-browser.ps1
./tools/infra.ps1 start
./tools/check-infra.ps1
./tools/bootstrap-admin.ps1 -Username admin
./tools/start-test.ps1
# 另一个 PowerShell 窗口
./tools/start-web.ps1
```

浏览器打开 `http://127.0.0.1:15183`。首次密码在本工程受保护文件 `deploy/.secrets/bootstrap-admin-password`，首次登录必须改密。不要把该文件内容粘贴到日志或提交。已有账号时 bootstrap 会拒绝重新初始化。

本地隔离端口：MySQL 33316、Redis 36389、API 18090、Web 15183。测试数据库 `adtek_template_test`，Compose 项目 `adtek-platform-template-test`。集成/浏览器测试另建临时容器，不使用该开发库。

## 本地 Docker 部署

完成上述工具准备与前后端验证后，将 API、Web、MySQL、Redis 一起运行在 Docker 中：

```powershell
./tools/build-images.ps1
./tools/local-docker.ps1 start
./tools/local-docker.ps1 status
# 停止并保留数据卷
./tools/local-docker.ps1 stop
```

访问 `https://127.0.0.1:18453`。本机自签名证书会触发浏览器信任提示；证书包含 localhost 和 127.0.0.1，脚本不修改系统信任库。首次初始化账号为 `admin`，密码保存在 `deploy/.secrets/bootstrap-admin-password`，首次登录必须改密。

此入口合并 `deploy/compose.test.yml` 与 `deploy/compose.local.yml`，复用本工程隔离数据库和 Redis，先迁移、空库初始化管理员，再等待四个服务健康。重复启动保留既有账号、凭据和数据，API 仅在容器网络内开放。不要同时运行宿主机 API；停止全容器环境使用 `local-docker.ps1 stop`。更新源码后重新执行前后端验证、镜像构建与 `start`。

## 创建项目与继续开发

使用干净的模板发布 checkout：

```powershell
./tools/new-project.ps1 -Destination ../adtek-equipment-training -ProjectName adtek-equipment-training
```

新项目只复制受版本控制的文件，生成 `template-origin.json`，使用新端口和新数据库，不复制账号、密钥、缓存或 Git 历史。操作说明见[创建指南](docs/02-新项目创建指南.md)。新项目中的端口以 `template-origin.json` 和实际配置为准。

- [文档导航](docs/README.md)
- [框架规范](docs/01-开发框架规范.md)
- [开发人员学习指引](docs/03-开发人员学习指引.md)
- [设备业务样例](docs/04-业务开发样例.md)
- [命令与问题排查](docs/05-常用命令与问题排查.md)
- [能力边界与版本继承](docs/06-能力边界与版本.md)
- [开发自查清单](docs/开发自查清单.md)

GitLab 流水线（`.gitlab-ci.yml`）与本地脚本执行同一组门禁；runner 与部署环境由各项目自行配置和验收。
