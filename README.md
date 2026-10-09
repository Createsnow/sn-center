# 多工厂 SN 防重管控

总部统一生成并分配 SN（可代任一工厂领取、打印、转厂），工厂按 PI 领取、打印；**同一张 PI 下的 SN 不得重复**。

- 前端：Vue 3 + TypeScript + Vite + Element Plus（简体中文 / English / Tiếng Việt）
- 后端：Python 3.12 + FastAPI + PyMySQL + structlog（目录结构与 `main` 分支相同：`app/{api,core,db,middleware,models,schemas,services}`；SQL 直接写在 services 里，不另设仓储层）
- 数据库：**MySQL 8**（启动时自动建表，Flyway 兼容的 `flyway_schema_history`；SN 明细与操作痕迹按月分区）
- 外部：金蝶 K3 Cloud 生产订单 / 组织机构（只读同步到本地快照）

## 文档

各文档的定位、主题以哪份为准、按角色的阅读顺序、改代码时要同步哪些文档：见 [docs/README.md](docs/README.md)。

| 文档 | 内容 |
| --- | --- |
| [需求说明（修订版）](docs/多工厂SN防重管控-需求说明（修订版）.md) | 业务口径 |
| [系统技术方案](docs/系统技术方案.md) | 正式评审稿（另有 Word 版）：总体架构、数据架构、功能设计、非功能设计；附录 A 为完整配置项 |
| [关键节点说明](docs/关键节点说明.md) | 业务视角：同步 → 预演 → 生成 → 分配 → 领取打印 → 转厂，每个节点的进入条件、结果与断点续做 |
| [MES 对接接口文档](docs/MES对接接口文档.md) | 给 MES / 打印系统的对外接口契约 |
| [技术文档](docs/技术文档.md) | 代码结构、权限矩阵、状态机、时序图、金蝶调用、接口清单、需求落地说明 |
| [数据库表设计](docs/数据库表设计.md) | ER 图、每张表字段与索引、分区与归档、容量估算、性能测试与设计决定 |
| [运维手册](docs/运维手册.md) | MySQL 准备、`.env`、三种启动方式、上线步骤、排障 |

## 快速开始

```bash
cp .env.example .env     # 填 DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD、SN_SECRET（≥ 32 位随机串）、K3_*
python start.py          # Windows 可双击 start.bat；需要 Python 3.12+、Node.js 20+
```

浏览器打开 `http://127.0.0.1:8000`，用 `.env` 里的 `SN_INIT_ADMIN_EMP_NO` + `SN_INIT_ADMIN_PASSWORD`（默认 `admin` / `Admin@123`）登录，再在「账户」页创建其他账户。
本机没有金蝶时可设 `SN_DEMO_SEED=true` 灌入演示工厂、规则、订单与账户（**连公司库务必关闭**）。

服务器：`docker compose up -d --build`（详见运维手册；Docker 方式为 `ENVIRONMENT=production`，`SN_SECRET` 为空、为示例值或不足 32 位会拒绝启动）。接口文档：`/docs`（对外接口在 `open` 分组）。

## 功能一览

