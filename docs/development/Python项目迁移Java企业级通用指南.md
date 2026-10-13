# Python 项目迁移 Java 技术栈等价替换指南（企业级通用）

> **文档性质**：独立、自包含的迁移手册，不依赖任何特定工程即可使用。
>
> **核心定位**：**只替换技术栈，不新增功能，不改变行为。**Python 系统原来用了什么，就换成对应的 Java 技术；原系统没有的能力本次一律不引入（列入附录 A“可选增强”）。验收标准只有一条：**新系统对外行为与原系统一致**。
>
> **占位约定**：公司包名 `com.company`，应用名 `app`，示例业务“订单（order）”。实施时替换为实际名称。
>
> **版本基线**（2026 年下半年）：**Java 25 LTS**（受平台限制时用 Java 21 LTS）、**Spring Boot 4.1.x**（4.2 正式发布后跟进）。落地时锁定当期最新补丁版本。

---

## 目录

1. [范围与原则](#1-范围与原则)
2. [迁移方式：可随时回退的逐步替换](#2-迁移方式可随时回退的逐步替换)
3. [盘点：原系统技术清单](#3-盘点原系统技术清单)
4. [技术替换对照表](#4-技术替换对照表)
5. [工程骨架（最小依赖）](#5-工程骨架最小依赖)
6. [逐层替换方法与代码](#6-逐层替换方法与代码)
7. [行为一致性专题](#7-行为一致性专题)
8. [认证与权限：按原机制复刻](#8-认证与权限按原机制复刻)
9. [数据库：结构零改动](#9-数据库结构零改动)
10. [后台任务与定时任务：按原机制映射](#10-后台任务与定时任务按原机制映射)
11. [缓存、文件、外部调用、日志、配置](#11-缓存文件外部调用日志配置)
12. [测试：逐接口对照验收](#12-测试逐接口对照验收)
13. [构建与部署：沿用原方式](#13-构建与部署沿用原方式)
14. [切换、回退与下线](#14-切换回退与下线)
15. [差异清单：发现但不在本次修复的问题](#15-差异清单发现但不在本次修复的问题)
16. [常见陷阱](#16-常见陷阱)
- [附录 A：可选增强（不在本次范围）](#附录-a可选增强不在本次范围)
- [附录 B：模板与检查清单](#附录-b模板与检查清单)

---

## 1. 范围与原则

### 1.1 本次做什么、不做什么

| 做 | 不做 |
| --- | --- |
| Python 后端代码用 Java 重写 | 新增业务功能 |
| Python 依赖库替换为 Java 对应库 | 引入原系统没有的基础设施（身份服务、消息队列、K8s、监控平台、对象存储等） |
| 原有后台任务、定时任务、外部集成迁移到 Java | 修改接口路径、字段名、状态码、错误格式、分页结构 |
| 测试改写为 Java，并建立新旧对照测试 | 修改数据库表结构、字段含义、时区语义 |
| 构建、启动脚本替换为 Java 方式 | 修改认证方式、密码哈希算法、权限模型 |
| | 改动前端（前端保持不变） |

### 1.2 七条原则

| # | 原则 | 说明 |
| --- | --- | --- |
| 1 | **行为一致优先** | 同样的请求 + 同样的数据 → 同样的响应和同样的数据库结果 |
| 2 | **按原系统决定技术** | 每项技术都写成“若原来用 X，则替换为 Y；原来没有就不引入” |
| 3 | **缺陷原样保持** | 迁移中发现的旧缺陷登记到差异清单（第 15 节），本次不修。**例外**：安全漏洞和会导致数据损坏的问题，经业务负责人确认后可在本次修复 |
| 4 | **重写而非逐行翻译** | 对外行为一致，内部按 Java 分层规范实现；只改变实现方式、不改变对外结果的写法可以采用（如用数据库唯一约束代替代码判重，前提是返回结果相同） |
| 5 | **可随时回退** | 数据库结构、数据格式、会话、密码哈希都保持 Python 可读写，任何时候都能把流量切回 Python |
| 6 | **证据验收** | 每个接口都要有新旧对照测试结果，不以“代码写完”作为完成标准 |
| 7 | **不应迁移的保留 Python** | 机器学习、重度数据分析、无 Java 等价物的功能，保留为独立 Python 进程，接口不变 |

### 1.3 完成定义

- 盘点清单中每一项都有替换结果：已替换 / Java 内置可替代 / 删除（确认无用）/ 保留 Python；
- 每个接口通过新旧对照测试，差异为零或已登记并确认；
- 后台任务、定时任务在 Java 侧运行，Python 侧已停用；
- 生产流量全部由 Java 处理，观察期内无回退；
- Python 后端下线（保留 Python 的部分除外）。

---

## 2. 迁移方式：可随时回退的逐步替换

### 2.1 基本做法

```mermaid
flowchart LR
    C[客户端<br/>前端不变] --> N[原 Nginx / 网关]
    N -->|已替换的接口| J[Java 应用]
    N -->|未替换的接口| P[Python 应用]
    J --> DB[(原数据库<br/>结构不变)]
    P --> DB
```

1. 复用原有 Nginx（或网关），按路径把接口逐批转到 Java；
2. Java 与 Python **共用同一个数据库**，表结构不变；
3. 每批接口先通过对照测试，再切换路由；出问题时把路由改回 Python 即可回退；
4. 全部接口切换完成并度过观察期后，下线 Python。

只有系统很小、允许停机时，才考虑一次性整体切换（第 14.3 节）。

### 2.2 Nginx 路由示例

```nginx
upstream python_app { server 127.0.0.1:8000; }   # 原 gunicorn / uvicorn
upstream java_app   { server 127.0.0.1:8080; }   # 新 Java 应用

server {
    # ……保持原有 listen、ssl、静态资源等配置不变……

    # 第 1 批：已替换的接口
    location /api/customers/ { proxy_pass http://java_app; }
    location /api/dicts/     { proxy_pass http://java_app; }

    # 其余仍由 Python 处理
    location /api/           { proxy_pass http://python_app; }
}
```

原系统没有 Nginx 时，可在 Python 前临时加一层 Nginx 作为切换工具，迁移结束后按原部署方式保留或移除。

### 2.3 迁移顺序

| 顺序 | 内容 | 原因 |
| --- | --- | --- |
| 1 | 只读查询接口（字典、基础资料、列表、详情） | 风险最低，便于建立对照测试 |
| 2 | 简单写接口（基础资料增删改） | 验证事务和数据一致性 |
| 3 | 核心业务写接口 | 团队熟悉后再迁 |
| 4 | 后台任务、定时任务 | 必须与其生产者/消费者一起切换（第 10 节） |
| 5 | 登录、登出、改密等认证接口 | 共存期由 Python 继续负责认证最简单（第 8.2 节），最后切换 |

同一业务的读写接口如果共享缓存或内存状态，必须同批切换（第 11.1 节）。

---

## 3. 盘点：原系统技术清单

### 3.1 盘点表

逐项填写，每项结论要有依据（文件路径、配置、命令输出）。

| 方面 | 需要查清的事实 | 产出 |
| --- | --- | --- |
| 框架 | Django / Flask / FastAPI 及版本；WSGI/ASGI 服务器 | 框架清单 |
| 接口 | 全部路由、方法、请求参数、响应结构、状态码、错误格式；是否依赖末尾斜杠 | 接口清单（含示例请求/响应） |
| 依赖 | `requirements.txt` / `pyproject.toml` 每一项 | 依赖替换表（3.2 节） |
| 数据访问 | ORM（Django ORM / SQLAlchemy）或裸 SQL；默认排序；事务边界（`ATOMIC_REQUESTS`、`atomic`、手动 commit） | 事务边界清单 |
| 数据库 | 表、列、类型、约束、索引、触发器；`USE_TZ` 和时区设置；字符集与排序规则 | 结构导出 |
| 认证 | 会话还是令牌；会话存储位置（数据库/Redis/签名 Cookie）；Cookie 名称与属性；JWT 算法、密钥、有效期、声明；CSRF 机制 | 认证方案说明 |
| 密码 | 哈希算法、参数、存储格式 | 哈希格式说明 |
| 权限 | 角色判断位置与逻辑；数据过滤条件 | 权限规则清单 |
| 后台任务 | Celery / RQ / 线程等；broker（RabbitMQ/Redis）；队列名；重试；结果存储 | 任务清单 |
| 定时任务 | Celery beat / APScheduler / cron；表达式与时区 | 定时清单 |
| 缓存 | 缓存后端、键、过期时间、失效逻辑 | 缓存清单 |
| 文件 | 上传/导出格式、存储路径、文件命名、编码 | 文件清单 |
| 外部调用 | 地址、认证、超时、重试 | 集成清单 |
| 日志 | 格式、输出位置、轮转 | 日志格式说明 |
| 配置 | 环境变量名、配置文件、默认值 | 配置清单 |
| 国际化 | 是否使用 gettext、语言列表 | 有/无 |
| 管理后台 | 是否使用 Django Admin | 有/无及使用人 |
| 部署 | 启动命令、进程管理（systemd/supervisor/Docker）、端口、健康检查路径 | 部署说明 |
| 测试 | pytest 用例及覆盖的场景；是否有接口级测试 | 场景清单 |

辅助手段：

- 路由：Django `show_urls`（django-extensions）、Flask `flask routes`、FastAPI `/openapi.json`；
- 实际使用情况：从 Nginx 访问日志统计接口调用量，找出无人调用的接口（经确认后可不迁移，属于“删除”，需登记）；
- 真实响应样本：在测试环境或通过日志采集各接口的真实请求/响应，作为对照测试的基准（第 12 节）。

### 3.2 依赖替换表模板

`requirements.txt` 每一项都必须有结论：

| Python 依赖 | 用途 | 结论 | Java 替换 | 备注 |
| --- | --- | --- | --- | --- |
| Django 4.2 | Web 框架 | 替换 | Spring Boot 4.1（Spring MVC） | |
| djangorestframework | 接口序列化/分页 | 替换 | Jackson + 自定义 DRF 兼容分页结构 | 第 6.3 节 |
| mysqlclient | 数据库驱动 | 替换 | mysql-connector-j | |
| celery | 后台任务 | 替换 | Spring AMQP（沿用原 RabbitMQ） | 第 10 节 |
| redis | 缓存 | 替换 | Spring Data Redis | |
| requests | 调用 ERP | 替换 | RestClient | |
| openpyxl | 导出 Excel | 替换 | Apache POI | |
| python-dateutil | 日期解析 | Java 内置可替代 | java.time | |
| gunicorn | 运行服务器 | Java 内置可替代 | 内嵌 Tomcat | |
| pandas | 报表计算 | 替换 / 保留 Python | SQL 聚合；复杂分析保留 Python | 需评估 |
| django-debug-toolbar | 开发调试 | 删除 | — | 仅开发环境 |

结论只能是：**替换 / Java 内置可替代 / 删除（写明原因）/ 保留 Python**。

---

## 4. 技术替换对照表

**使用方法**：先看左列“原系统用的是什么”，只替换实际用到的行；原系统没有用到的行，Java 侧也不引入。

### 4.1 Web 与接口

| 原系统使用 | Java 替换 | 关键点 |
| --- | --- | --- |
| Django views / DRF ViewSet | `@RestController` | 路径、方法、状态码与原接口逐一对应 |
| Flask `@app.route` / Blueprint | `@RestController` + `@RequestMapping` | 同上 |
| FastAPI `@router.get` | `@RestController` | FastAPI POST 默认返回 200（不是 201），保持原状态码 |
| Pydantic / Marshmallow / DRF Serializer 校验 | Bean Validation（`@NotBlank` 等）+ 自定义错误输出 | **错误响应结构与原系统一致**（第 7.3 节） |
| DRF 分页 `{count,next,previous,results}` | 自定义同结构 record | 第 6.3 节 |
| Django Middleware / Flask `before_request` | `Filter` / `HandlerInterceptor` | 执行顺序与原中间件一致 |
| Django `APPEND_SLASH` | 精确映射原路径 + 重定向过滤器 | 第 7.2 节 |
| CORS（django-cors-headers / flask-cors） | Spring `CorsConfiguration` | 允许的来源、方法、头与原配置一致 |
| 原系统有 OpenAPI 文档（FastAPI 自带、drf-spectacular） | springdoc-openapi | 原来没有文档则不必新增 |
| WebSocket（Channels / FastAPI） | Spring WebSocket | 消息格式不变 |
| Jinja2 / Django 模板页面 | Thymeleaf | 页面输出 HTML 一致 |

### 4.2 数据

| 原系统使用 | Java 替换 | 关键点 |
| --- | --- | --- |
| Django ORM / SQLAlchemy ORM | Spring Data JPA（Hibernate） | 实体完全映射现有表，`ddl-auto: validate` |
| 裸 SQL（PyMySQL、psycopg、`cursor.execute`） | `JdbcClient`（或 MyBatis） | SQL 原样迁移，结果一致 |
| Django signals / SQLAlchemy events | JPA 实体回调 / Spring 事件 | 只替换已有的钩子 |
| Alembic / Django migrations | Flyway **仅做基线** | 本次不新增结构（第 9 节） |
| MySQL / PostgreSQL 驱动 | mysql-connector-j / PostgreSQL JDBC | |
| 连接池（SQLAlchemy pool / Django CONN_MAX_AGE） | HikariCP | 池大小参考原配置 |

### 4.3 认证与权限

| 原系统使用 | Java 替换 | 关键点 |
| --- | --- | --- |
| Django 会话（数据库/缓存存储） | Spring Security；共存期借助原系统校验（第 8.2 节） | Cookie 名称、属性、有效期一致 |
| Flask 签名 Cookie 会话（itsdangerous） | 同上 | |
| PyJWT / python-jose / FastAPI OAuth2PasswordBearer | Spring Security OAuth2 资源服务器（`NimbusJwtDecoder`） | **相同算法、密钥、有效期、声明结构** |
| Django CSRF（`csrftoken` Cookie + `X-CSRFToken` 头） | `CookieCsrfTokenRepository`，名称配置为同名 | 第 8.4 节 |
| Django PBKDF2 / passlib bcrypt / argon2 | 相同算法的 `PasswordEncoder`，**生成与校验格式都与原来相同** | 第 8.5 节 |
| 装饰器 / 依赖中的角色判断 | `@PreAuthorize` 或服务层判断，**逻辑与原代码一致** | 第 8.6 节 |
| 原系统有登录失败限制 | 按原规则实现 | 原来没有就不加 |

### 4.4 任务、缓存、集成、文件

| 原系统使用 | Java 替换 | 关键点 |
| --- | --- | --- |
| Celery + RabbitMQ | Spring AMQP，**沿用原 RabbitMQ** | 第 10 节 |
| Celery + Redis broker / RQ | Spring Data Redis 实现的队列，或 JobRunr（Redis/数据库存储） | 第 10 节 |
| Celery beat / APScheduler | `@Scheduled`（相同 cron 与时区） | 多实例且原来有防重机制时才加 ShedLock |
| 系统 crontab 调用脚本 | 保留 crontab，命令改为调用 Java（CLI 模式或 HTTP） | |
| `threading` / `concurrent.futures` | `ExecutorService`（虚拟线程） | |
| Django cache / Flask-Caching（Redis） | Spring Cache + Redis | 第 11.1 节 |
| Django cache（本地内存） | Spring Cache + Caffeine | |
| requests / httpx | `RestClient`（+ `@HttpExchange` 接口） | 超时、重试次数与原来一致 |
| tenacity / backoff 重试 | Spring Framework 7 `@Retryable` | 重试条件、次数、间隔一致 |
| openpyxl / xlsxwriter | Apache POI | 第 11.2 节 |
| `csv` 模块 | Apache Commons CSV | 编码、BOM、换行符一致 |
| ReportLab / WeasyPrint | OpenPDF / PDFBox / OpenHTMLtoPDF | |
| Pillow | Thumbnailator / ImageIO | |
| boto3（原来就用 S3） | AWS SDK for Java v2 | 原来存本地就继续存本地 |
| smtplib / Django mail | Spring Mail | 发件人、模板一致 |
| gettext（.po） | `MessageSource`（.properties，由 .po 转换） | 原来没有国际化就不加 |

### 4.5 运维与工程

| 原系统使用 | Java 替换 | 关键点 |
| --- | --- | --- |
| gunicorn / uvicorn | 内嵌 Tomcat（`java -jar`） | 端口、监听地址一致 |
| systemd / supervisor | systemd / supervisor（只改启动命令） | |
| Docker（python 镜像） | Docker（Temurin JRE 镜像） | 第 13 节 |
| Kubernetes（原来就有） | 修改镜像和探针路径 | 原来没有就不引入 |
| 原有健康检查路径（如 `/health`） | 同路径的控制器或 Actuator 映射 | 路径和返回内容一致 |
| logging / structlog | SLF4J + Logback | **日志格式与原来一致**（第 11.4 节） |
| prometheus_client（原来就有） | Micrometer + Prometheus | 指标名称尽量一致；原来没有就不加 |
| Sentry（原来就有） | Sentry Java SDK | |
| settings.py / `.env` / pydantic-settings | `application.yml` + `@ConfigurationProperties` | **环境变量名保持不变**（第 11.5 节） |
| click / Django management command | Spring Boot CLI 模式（`ApplicationRunner` + 参数）或 picocli | 命令名、参数一致 |
| pytest | JUnit 5 + AssertJ + Testcontainers | 第 12 节 |
| black / isort | Spotless | 原来有格式检查才加对应工具 |
| flake8 / ruff / pylint | Checkstyle | 同上 |
| mypy | Java 编译器 | |
| coverage.py | JaCoCo | 同上 |
| pip / poetry / uv | Maven | |

### 4.6 Django Admin

Django Admin 没有 Java 等价物，重写它等于新开发一个管理前端，超出“等价替换”范围。可选两种做法：

1. **保留 Django Admin**（推荐）：作为“保留 Python”的部分继续运行，只连同一个数据库，仅供内部管理员使用；
2. 若必须去除 Python，单独立项开发管理页面，不纳入本次迁移。

---

## 5. 工程骨架（最小依赖）

### 5.1 目录结构

按业务分包（与原 Django app / Flask blueprint 一一对应），每个业务包内部分层：

```text
app/
├─ pom.xml
├─ mvnw, mvnw.cmd, .mvn/
├─ Dockerfile                          原来用 Docker 才需要
└─ src/
   ├─ main/java/com/company/app/
   │  ├─ Application.java
   │  ├─ common/                       跨业务的兼容层：错误格式、分页格式、JSON 配置、认证过滤器
   │  ├─ order/                        对应原 Django app "orders"
   │  │  ├─ web/          控制器、请求/响应 DTO（字段名与原接口一致）
   │  │  ├─ service/      业务逻辑、事务
   │  │  ├─ domain/       实体（映射现有表）、仓储
   │  │  └─ infra/        外部调用、复杂 SQL、文件
   │  └─ customer/ …
   ├─ main/resources/
   │  ├─ application.yml
   │  └─ db/migration/V1__baseline.sql   仅用于新建空库，见第 9 节
   └─ test/java/com/company/app/
      ├─ ArchitectureTest.java
      ├─ TestcontainersConfiguration.java
      └─ parity/                       新旧对照测试
```

### 5.2 pom.xml

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.x</version><!-- 锁定当期最新补丁版本 -->
    <relativePath/>
  </parent>
  <groupId>com.company</groupId>
  <artifactId>app</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <java.version>25</java.version><!-- 平台只支持 21 时改为 21 -->
    <mapstruct.version>1.6.x</mapstruct.version>
  </properties>

  <dependencies>
    <!-- ===== 基础（必选） ===== -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>${mapstruct.version}</version></dependency>

    <!-- ===== 按原系统情况选择（原来没有就删除） ===== -->
    <!-- 原系统用 JWT：-->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId></dependency>
    <!-- 原系统用 Redis（缓存/会话/队列）：-->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <!-- 原系统有缓存：-->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-cache</artifactId></dependency>
    <!-- 原系统用 Celery + RabbitMQ：-->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-amqp</artifactId></dependency>
    <!-- 原系统导出 Excel：-->
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.x.x</version></dependency>
    <!-- 原系统导出 CSV：-->
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.x</version></dependency>
    <!-- 原系统有 OpenAPI 文档：springdoc-openapi-starter-webmvc-ui（3.x） -->
    <!-- 原系统有 prometheus_client 指标：micrometer-registry-prometheus -->

    <!-- ===== 测试（工具，不影响运行） ===== -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-mysql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>1.4.x</version><scope>test</scope></dependency>
  </dependencies>

  <build>
    <finalName>app</finalName>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <parameters>true</parameters>
          <annotationProcessorPaths>
            <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
          </annotationProcessorPaths>
          <compilerArgs>
            <arg>-Amapstruct.defaultComponentModel=spring</arg>
            <arg>-Amapstruct.unmappedTargetPolicy=ERROR</arg>
          </compilerArgs>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

> Spring Boot 4 的 starter 名称与 3.x 不同（如 `spring-boot-starter-webmvc`、`spring-boot-starter-flyway`），默认 JSON 库为 Jackson 3（包名 `tools.jackson`）。网上基于 3.x 的示例需按此调整。

### 5.3 application.yml

```yaml
spring:
  application:
    name: app
  threads:
    virtual:
      enabled: true                 # Java 25 下推荐开启；Java 21 需注意 synchronized 导致的线程钉住
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT:3306}/${DB_NAME}   # 环境变量名沿用原 Python 配置
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10        # 参考原连接池配置
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate            # 只校验，绝不修改表结构
    properties:
      hibernate.jdbc.time_zone: UTC # 与原系统的时区语义一致（第 9.3 节），原来存本地时间则改为对应时区
  flyway:
    baseline-on-migrate: true       # 接入现有库：只登记基线，不执行任何结构变更
    baseline-version: 1
  jackson:
    property-naming-strategy: SNAKE_CASE    # 与原接口字段命名一致（原来是 camelCase 则删除此行）
  lifecycle:
    timeout-per-shutdown-phase: 30s

server:
  port: ${PORT:8080}
  shutdown: graceful
  error:
    include-message: never
    include-stacktrace: never

management:
  endpoints:
    web:
      exposure:
        include: health
```

---

## 6. 逐层替换方法与代码

### 6.1 单个接口的替换步骤

1. 从接口清单取出该接口的**真实请求/响应样本**（成功、校验失败、无权限、不存在、冲突等各种情况）；
2. 在 Java 中按相同路径、方法实现控制器，DTO 字段名、类型、可空性与原响应一致；
3. 业务逻辑按原代码语义重写，事务边界与原来一致；
4. 跑对照测试，逐字段比对，直到一致；
5. 加入 Nginx 路由，切换。

### 6.2 示例：Django REST Framework 订单接口

**原 Python：**

```python
# orders/models.py
class Order(models.Model):
    order_no = models.CharField(max_length=64, unique=True)
    customer = models.ForeignKey(Customer, on_delete=models.PROTECT)
    amount = models.DecimalField(max_digits=18, decimal_places=2)
    status = models.CharField(max_length=16, choices=[("new", "New"), ("paid", "Paid")], default="new")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "orders_order"
        ordering = ["-created_at"]

# orders/views.py
class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer
    permission_classes = [IsAuthenticated]

    def perform_create(self, serializer):
        if not self.request.user.groups.filter(name="sales").exists():
            raise PermissionDenied()
        serializer.save()
```

**Java 实体（映射现有表，不改结构）：**

```java
package com.company.app.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders_order")                     // 沿用 Django 表名
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "customer_id", nullable = false) // Django ForeignKey 列名为 <字段>_id
    private Long customerId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Convert(converter = OrderStatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Order() {}

    public Order(String orderNo, Long customerId, BigDecimal amount) {
        this.orderNo = orderNo;
        this.customerId = customerId;
        this.amount = amount;
        this.status = OrderStatus.NEW;                // 对应 Django default="new"
    }

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }   // 对应 auto_now_add

    // getter 省略
}
```

**枚举与原存储值一致（不能用枚举名或序号）：**

```java
public enum OrderStatus {
    NEW("new"), PAID("paid");

    private final String dbValue;
    OrderStatus(String dbValue) { this.dbValue = dbValue; }
    public String dbValue() { return dbValue; }

    public static OrderStatus fromDb(String v) {
        for (var s : values()) if (s.dbValue.equals(v)) return s;
        throw new IllegalStateException("Unknown status in database: " + v);
    }
}

@Converter
class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {
    @Override public String convertToDatabaseColumn(OrderStatus s) { return s == null ? null : s.dbValue(); }
    @Override public OrderStatus convertToEntityAttribute(String v) { return v == null ? null : OrderStatus.fromDb(v); }
}
```

**仓储（默认排序与 Django `Meta.ordering` 一致）：**

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    // Django 的 Meta.ordering 会自动加 ORDER BY；Java 必须显式写出，否则顺序不确定
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

**响应 DTO（字段、类型、格式与原 DRF 输出一致）：**

```java
// 原 DRF 输出：{"id": 1, "order_no": "A001", "customer": 3, "amount": "99.00", "status": "new", "created_at": "2026-10-12T08:00:00Z"}
public record OrderResponse(
        long id,                    // 原来输出数字，就保持数字
        String orderNo,             // SNAKE_CASE 策略输出为 order_no
        long customer,              // DRF 外键默认输出为字段名 customer（值为 ID）
        String amount,              // DRF DecimalField 默认输出字符串 "99.00"
        String status,              // 原存储值 "new"
        String createdAt) {}        // 按原格式输出，见第 7.1 节
```

**控制器：**

```java
@RestController
@RequestMapping("/api/orders/")                    // 与原路径完全一致，包括末尾斜杠
class OrderController {
    private final OrderService service;

    OrderController(OrderService service) { this.service = service; }

    @GetMapping
    DrfPage<OrderResponse> list(@RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        return service.list(page, request);
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));   // DRF create 返回 201
    }
}
```

**服务（权限判断复刻原逻辑）：**

```java
@Service
class OrderService {
    private final OrderRepository orders;
    private final CurrentUser currentUser;

    OrderService(OrderRepository orders, CurrentUser currentUser) {
        this.orders = orders;
        this.currentUser = currentUser;
    }

    @Transactional                                   // 仅当原代码在事务中执行（ATOMIC_REQUESTS 或 atomic）
    OrderResponse create(CreateOrderRequest req) {
        if (!currentUser.inGroup("sales")) {         // 复刻 groups.filter(name="sales")
            throw new LegacyApiException(HttpStatus.FORBIDDEN, DrfErrors.permissionDenied());
        }
        // 校验与原 Serializer 一致，失败时输出 DRF 格式错误（第 7.3 节）
        var order = orders.save(new Order(req.orderNo(), req.customer(), req.amount()));
        return OrderMapper.toResponse(order);
    }
}
```

### 6.3 兼容原分页结构

```java
/** 与 DRF PageNumberPagination 输出一致：{"count":..,"next":..,"previous":..,"results":[..]} */
public record DrfPage<T>(long count, String next, String previous, List<T> results) {

    public static <T> DrfPage<T> of(Page<T> page, HttpServletRequest request) {
        int current = page.getNumber() + 1;            // DRF 页码从 1 开始
        return new DrfPage<>(
                page.getTotalElements(),
                page.hasNext() ? pageUrl(request, current + 1) : null,
                page.hasPrevious() ? pageUrl(request, current - 1) : null,
                page.getContent());
    }

    private static String pageUrl(HttpServletRequest request, int page) {
        var builder = ServletUriComponentsBuilder.fromRequest(request);
        // DRF 在第 1 页时会去掉 page 参数
        return (page == 1 ? builder.replaceQueryParam("page") : builder.replaceQueryParam("page", page))
                .toUriString();
    }
}
```

> `next`/`previous` 中的协议与主机取自请求，需确认 Nginx 传递了正确的 `Host` 和 `X-Forwarded-Proto`，并配置 `server.forward-headers-strategy: native`，否则链接会与原系统不同。

### 6.4 FastAPI / Flask 的对应点

| 原框架特性 | Java 处理 |
| --- | --- |
| FastAPI `response_model` 过滤字段 | DTO 只包含 response_model 中的字段 |
| FastAPI `response_model_exclude_none=True` | 该 DTO 上加 `@JsonInclude(JsonInclude.Include.NON_NULL)` |
| FastAPI 路径参数类型校验失败返回 422 | 自定义异常处理返回 422 和相同结构（第 7.3 节） |
| Flask 返回 `jsonify(...)` | 控制器返回对应 record / Map |
| Flask 返回 `(body, status)` 元组 | `ResponseEntity.status(...).body(...)` |
| Flask `abort(404)` 返回 HTML 错误页 | 若客户端依赖该 HTML，原样返回相同内容 |

### 6.5 分层规范（只约束内部实现，不影响对外行为）

| 层 | 职责 |
| --- | --- |
| web | 路由、参数接收、调用服务、按原格式输出 |
| service | 业务逻辑、事务、权限判断（复刻原逻辑） |
| domain | 实体、仓储 |
| infra | 外部调用、复杂 SQL、文件读写 |

用 ArchUnit 约束：控制器不能直接访问仓储；domain 不依赖 web。

---

## 7. 行为一致性专题

这是等价替换中最容易出错、也最需要投入的部分。

### 7.1 JSON 序列化

| 项 | Python 常见行为 | Java（Jackson 3）默认 | 一致化方法 |
| --- | --- | --- | --- |
| 字段命名 | snake_case | 字段名原样（camelCase） | `spring.jackson.property-naming-strategy: SNAKE_CASE`，或 DTO 上 `@JsonProperty` |
| null 字段 | 输出 `null`（DRF、Pydantic 默认） | 输出 `null` | 保持默认；原来省略的接口加 `@JsonInclude(NON_NULL)` |
| 字段顺序 | 按定义/插入顺序 | record 按声明顺序 | DTO 按原顺序声明；必要时 `@JsonPropertyOrder` |
| Decimal | DRF 默认输出字符串 `"99.00"`；Pydantic v2 默认输出字符串 | `BigDecimal` 输出数字 | 原来是字符串就用 `String` 字段并 `toPlainString()`，**保留原小数位数** |
| float | `1e-05`、`10000000.0` 等 | `1.0E-5`、`1.0E7` | 原来输出浮点数的字段，用自定义序列化器模拟 Python `repr` 规则，或与客户端确认数值等价即可 |
| 大整数 | 任意精度 | `long` 范围 | 超出 `long` 的用 `BigInteger` |
| 日期时间 | DRF：`2026-10-12T08:00:00Z`；`datetime.isoformat()`：`2026-10-12T08:00:00+00:00`；微秒非零时输出 6 位 | `Instant` 输出 `2026-10-12T08:00:00Z`，小数位按实际精度 | **以真实样本为准**，用固定格式化器（见下） |
| 日期 | `2026-10-12` | `LocalDate` 相同 | — |
| 布尔 | `true/false` | 相同 | — |
| 非 ASCII | `json.dumps` 默认转义为 `\uXXXX`（Django JsonResponse、Flask 默认）；FastAPI 不转义 | 不转义 | 解析后语义相同，一般无需处理；**仅当客户端按字节比对或签名时**才需要开启转义 |
| 空列表/空对象 | `[]` / `{}` | 相同 | — |

模拟 Python `datetime.isoformat()` 的格式化（微秒为 0 时不输出小数，否则输出 6 位）：

```java
public final class PythonTime {
    private PythonTime() {}

    /** 输出与 Python datetime.isoformat() 相同的 UTC 字符串，例如 2026-10-12T08:00:00+00:00 */
    public static String isoformat(Instant instant) {
        var t = instant.atOffset(ZoneOffset.UTC);
        var base = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        int micros = t.getNano() / 1_000;
        return (micros == 0 ? base : base + String.format(".%06d", micros)) + "+00:00";
    }

    /** DRF 风格：将 +00:00 替换为 Z */
    public static String drf(Instant instant) {
        return isoformat(instant).replace("+00:00", "Z");
    }
}
```

> 注意：Python 用 `datetime.now()` 写入的时间带微秒，而 Java `Instant.now()` 在部分系统上可能精确到纳秒，写库前应截断到微秒：`Instant.now().truncatedTo(ChronoUnit.MICROS)`，否则同一数据在两边的输出会不同。

### 7.2 HTTP 层

| 项 | 一致化方法 |
| --- | --- |
| 路径末尾斜杠 | Spring 6+ 默认**不再**把 `/a/` 与 `/a` 视为同一路径。按原路径精确映射；原系统依赖 Django `APPEND_SLASH`（缺少斜杠时 301 重定向）时，加一个过滤器复刻该重定向 |
| 状态码 | 逐接口对照：DRF 创建 201、删除 204；FastAPI 默认 200；校验失败 DRF 400、FastAPI 422 |
| 响应头 | `Content-Type`（含 charset）、缓存头、自定义头逐项对照 |
| Cookie | 名称、`Path`、`Domain`、`HttpOnly`、`Secure`、`SameSite`、`Max-Age` 与原系统一致 |
| CORS | 来源、方法、头、是否允许凭据一致 |
| 请求体大小限制 | 与原 Nginx/框架限制一致（`spring.servlet.multipart.*`） |
| HEAD / OPTIONS | 原框架自动处理的，确认 Spring 行为相同 |
| 404 / 405 响应体 | 原系统返回的 JSON 或 HTML 原样复刻 |

`APPEND_SLASH` 重定向过滤器：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)        // 与 Django CommonMiddleware 一样，在认证之前处理
class AppendSlashFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        boolean isFile = uri.substring(uri.lastIndexOf('/') + 1).contains(".");
        if (uri.startsWith("/api/") && !uri.endsWith("/") && !isFile && "GET".equals(req.getMethod())) {
            String query = req.getQueryString();
            res.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);   // 与 Django 一致：301
            res.setHeader("Location", uri + "/" + (query == null ? "" : "?" + query));
            return;
        }
        chain.doFilter(req, res);
    }
}
```

### 7.3 错误响应

原系统的错误格式必须原样复刻。常见格式：

| 框架 | 场景 | 原格式示例 |
| --- | --- | --- |
| DRF | 校验失败（400） | `{"order_no": ["This field is required."]}` |
| DRF | 未登录（401/403） | `{"detail": "Authentication credentials were not provided."}` |
| DRF | 无权限（403） | `{"detail": "You do not have permission to perform this action."}` |
| DRF | 不存在（404） | `{"detail": "Not found."}` |
| FastAPI | 校验失败（422） | `{"detail": [{"type": "missing", "loc": ["body", "order_no"], "msg": "Field required", "input": {...}}]}` |
| FastAPI | `HTTPException` | `{"detail": "..."}` |
| Flask | 自定义 | 以实际代码为准 |

统一异常处理（DRF 风格示例）：

```java
/** 携带“原系统格式”响应体的异常。 */
public class LegacyApiException extends RuntimeException {
    private final HttpStatus status;
    private final Object body;

    public LegacyApiException(HttpStatus status, Object body) {
        super(status.toString());
        this.status = status;
        this.body = body;
    }
    public HttpStatus status() { return status; }
    public Object body() { return body; }
}

public final class DrfErrors {
    private DrfErrors() {}
    public static Map<String, String> detail(String message) { return Map.of("detail", message); }
    public static Map<String, String> permissionDenied() {
        return detail("You do not have permission to perform this action.");
    }
    public static Map<String, String> notFound() { return detail("Not found."); }
}

@RestControllerAdvice
class LegacyErrorHandler {
    @ExceptionHandler(LegacyApiException.class)
    ResponseEntity<Object> legacy(LegacyApiException e) {
        return ResponseEntity.status(e.status()).body(e.body());
    }

    /** Bean Validation 失败 → DRF 字段错误格式：{"field": ["message"]} */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, List<String>>> invalid(MethodArgumentNotValidException e) {
        var errors = new LinkedHashMap<String, List<String>>();
        for (var f : e.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(toSnakeCase(f.getField()), k -> new ArrayList<>()).add(f.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(DrfErrors.permissionDenied());
    }

    private static String toSnakeCase(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
```

校验消息文本与原系统一致：在注解上写明原文，例如 `@NotBlank(message = "This field is required.")`；多语言系统则放入 `ValidationMessages.properties`。

### 7.4 ORM 与 SQL 行为

| 项 | Python 侧常见行为 | Java 一致化 |
| --- | --- | --- |
| 默认排序 | Django `Meta.ordering` 自动加 `ORDER BY` | 查询中显式写出相同排序；**无排序的查询结果顺序不保证一致**，原系统依赖顺序的需补上相同的 ORDER BY |
| 空串与 NULL | Django `CharField` 常存空串 `""` 而非 NULL | 写入时保持相同约定 |
| 自动时间 | `auto_now_add` / `auto_now` | `@PrePersist` / `@PreUpdate` |
| 默认值 | 模型 `default=` 在应用层赋值（数据库无默认值） | 在实体构造或 `@PrePersist` 中赋值 |
| 外键删除 | `on_delete=CASCADE` 在 **Django 应用层**执行（数据库无级联） | Java 服务层显式执行相同的级联删除 |
| 软删除 | 自定义 Manager 自动过滤 | 仓储方法或 Hibernate `@SQLRestriction` 加同样条件 |
| 批量更新 | `QuerySet.update()` 不触发 `save()` 和信号 | 用批量 UPDATE 语句，同样不触发实体回调 |
| 信号 | `post_save` 等 | 只复刻原系统确实注册了的信号逻辑 |
| 大小写比较 | 由数据库排序规则决定（`iexact` 等显式不区分） | 同一数据库，SQL 条件写法保持一致即可 |
| `get_or_create` | 先查后插（存在并发重复风险） | 等价实现；并发问题登记为差异 |

### 7.5 数值、字符串、时间语义

| 场景 | Python | Java | 一致化 |
| --- | --- | --- | --- |
| 整除 | `-7 // 2 == -4` | `-7 / 2 == -3` | `Math.floorDiv` |
| 取模 | `-7 % 3 == 2` | `-7 % 3 == -1` | `Math.floorMod` |
| `round()` | 银行家舍入：`round(2.5) == 2` | `Math.round(2.5) == 3` | `BigDecimal.setScale(n, RoundingMode.HALF_EVEN)` |
| `Decimal.quantize` | 默认 `ROUND_HALF_EVEN`（可配置） | — | 使用与原代码相同的 `RoundingMode` |
| 整数溢出 | 不溢出 | 溢出 | `Math.addExact` 等，或 `BigInteger` |
| 浮点运算 | IEEE 754 双精度 | 相同 | 运算顺序保持一致即可得到相同结果 |
| 字符串长度/切片 | 按 Unicode 码点 | 按 UTF-16 单元 | 涉及表情等字符时用 `codePointCount` / `offsetByCodePoints` |
| `lower()` / `upper()` | 与区域无关 | 受默认区域影响 | `toLowerCase(Locale.ROOT)` |
| `strip()` | 去除 Unicode 空白 | `trim()` 只去 ASCII 控制字符和空格 | 用 `String.strip()` |
| `split()` 无参数 | 按任意空白分割并去掉空项 | `split(" ")` 行为不同 | `s.strip().split("\\s+")`（空串需单独处理） |
| 正则 `\d`、`\w` | 默认匹配 Unicode | 默认只匹配 ASCII | 需要时加 `Pattern.UNICODE_CHARACTER_CLASS` |
| 排序 | 字符串按码点排序 | `compareTo` 按 UTF-16 单元 | 只在含增补字符时有差异 |
| 字典迭代顺序 | 插入顺序 | `HashMap` 无序 | `LinkedHashMap` |
| `datetime.now()` | 本地时间（naive） | — | 与原代码相同的时区，用注入的 `Clock` |
| `strftime` | `%Y-%m-%d` 等 | `DateTimeFormatter` | 注意 `yyyy`（年）与 `YYYY`（周年）不同 |
| 真值判断 | 空串、0、空列表为假 | 无隐式转换 | 显式写出与原代码相同的判断 |

### 7.6 事务边界

| 原系统情况 | Java 做法 |
| --- | --- |
| Django `ATOMIC_REQUESTS=True`（每个请求一个事务） | 每个写接口的服务方法 `@Transactional` |
| 代码中 `transaction.atomic()` 块 | 对应方法加 `@Transactional`，范围相同 |
| Django 默认 autocommit（每条 SQL 单独提交） | 原样复刻**会保留“中途失败留下部分数据”的行为**。登记为差异，由业务确认：保持原样，或加事务（只改变失败时的结果，属于数据安全修复） |
| SQLAlchemy 手动 `session.commit()` | 按原提交点划分事务；一个方法内多次提交的，拆成多个事务方法 |
| 事务中调用外部系统 | 保持原顺序；风险登记为差异 |

---

## 8. 认证与权限：按原机制复刻

### 8.1 总原则

- 认证方式、Cookie/令牌格式、有效期、密码哈希**全部保持不变**；
- 共存期同一个用户的登录态在 Python 与 Java 两边都有效；
- 登录、登出、改密接口最后迁移。

### 8.2 原系统使用会话（Django / Flask）：共存期由 Nginx 向 Python 校验

Java 自己解析 Django/Flask 的会话格式容易出错（签名、序列化、密码变更后会话失效等细节）。共存期最稳妥的做法是：**由 Nginx 的 `auth_request` 请 Python 判断会话是否有效，再把用户 ID 传给 Java**。

```mermaid
sequenceDiagram
    participant B as 浏览器（Cookie 不变）
    participant N as Nginx
    participant P as Python（/internal/whoami）
    participant J as Java
    B->>N: GET /api/customers/（携带原会话 Cookie）
    N->>P: 子请求 /internal/whoami（转发 Cookie）
    P-->>N: 200 + X-User-Id / 401
    N->>J: 转发请求 + X-User-Id（并清除客户端伪造的同名头）
    J-->>B: 响应
```

Python 侧新增一个仅供内部调用的接口（共存期专用，下线 Python 时随之删除）：

```python
# 仅限内网访问
@login_required
def whoami(request):
    resp = HttpResponse(status=200)
    resp["X-User-Id"] = str(request.user.id)
    return resp
```

Nginx：

```nginx
location = /internal/whoami {
    internal;
    proxy_pass http://python_app/internal/whoami;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Cookie $http_cookie;
}

location /api/customers/ {
    auth_request /internal/whoami;
    auth_request_set $user_id $upstream_http_x_user_id;
    proxy_set_header X-User-Id $user_id;       # 覆盖客户端可能伪造的同名头
    proxy_pass http://java_app;
}
```

Java 侧过滤器（只信任来自 Nginx 的请求，Java 端口不对外开放）。注意它必须加入 Spring Security 过滤链，不能只注册为普通 Bean，否则会在安全检查之后才执行：

```java
/** 不加 @Component，由安全配置显式加入过滤链。 */
class GatewayUserFilter extends OncePerRequestFilter {
    private final UserRepository users;

    GatewayUserFilter(UserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String userId = req.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            users.findById(Long.parseLong(userId)).ifPresent(user -> {
                var auth = UsernamePasswordAuthenticationToken.authenticated(
                        user, null, user.authorities());    // 角色/组从原表读取
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(req, res);
    }
}

@Configuration
@EnableMethodSecurity
class GatewaySecurityConfig {
    @Bean
    SecurityFilterChain api(HttpSecurity http, UserRepository users) throws Exception {
        return http
                .addFilterBefore(new GatewayUserFilter(users), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())   // 按原接口逐一配置，见 8.6 节
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())                                       // 共存期 CSRF 由 Python 校验，见 8.4 节
                .build();
    }
}
```

切换认证接口（最后一批）时，再由 Java 实现会话管理：

- 使用 Spring Security + Spring Session（会话存储与原系统相同：原来存 Redis 就用 Spring Session Redis，原来存数据库就用 Spring Session JDBC）；
- 会话 Cookie 名称配置为与原系统相同（如 `sessionid`），属性一致；
- 切换时原会话会失效，需要用户重新登录一次。若不能接受，切换前需评估实现原会话格式的读取（工作量较大，单独评估）。

### 8.3 原系统使用 JWT：两边直接互认

Java 使用**相同的算法、密钥和声明结构**校验令牌，两边签发的令牌互相有效：

```java
@Configuration
@EnableMethodSecurity
class JwtSecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${JWT_SECRET}") String secret) {       // 与 Python 相同的环境变量和密钥
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 原系统校验了哪些声明（exp、iss、aud），这里就校验哪些，不多也不少
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())          // 访问规则见 8.6 节，按原接口逐一配置
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .build();
    }

    /** 把原令牌中的声明（例如 "user_id"、"roles"）映射为当前用户 */
    private JwtAuthenticationConverter converter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");     // 与原令牌字段一致
        authorities.setAuthorityPrefix("ROLE_");
        var c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(authorities);
        c.setPrincipalClaimName("user_id");               // 与原令牌字段一致
        return c;
    }
}
```

> PyJWT 的 HS256 密钥按 UTF-8 字节使用，与上面的写法一致。若原系统用 RS256，则用 `NimbusJwtDecoder.withPublicKey(...)` 加载同一公钥。Java 侧签发令牌（迁移登录接口时）使用 `NimbusJwtEncoder`，声明结构、有效期与原系统完全相同。

### 8.4 CSRF（与 Django 兼容）

前端不变，因此 CSRF 的 Cookie 名和请求头名必须与 Django 相同：

```java
var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
csrf.setCookieName("csrftoken");          // Django 默认 Cookie 名
csrf.setHeaderName("X-CSRFToken");        // Django 默认请求头名
http.csrf(c -> c.csrfTokenRepository(csrf)
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
```

> 共存期由哪一方签发 `csrftoken`、另一方是否接受，需要在对照测试中验证。Django 的 CSRF 令牌带掩码格式，Spring 默认不认识；共存期建议对迁移到 Java 的写接口在 Nginx 层沿用 Python 的校验（同 8.2 节的 `auth_request` 方式，由 Python 一并校验 CSRF），或在认证切换批次统一切换。

### 8.5 密码哈希：生成与校验都保持原格式

为保证随时可回退，Java 写入的密码哈希必须能被 Python 校验。**不使用带 `{id}` 前缀的格式，也不升级算法。**

Django PBKDF2 格式（`pbkdf2_sha256$迭代次数$盐$哈希`）的完整实现：

```java
/** 与 Django PBKDF2PasswordHasher 完全兼容的编码器：生成和校验同一格式。 */
public class DjangoPbkdf2PasswordEncoder implements PasswordEncoder {
    private static final String ALGORITHM = "pbkdf2_sha256";
    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final int iterations;              // 与原 Django 版本的默认值一致（例如 Django 4.2 为 600000）
    private final SecureRandom random = new SecureRandom();

    public DjangoPbkdf2PasswordEncoder(int iterations) { this.iterations = iterations; }

    @Override
    public String encode(CharSequence raw) {
        var salt = new StringBuilder(22);       // Django 默认盐长度 22（以原系统实际为准）
        for (int i = 0; i < 22; i++) salt.append(SALT_CHARS.charAt(random.nextInt(SALT_CHARS.length())));
        return ALGORITHM + "$" + iterations + "$" + salt + "$" + hash(raw, salt.toString(), iterations);
    }

    @Override
    public boolean matches(CharSequence raw, String encoded) {
        if (encoded == null) return false;
        String[] p = encoded.split("\\$");
        if (p.length != 4 || !ALGORITHM.equals(p[0])) return false;
        try {
            String actual = hash(raw, p[2], Integer.parseInt(p[1]));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    p[3].getBytes(StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String hash(CharSequence raw, String salt, int iterations) {
        try {
            var spec = new PBEKeySpec(raw.toString().toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(dk);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

@Bean
PasswordEncoder passwordEncoder() {
    return new DjangoPbkdf2PasswordEncoder(600_000);   // 与原系统迭代次数一致
}
```

其他格式：

| 原格式 | Java 编码器 | 注意 |
| --- | --- | --- |
| passlib / bcrypt `$2b$12$...` | `new BCryptPasswordEncoder(BCryptVersion.$2B, 12)` | 版本前缀和成本因子与原来一致，生成的哈希 Python 可直接校验 |
| argon2 `$argon2id$...` | `Argon2PasswordEncoder`（参数与原配置一致） | 需要 BouncyCastle；先用样本验证互认 |
| werkzeug `pbkdf2:sha256:600000$盐$十六进制哈希` | 参照上例自定义（哈希为十六进制、盐为 ASCII） | 用样本验证 |
| werkzeug 3.x 默认 `scrypt:32768:8:1$盐$十六进制哈希` | 用 `SCryptPasswordEncoder` 的底层算法（BouncyCastle `SCrypt`）自定义编码器，参数取自哈希串 | 用样本验证 |
| 其他自定义算法 | 按原代码逐字节实现 | 必须用原系统生成的样本做互认测试 |

**互认测试**：用 Python 生成一批哈希，Java 必须全部校验通过；用 Java 生成一批哈希，Python 必须全部校验通过。

### 8.6 权限：复刻原判断逻辑

| 原系统写法 | Java 写法 |
| --- | --- |
| DRF `IsAuthenticated` | `.requestMatchers("/api/orders/**").authenticated()` |
| DRF `AllowAny` | `.permitAll()` |
| `request.user.is_staff` / `is_superuser` | 从原用户表读取同名字段，`@PreAuthorize("principal.staff")` 或服务层判断 |
| `user.groups.filter(name=...)` | 从原 `auth_user_groups` / `auth_group` 表加载组，服务层判断 |
| `user.has_perm("app.change_order")` | 从原 `auth_permission` 等表加载权限，`hasAuthority("app.change_order")` |
| 查询中按用户/部门过滤 | 仓储查询加完全相同的条件 |
| 自定义装饰器 | 自定义注解 + 切面，或服务层判断，逻辑逐行对照 |

无权限时返回的状态码与响应体与原系统一致（DRF 未登录时可能返回 401 或 403，取决于认证类配置，以实际样本为准）。

---

## 9. 数据库：结构零改动

### 9.1 接入现有库

1. 导出当前结构作为基线文件（仅用于新建空库，如测试环境）：

   ```bash
   mysqldump --no-data --routines --triggers --skip-add-drop-table app > V1__baseline.sql
   ```

2. 对已有数据库，Flyway 只登记基线版本，**不执行任何脚本**：

   ```yaml
   spring:
     flyway:
       baseline-on-migrate: true
       baseline-version: 1
   ```

3. JPA 设置 `ddl-auto: validate`，实体与表不一致时启动失败，防止映射错误；
4. 迁移期间**冻结结构变更**。确需变更时（例如线上紧急修复），统一由一方执行，并同步修改 Python 模型与 Java 实体。

### 9.2 实体映射规则

| Django / SQLAlchemy | JPA |
| --- | --- |
| `db_table` / `__tablename__` | `@Table(name = "...")`，照抄原表名 |
| 字段名 | `@Column(name = "...")`，照抄原列名 |
| `AutoField` / `BigAutoField` | `@Id @GeneratedValue(strategy = IDENTITY)`，类型 `Integer` / `Long` 与原列一致 |
| `ForeignKey` | 默认只映射外键 ID（`Long customerId`），需要关联查询时再用 `@ManyToOne(fetch = LAZY)` |
| `ManyToManyField` | `@ManyToMany` + `@JoinTable(name = 原中间表)`，或映射中间表为实体 |
| `DecimalField` | `BigDecimal`，精度一致 |
| `DateTimeField` | 见 9.3 节 |
| `BooleanField` | `boolean` / `Boolean`（MySQL `TINYINT(1)`） |
| `JSONField` | `@JdbcTypeCode(SqlTypes.JSON)` + `Map`/record |
| `choices` | 枚举 + `AttributeConverter`（存原值） |
| `null=True` | 包装类型（`Integer`、`Boolean`），不要用基本类型 |
| 模型继承（多表继承） | `@Inheritance(strategy = JOINED)`，与原表结构一致 |

### 9.3 时间与时区

| 原系统设置 | 数据库中的值 | Java 映射 |
| --- | --- | --- |
| Django `USE_TZ=True`（默认） | UTC 时间（MySQL `DATETIME` 无时区） | `Instant` + `hibernate.jdbc.time_zone: UTC` |
| Django `USE_TZ=False` | `TIME_ZONE` 指定的本地时间 | `LocalDateTime`，不做时区转换；或 `hibernate.jdbc.time_zone` 设为该时区 |
| SQLAlchemy naive datetime | 以原代码写入时使用的时区为准 | 同上 |

**必须用真实数据验证**：同一条记录在 Python 和 Java 中读出的时间、输出的字符串完全一致。

### 9.4 原生 SQL

原系统用裸 SQL 的地方，用 `JdbcClient` 原样迁移，语义（排序、分组、空值处理）保持不变：

```java
List<DailyTotal> totals = jdbc.sql("""
        SELECT DATE(created_at) AS day, COUNT(*) AS cnt, SUM(amount) AS total
        FROM orders_order
        WHERE created_at >= :start AND created_at < :end
        GROUP BY DATE(created_at)
        ORDER BY day
        """)
        .param("start", start)
        .param("end", end)
        .query(DailyTotal.class)
        .list();
```

### 9.5 数据迁移

共用原数据库，**不需要数据迁移**。如果原系统有需要转换的数据（例如 Python `pickle` 序列化存储的字段），Java 无法读取，需要：

- 由 Python 一次性转换为 JSON 格式写回（这属于数据格式变更，需登记、在切换前完成并确认 Python 仍可读）；或
- 该功能保留 Python。

---

## 10. 后台任务与定时任务：按原机制映射

### 10.1 映射规则

| 原系统 | Java 替换 | 共存期切换方式 |
| --- | --- | --- |
| Celery + RabbitMQ | Spring AMQP，沿用原 RabbitMQ 服务器 | 生产者和消费者**同批切换**（见 10.2） |
| Celery + Redis broker | Spring Data Redis 队列，或 JobRunr（Redis/数据库存储） | 同上 |
| RQ（Redis） | 同上 | 同上 |
| Celery beat | `@Scheduled`，cron 与 `CELERY_TIMEZONE` 一致 | 先停 Python 的该项调度，再启用 Java |
| APScheduler | `@Scheduled` | 同上 |
| crontab 调用 `manage.py xxx` | crontab 调用 Java CLI（`java -jar app.jar --task=xxx`） | 修改 crontab 一行即可切换和回退 |
| 请求中开线程异步处理 | `@Async`（虚拟线程执行器） | 随接口一起切换 |
| 多实例部署且用 Redis 锁防止重复执行 | ShedLock（Redis 实现）或沿用相同的 Redis 锁键 | 锁键一致时两边可互斥 |

### 10.2 Celery 任务的切换方法

Celery 的消息格式是 Celery 专有协议，Java 直接消费 Celery 消息成本高、易出错。推荐**按任务类型整体切换**：

1. 该任务的**生产者**（发送任务的接口）和**消费者**（worker）一起迁到 Java；
2. Java 使用新的队列名（如 `java.order.export`），消息体为 JSON；
3. 切换步骤：Nginx 把生产者接口切到 Java → 等待 Python 队列中该类任务处理完 → 停掉 Python worker 对该任务的监听；
4. 回退：把接口切回 Python 即可，Java 队列中剩余消息处理完后停止 Java 消费者。

Java 消费者示例：

```java
@Configuration
class OrderExportQueueConfig {
    @Bean
    Queue orderExportQueue() { return QueueBuilder.durable("java.order.export").build(); }

    @Bean
    MessageConverter jsonConverter(JsonMapper mapper) { return new JacksonJsonMessageConverter(mapper); }
}

@Component
class OrderExportWorker {
    private final OrderExportService exports;

    OrderExportWorker(OrderExportService exports) { this.exports = exports; }

    /** 重试次数、失败处理与原 Celery 任务（max_retries、autoretry_for）一致 */
    @RabbitListener(queues = "java.order.export")
    void handle(OrderExportMessage message) {
        exports.export(message.exportId());
    }
}
```

> Spring AMQP 的 JSON 消息转换器类名随版本变化（Jackson 2 为 `Jackson2JsonMessageConverter`，Jackson 3 对应新类），以所用版本文档为准。重试策略通过 `spring.rabbitmq.listener.simple.retry.*` 配置为与原任务相同。

原任务的结果存储（Celery result backend）如果被接口查询（例如前端轮询任务状态），Java 侧要实现同样的状态查询接口和返回结构。

### 10.3 定时任务

```java
@Configuration
@EnableScheduling
class SchedulingConfig { }

@Component
class DailyReportJob {
    private final ReportService reports;
    DailyReportJob(ReportService reports) { this.reports = reports; }

    // 原 Celery beat：crontab(hour=2, minute=0)，CELERY_TIMEZONE = "Asia/Shanghai"
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    void run() {
        reports.generateDaily();
    }
}
```

注意 cron 格式差异：Celery `crontab()` / Linux crontab 为 5 段，Spring 为 6 段（首位是秒）。

---

## 11. 缓存、文件、外部调用、日志、配置

### 11.1 缓存

| 要点 | 说明 |
| --- | --- |
| 只替换已有缓存 | 缓存位置、过期时间与原系统一致；原来没有缓存的地方不加 |
| 两边缓存不能互读 | Python 缓存值多为 pickle 格式，Java 无法读取；Java 使用**不同的键前缀** |
| 共存期的数据新鲜度 | 若 Python 缓存了某数据，而该数据改由 Java 写入，Python 会读到旧缓存。处理：该数据的读写接口同批切换；或 Java 写入后按原键删除 Python 缓存（键名规则需与原系统一致，例如 Django 默认 `:1:<key>`） |

```java
@Cacheable(cacheNames = "dict", key = "#type")    // 过期时间在 RedisCacheConfiguration 中配置为原值
public List<DictItem> items(String type) { ... }
```

### 11.2 文件导入导出

| 项 | 一致化要求 |
| --- | --- |
| 存储位置 | 原来存本地磁盘就存同一目录（Java 进程需有相同权限）；原来存 S3 就用同一桶 |
| 文件名 | 命名规则、下载时 `Content-Disposition` 一致 |
| Excel | 工作表名、表头文字、列顺序、单元格类型（文本/数字/日期）、数字格式、日期格式一致；列宽、样式按需 |
| CSV | 编码（UTF-8 / GBK）、是否带 BOM、分隔符、引号规则、**换行符**（Python `csv` 默认 `\r\n`）一致 |
| 导入 | 支持的格式、表头匹配规则、错误提示文本和返回结构一致 |
| 上传限制 | 大小、类型限制一致 |

```java
// 与 Python csv.writer 默认行为一致：逗号分隔、必要时加双引号、\r\n 换行
var format = CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").build();
try (var printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
    printer.printRecord("order_no", "amount", "status");
    for (var row : rows) printer.printRecord(row.orderNo(), row.amount(), row.status());
}
```

用对照测试比对导出文件：CSV 逐字节比对；Excel 解析后逐单元格比对值和类型。

### 11.3 外部调用

超时、重试次数、重试间隔、请求头、认证方式与原代码一致：

```java
public interface ErpClient {
    @GetExchange("/orders/{no}")
    ErpOrder getOrder(@PathVariable String no);
}

@Configuration
@EnableResilientMethods
class ErpClientConfig {
    @Bean
    ErpClient erpClient(RestClient.Builder builder, @Value("${ERP_BASE_URL}") String baseUrl) {
        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))          // 与原 requests timeout 一致
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        var client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(ErpClient.class);
    }
}

@Component
class ErpGateway {
    private final ErpClient client;
    ErpGateway(ErpClient client) { this.client = client; }

    // 原代码：@retry(stop=stop_after_attempt(3), wait=wait_exponential(multiplier=1))
    @Retryable(includes = ResourceAccessException.class, maxRetries = 2, delay = 1000, multiplier = 2)
    public ErpOrder fetch(String no) { return client.getOrder(no); }
}
```

> 注意重试次数的计数方式：tenacity `stop_after_attempt(3)` 表示总共 3 次尝试，对应 Spring `maxRetries = 2`（首次之外再重试 2 次）。

### 11.4 日志

日志格式与原系统一致，运维的日志检索、告警规则无需改动：

```xml
<!-- logback-spring.xml：模拟 Python logging 格式 "%(asctime)s %(levelname)s %(name)s %(message)s" -->
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss,SSS} %level %logger %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

原系统用 JSON 日志（structlog）的，使用 Logstash 编码器或 Boot 结构化日志，并确保字段名与原来一致。日志输出位置（标准输出/文件路径/轮转策略）保持不变。

### 11.5 配置

- **环境变量名保持不变**（`DATABASE_URL`、`SECRET_KEY`、`REDIS_URL` 等），运维脚本和部署配置无需修改；
- 原来用 `DATABASE_URL` 这类组合格式的，在 Java 中解析后设置数据源：

```java
@Configuration
class DatabaseUrlConfig {
    /** 兼容原 DATABASE_URL=mysql://user:pass@host:3306/db 格式 */
    @Bean
    DataSource dataSource(@Value("${DATABASE_URL}") String url) {
        var u = URI.create(url);
        String[] cred = u.getUserInfo().split(":", 2);
        var ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://" + u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 3306) + u.getPath());
        ds.setUsername(URLDecoder.decode(cred[0], StandardCharsets.UTF_8));
        ds.setPassword(cred.length > 1 ? URLDecoder.decode(cred[1], StandardCharsets.UTF_8) : "");
        return ds;
    }
}
```

- 业务配置用 `@ConfigurationProperties` 绑定，默认值与原系统一致；
- 原系统读不到配置时用默认值的行为，按原样保持（不改为启动失败，避免切换时因配置缺失而无法启动）；此类隐患登记为差异。

---

## 12. 测试：逐接口对照验收

### 12.1 测试组成

| 类型 | 工具 | 用途 |
| --- | --- | --- |
| **新旧对照测试（核心）** | 录制回放 / 复用原 pytest 接口测试 | 证明行为一致 |
| 单元测试 | JUnit 5 + AssertJ | 计算、规则、格式化函数（如第 7 节各项） |
| 集成测试 | Testcontainers（与生产相同的数据库版本） | 实体映射、SQL、事务 |
| 互认测试 | Python 与 Java 交叉验证 | 密码哈希、JWT、缓存键、文件格式 |
| 架构测试 | ArchUnit | 分层约束 |

### 12.2 方法一：复用原 pytest 接口测试（推荐优先）

如果原系统有基于 HTTP 的接口测试（如 DRF `APIClient`、FastAPI `TestClient`），把它们改造为**对任意地址发请求的黑盒测试**，分别对 Python 和 Java 运行：

```python
# conftest.py：通过环境变量切换被测系统
import os, requests, pytest

BASE_URL = os.environ.get("TARGET_BASE_URL", "http://127.0.0.1:8000")

@pytest.fixture
def api():
    session = requests.Session()
    session.base_url = BASE_URL
    return session
```

```bash
TARGET_BASE_URL=http://127.0.0.1:8000 pytest tests/api   # 对 Python 运行
TARGET_BASE_URL=http://127.0.0.1:8080 pytest tests/api   # 对 Java 运行，结果必须相同
```

这样原有测试资产直接成为验收标准。测试本身属于测试工具，Python 下线后可以保留在测试仓库中运行。

### 12.3 方法二：录制回放比对

1. 在测试环境准备一份固定数据（数据库快照）；
2. 对 Python 执行一组请求（覆盖成功、各类失败、边界值），录制请求、响应、执行后的数据库变化；
3. 将数据库恢复到同一快照，对 Java 重放同一组请求；
4. 比对响应（状态码、响应头、响应体）和数据库变化。

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OrderParityTest {
    @LocalServerPort int port;

    @ParameterizedTest
    @MethodSource("recordedCases")                 // 从 src/test/resources/parity/orders/*.json 读取录制样本
    void sameAsPython(RecordedCase c) throws Exception {
        var response = RestClient.create("http://localhost:" + port)
                .method(HttpMethod.valueOf(c.method()))
                .uri(c.path())
                .headers(h -> c.headers().forEach(h::add))
                .body(c.requestBody() == null ? "" : c.requestBody())
                .exchange((req, res) -> new Recorded(res.getStatusCode().value(), res.bodyTo(String.class)));

        assertThat(response.status()).isEqualTo(c.expectedStatus());
        // 严格比较 JSON（字段、类型、值、数组顺序），只忽略事先列明的易变字段
        JSONAssert.assertEquals(c.expectedBody(), response.body(),
                new CustomComparator(JSONCompareMode.STRICT, c.ignoredPaths()));
    }
}
```

（`JSONAssert` 来自 `org.skyscreamer:jsonassert`，Spring Boot 测试依赖已包含。）

**比对规则**：

- 只允许忽略事先列明的字段（自增 ID、当前时间、随机令牌），并记录忽略原因；
- 数组顺序必须一致（原系统顺序不确定的，先在 Python 侧确认是否依赖顺序）；
- 数据库比对：受影响表的行数和每行字段值。

### 12.4 Testcontainers 配置

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    MySQLContainer mysql() {                                  // Testcontainers 2.x：org.testcontainers.mysql.MySQLContainer
        return new MySQLContainer("mysql:8.0.36")             // 与生产相同的版本
                .withCopyFileToContainer(MountableFile.forClasspathResource("db/migration/V1__baseline.sql"),
                        "/docker-entrypoint-initdb.d/01-schema.sql");
    }
}
```

### 12.5 每批接口的验收标准

- 对照测试 100% 通过（或差异已登记并获确认）；
- 互认测试通过（涉及认证、缓存、文件的批次）；
- 性能不低于原接口（同环境、同数据量对比平均和 P95 响应时间）；
- 预发布环境用真实流量副本验证一段时间（可用 Nginx `mirror` 指令把只读请求复制到 Java，只比对不返回）。

```nginx
location /api/customers/ {
    mirror /mirror_customers;          # 复制请求到 Java，只用于比对，Java 的响应被丢弃
    proxy_pass http://python_app;
}
location = /mirror_customers {
    internal;
    proxy_pass http://java_app$request_uri;
}
```

> 镜像流量只能用于**只读接口**，写接口镜像会导致重复写入。

---

## 13. 构建与部署：沿用原方式

### 13.1 构建

```bash
./mvnw -B verify          # 编译、单元测试、集成测试、对照测试
# 产物：target/app.jar
```

原系统有 CI 的，在原 CI 中把 Python 步骤替换为上面的命令，CI 平台不变。

### 13.2 部署方式对照

| 原部署方式 | Java 部署 |
| --- | --- |
| systemd + gunicorn | systemd，`ExecStart=/usr/bin/java -jar /opt/app/app.jar`；端口、用户、工作目录不变 |
| supervisor + gunicorn | supervisor，`command=java -jar /opt/app/app.jar` |
| Docker（python 镜像） | Docker（Temurin JRE 镜像），端口、环境变量、挂载目录不变 |
| docker-compose | 只替换该服务的镜像和启动命令 |
| Kubernetes | 只替换镜像，探针路径改为原健康检查路径 |

systemd 示例：

```ini
[Unit]
Description=app (Java)
After=network.target

[Service]
User=app
WorkingDirectory=/opt/app
EnvironmentFile=/opt/app/.env          # 沿用原环境变量文件
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/app/app.jar
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Dockerfile 示例：

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
```

### 13.3 健康检查

原系统有健康检查路径（如 `/health` 返回 `{"status":"ok"}`）的，Java 提供相同路径和响应，负载均衡和监控配置无需修改：

```java
@RestController
class HealthController {
    private final JdbcClient jdbc;
    HealthController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/health")
    Map<String, String> health() {
        jdbc.sql("SELECT 1").query(Integer.class).single();   // 与原检查内容一致
        return Map.of("status", "ok");
    }
}
```

### 13.4 资源规划

Java 进程内存占用模型与 Python 不同（JVM 堆 + 元空间 + 线程栈），通常单进程即可替代多个 gunicorn worker。按压测结果确定内存上限，`-XX:MaxRAMPercentage` 控制堆占比。

---

## 14. 切换、回退与下线

### 14.1 每批接口的切换步骤

| 步骤 | 操作 | 回退方式 |
| --- | --- | --- |
| 1 | 对照测试全部通过 | — |
| 2 | 部署 Java 新版本（不接流量） | — |
| 3 | 只读接口可先镜像流量比对（第 12.5 节） | 删除镜像配置 |
| 4 | 修改 Nginx 路由，`nginx -s reload` | 改回路由并 reload |
| 5 | 观察错误率、响应时间、业务数据 | 发现问题立即执行回退 |
| 6 | 观察期结束（建议至少一个完整业务周期） | — |

由于数据库结构、数据格式、会话、密码哈希都与 Python 兼容，**任何一批在任何时候都可以通过改回路由回退**。这是本方案最重要的保障，任何破坏兼容的改动都必须拒绝。

### 14.2 后台任务的切换

按第 10 节：生产者与消费者同批切换，先停 Python 侧再启 Java 侧，确认队列排空。

### 14.3 一次性整体切换（仅限小系统）

1. 所有接口和任务在测试环境全部通过对照测试；
2. 选定停机窗口，停止 Python 服务；
3. 启动 Java，修改 Nginx 全部指向 Java；
4. 冒烟测试；
5. 失败时停止 Java、恢复 Python（数据库未改动，可直接恢复）。

### 14.4 下线 Python

1. 全部接口和任务切换完成，并度过观察期；
2. 删除 Nginx 中的 Python 上游和共存期专用配置（`auth_request`、镜像）；
3. 删除 Python 侧共存期专用接口（如 `/internal/whoami`）；
4. 停止 Python 服务，归档代码仓库；
5. 保留 Python 的部分（如 Django Admin、ML 服务）继续运行，接口不变；
6. 输出迁移总结：替换清单、差异清单、遗留事项。

---

## 15. 差异清单：发现但不在本次修复的问题

迁移过程中一定会发现旧系统的问题。按“等价替换”原则，默认**原样保持**，统一登记在这里，迁移完成后作为独立事项处理。

### 15.1 登记表

| 编号 | 位置 | 旧行为 | 问题 | 本次处理 | 后续建议 | 确认人 |
| --- | --- | --- | --- | --- | --- | --- |
| D-001 | `orders/views.py:42` | 先查询后插入判断订单号重复 | 并发下可能重复 | 保持（Java 同样先查后插） | 加唯一约束 | |
| D-002 | `reports/tasks.py:18` | 多条 SQL 无事务 | 中途失败留下部分数据 | 经确认加事务（数据安全修复） | — | 业务负责人 |
| D-003 | 订单列表 | 无 ORDER BY，顺序随机 | 分页可能重复/遗漏 | 保持 | 增加排序 | |

### 15.2 本次允许修复的范围

只有以下两类问题可以在迁移中修复，且必须经业务负责人确认并在对照测试中标注为“有意差异”：

1. **安全漏洞**：SQL 注入、越权访问、敏感信息泄露、明文密码等；
2. **数据损坏**：会导致数据不一致或丢失的问题（如无事务的多步写入）。

其他一律原样保持，包括：性能问题、代码质量问题、不合理的接口设计、前端精度问题、缺少的功能。

### 15.3 迁移后的常见改进方向

以下改进在迁移完成、Python 下线后再单独立项，见附录 A：接口规范化（统一错误格式、版本化）、ID 字符串化、密码哈希升级、统一认证（OIDC）、可观测性、唯一约束与并发控制、自动化质量门禁等。

---

## 16. 常见陷阱

| 陷阱 | 后果 | 预防 |
| --- | --- | --- |
| 顺手“优化”接口格式 | 前端或外部调用方出错 | 对照测试零差异；改进进差异清单 |
| 顺手修复旧缺陷 | 行为变化，回退后数据不兼容 | 按第 15 节处理 |
| 忽略 Django `Meta.ordering` | 列表顺序不同 | 显式 ORDER BY |
| 枚举按名称或序号存储 | 写入值与 Python 不兼容 | `AttributeConverter` 存原值 |
| 时区处理不同 | 时间偏移 8 小时等 | 9.3 节逐项确认 |
| 时间精度不同 | 输出的小数位不同 | 截断到微秒；按原格式输出 |
| Decimal 输出为数字 | 原接口是字符串，客户端解析失败 | DTO 用 `String` |
| 末尾斜杠 | 404 或重定向行为不同 | 精确映射 + 重定向过滤器 |
| 使用新的密码哈希格式 | 回退后用户无法登录 | 与原格式一致，互认测试 |
| Java 写入后 Python 缓存未失效 | Python 返回旧数据 | 同批切换或按原键删除 |
| Celery 任务只迁一半 | 任务丢失或重复 | 生产者消费者同批切换 |
| 定时任务两边同时运行 | 重复执行 | 先停 Python 再启 Java |
| cron 表达式段数不同 | 执行时间错误 | Spring 为 6 段（含秒） |
| 重试次数计数方式不同 | 多重试或少重试一次 | 对照原库的语义换算 |
| 修改环境变量名 | 部署失败 | 沿用原变量名 |
| 日志格式改变 | 运维检索和告警失效 | 沿用原格式 |
| Hibernate `ddl-auto=update` | 自动修改生产表结构 | 只用 `validate` |
| 引入原系统没有的中间件 | 运维负担和风险增加 | 按第 4 节“原来没有就不引入” |

---

## 附录 A：可选增强（不在本次范围）

以下内容**不属于等价替换**，迁移完成并稳定运行后，按需单独立项：

| 增强项 | 内容 | 价值 |
| --- | --- | --- |
| 接口规范化 | 统一 camelCase、RFC 9457 错误格式、`/api/v2` 版本化、ID 与大数字符串化 | 规范统一、前端精度安全 |
| 统一身份认证 | OIDC 身份服务（Keycloak 等）+ BFF | 单点登录、令牌不进浏览器 |
| 密码哈希升级 | `DelegatingPasswordEncoder`，登录时自动升级为 bcrypt/argon2 | 安全性提升 |
| 权限模型 | 权限码 RBAC、数据范围、多租户 | 精细化授权 |
| 并发与一致性 | 唯一约束、乐观锁版本列、幂等键 | 消除并发缺陷 |
| 模块化架构 | Spring Modulith 模块边界校验与事件 | 长期可维护性 |
| 可靠后台任务 | JobRunr / db-scheduler、事务性发件箱 | 任务可恢复、可观测 |
| 可观测性 | Micrometer + Prometheus + OpenTelemetry + 结构化日志 + 告警 | 故障定位、容量规划 |
| 质量门禁 | SpotBugs、SonarQube、依赖漏洞扫描、覆盖率门槛、自动依赖升级 | 持续保障质量 |
| 容器编排 | Kubernetes / Helm、自动扩缩容 | 弹性与高可用 |
| 对象存储 | S3 / MinIO 替代本地文件 | 多实例共享、容量扩展 |
| 前端现代化 | React + TypeScript 重写 | 用户体验与可维护性 |

---

## 附录 B：模板与检查清单

### B.1 每批接口工作包模板

```markdown
# 工作包：第 N 批接口替换

- 接口清单：GET /api/customers/、POST /api/customers/、……
- 依赖：数据表、缓存、外部系统、后台任务
- 行为一致性要点：排序 / 时间格式 / Decimal / 错误格式 / 权限
- 对照测试：用例数 X，覆盖成功与各类失败
- 互认测试：（如涉及）密码 / JWT / 缓存 / 文件
- 差异登记：D-xxx
- 切换计划：路由变更内容、观察期、回退方式
- 负责人：
```

### B.2 对照验收记录模板

```markdown
# 第 N 批接口对照验收记录 YYYY-MM-DD

- Java 版本：<commit / 镜像标签>；Python 版本：<commit>
- 环境与数据快照：<说明>
- 对照测试：用例 X 个，一致 X1 个，差异 X2 个（全部已登记：D-xxx）
- 忽略字段及原因：<列表>
- 互认测试：通过 / 不涉及
- 性能：Python 平均 / P95 = a / b ms；Java = c / d ms
- 镜像流量比对（只读接口）：<时长、请求数、差异数>
- 结论：可切换 / 不可切换（原因）
```

### B.3 总体检查清单

**盘点**
- [ ] 第 3.1 节盘点表全部完成
- [ ] 依赖替换表每一项都有结论
- [ ] 接口清单含真实请求/响应样本
- [ ] 确定保留 Python 的部分（Django Admin、ML 等）

**骨架**
- [ ] 只引入原系统已有能力对应的依赖
- [ ] 实体映射全部现有表，`ddl-auto: validate` 启动通过
- [ ] Flyway 只做基线，结构零改动
- [ ] JSON、日期、Decimal、错误格式、分页结构与原系统一致
- [ ] 环境变量名、日志格式、健康检查路径与原系统一致

**认证**
- [ ] 共存期认证方案（`auth_request` 或 JWT 互认）验证通过
- [ ] 密码哈希双向互认测试通过
- [ ] CSRF、Cookie 名称与属性一致
- [ ] 权限判断逐接口对照

**任务**
- [ ] 每个后台任务的生产者/消费者同批切换计划
- [ ] 定时任务 cron、时区一致，切换顺序明确

**验收与切换**
- [ ] 每批接口对照测试通过、差异已登记
- [ ] 每批切换都有回退演练
- [ ] 全部切换后观察期无回退
- [ ] Python 下线，共存期专用配置和接口已删除
- [ ] 输出迁移总结（替换清单、差异清单、遗留事项）
