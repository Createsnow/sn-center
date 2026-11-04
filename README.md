# Adtek Platform Template 0.1.0

Adtek 新项目的工程模板：React / Spring Boot 前后端同仓，模块化单体，MySQL + Redis。模板**只包含技术架构、规范、技术栈、工程要求与代码结构**，不包含业务功能代码；新项目在此骨架上按规范开发。

## 包含内容

- **后端骨架**：Maven 多模块 `common → framework → system / sample → server`，每个分层包附 `package-info.java` 说明职责；`ArchitectureTest` 强制模块边界、分层方向、命名后缀与 Mapper 规则。
- **前端骨架**：React 19 + TypeScript strict + Vite + Ant Design + TanStack Query + i18next（zh-CN/en/vi）的应用外壳与目录约定。
- **工程化**：锁定的依赖与工具版本、格式化与检查（Spotless、Checkstyle、Enforcer、ESLint、Prettier、PSScriptAnalyzer）、验证脚本、GitLab CI、镜像与本地 MySQL/Redis 设施、新项目复制脚本。
- **文档**：架构、技术栈、代码结构、开发规范、风格、工程要求。

## 快速开始

```powershell
./tools/bootstrap-build.ps1
./tools/bootstrap-format.ps1
./tools/pnpm.ps1 install --frozen-lockfile
./tools/format.ps1 -Check
./tools/verify.ps1
./tools/verify-web.ps1
node tools/check-docs.mjs
```

本地设施（需要 Docker Desktop Linux 引擎）：

```powershell
./tools/infra.ps1 start
./tools/check-infra.ps1
./tools/start-web.ps1
```

创建新项目：

```powershell
./tools/new-project.ps1 -Destination ../my-project -ProjectName my-project
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
