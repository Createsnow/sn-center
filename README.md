# Adtek Platform Template 0.1.0

Adtek 新项目的工程模板：React / Spring Boot 前后端同仓，模块化单体，MySQL + Redis。模板**只包含技术架构、规范、技术栈、工程要求与代码结构**，不包含业务功能代码；新项目在此骨架上按规范开发。

## 包含内容

- **后端骨架**：Maven 多模块 `common → framework → system / sample → server`，每个分层包附 `package-info.java` 说明职责；`ArchitectureTest` 强制模块边界、分层方向、命名后缀与 Mapper 规则。
- **前端骨架**：React 19 + TypeScript strict + Vite + Ant Design + TanStack Query + i18next（zh-CN/en/vi）的应用外壳与目录约定。
- **工程化**：锁定的依赖与镜像版本、格式化与检查（Spotless、Checkstyle、Enforcer、ESLint、Prettier）、Windows 批处理脚本、Linux Compose 部署、GitLab CI、新项目复制脚本。不使用 PowerShell。
- **文档**：架构、技术栈、代码结构、开发规范、风格、工程要求。

## 快速开始（Windows 命令行）

前提：JDK 21、Maven 3.9.9+、Node 24、Git、Docker Desktop（Linux 引擎）。在 `cmd` 中、工程根目录执行：

```bat
tools\setup.bat
tools\verify.bat
tools\infra.bat start
tools\migrate.bat
tools\start-api.bat
```

另开一个命令行窗口执行 `tools\start-web.bat`，浏览器打开 `http://127.0.0.1:15183`。

## Linux 部署（Docker Compose，K8s 就绪）

API、Web、迁移各为独立单进程容器；MySQL/Redis 可由 `compose.infra.yml` 提供或使用外部服务。先在构建机或 CI 完成验证得到 JAR 与 `frontend/dist`，然后：

```bash
sh deploy/init-secrets.sh
cp deploy/app.env.example deploy/app.env
docker compose -f deploy/compose.infra.yml -f deploy/compose.yml up -d --build --wait
```

Web 默认监听 `127.0.0.1:18080`，由 TLS 反向代理对外提供 HTTPS。K8s 映射与约定见[工程要求与命令](docs/06-工程要求与命令.md#linux-部署docker-compose)。

## 创建新项目

```bat
tools\new-project.bat --destination ..\my-project --name my-project
```

## 文档

- [文档导航](docs/README.md)
- [技术架构](docs/01-技术架构.md)
- [技术栈与版本](docs/02-技术栈与版本.md)
- [代码结构](docs/03-代码结构.md)
- [开发规范](docs/04-开发规范.md)
- [代码风格与格式](docs/05-代码风格与格式.md)
- [工程要求与命令](docs/06-工程要求与命令.md)
- [开发自查清单](docs/开发自查清单.md)