| 页面 | 角色 | 功能 |
| --- | --- | --- |
| 首页 | 全部 | 在途状态枚数、待处理转厂、今日作业量、最近生成任务 |
| 生产订单 | 总部、查询员 | 金蝶快照：左表按单据汇总数量 / 已生成 / 可生成，右表物料行；全量或按单据同步；可开启定时全量同步（总部设置间隔 10 分钟–7 天） |
| 生成与分配 | 总部 | 每张 PI 一行（可搜 PI / 客户 / 单据 / PO，默认只看有额度或待分配的），行上一个下一步按钮：「生成 N 枚」或「分配 N 枚」。点 PI 打开抽屉：PI 流水与规则、首次生成时选规则；数量停顿后自动预演，按单据号顺序占用各订单额度、号段接续；整张 PI 在一个事务里生成，任何一张失败全部回滚（大批量后台任务 + 进度）；整张 PI 分配时各订单分到各自的生产组织，也可只分配其中一张订单 |
| 规则模板 | 总部 | 通用（可多条）/ 按客户 / 按 PI，绑定可修改、改回通用即解绑；生成时有绑定用绑定，没绑定从全部通用规则中选，可指定给 PI（首次生成自动指定）、可解绑重选；未用过的版本就地修改，用过的另出一版，改名不出版本 |
| 领取与打印 | 全部（查询员只读） | 每张 PI 一行，显示「待领取 → 待打印」进度，行上按钮执行下一步：按「工厂 + PI」整批领取、打印（下载打印文件）；可勾选多张 PI 批量操作（批量按钮与行按钮一致：每张 PI 只算进它行按钮那一步）；点 PI 打开号段（按「工厂 + PI + 物料 + 连续号段 + 状态」分行，可只读看各厂分布、按物料带入转厂） |
| 转厂 | 全部（查询员只读） | 转厂单列表（总部默认看待处理）；新建时按「转哪些号 → 转到哪里 → 原因」填写，转入目标从订单快照搜索或手工填写，右侧实时校验；工厂申请 / 撤回；总部确认 / 驳回 / 直接转移；整张 PI、PI + 物料、单枚 SN 三种范围；转出厂可查已转出的号 |
| SN 查询 | 全部 | SN 明细：按工厂 / 客户 / PI / 物料 / 状态 / SN / 来源订单 / 批次筛选，厂区可只读查看同 PI 各厂，导出 xlsx / csv；领取批次、打印记录：按批次号 / 打印单号 / 请求号 / 来源（页面或 MES 接口）查找，看批次明细、重新下载打印文件 |
| 操作痕迹 | 全部（按范围） | 账户、规则、SN、转厂全流程留痕（含失败）；筛选、导出；保存期与归档 |
| 账户 / 工厂 | 总部 | 工号唯一、只停用不删除、重置密码；工厂从金蝶同步或手工建档 |

对外接口（MES / 打印系统）：`POST /api/open/acquire`（按 PI 整批领取，请求号幂等）、`GET /api/open/batches/{batch_no}/items`（分页明细）、`POST /api/open/callback`（打印回调）、`GET /api/open/sn`（只读拉取）。

## 开发与测试

```bash
uv sync && uv run uvicorn app.main:app --reload --port 8000   # 后端（读仓库根 .env）
cd frontend && npm install && npm run dev      # 前端 5173，代理 /api 到 8000

uv run pytest -q                               # 单元 + 集成测试（集成测试需 MySQL，默认 127.0.0.1:3306/sndb_test_py，连不上自动跳过）
uv run ruff check app tests                    # 代码检查
node --test frontend/src/*.test.js             # 前端测试
python3 frontend/scripts/gen_locales.py        # 改了词条 / 错误码后重新生成三种语言文件
```

## 目录

```
app/                         FastAPI 后端
  main.py                    应用装配、错误处理、单端口托管前端、启动任务
  api/                       deps.py（鉴权依赖）、routes/（各接口）
  core/                      配置、.env、错误码、异常、SN 编码、口令与令牌、日志、定时任务
  db/                        MySQL 连接池与事务、建表迁移、migration/V1__init.sql
  middleware/ models/ schemas/ services/
tests/                       pytest：单元测试 + 集成测试（真实 MySQL + 假金蝶）
frontend/                    Vue 3 + Vite + Element Plus
  src/views/                 各页面
  scripts/                   多语言词条源表与生成脚本
docs/                        需求、技术文档、数据库表设计、部署说明
.env.example                 配置模板（.env 不提交）
pyproject.toml / uv.lock     Python 依赖
Dockerfile / frontend/Dockerfile / docker-compose.yml
start.py / start.bat / stop.bat
```
