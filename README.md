# 多生产组织 SN 防重管控

总部按金蝶生产订单的物料行统一生成 SN，分配到订单的生产组织：**对接 MES 的组织由本系统分批推送（MES 也可主动取批），未对接的在页面领取**；每一批都有回执或人工处理的出口，**SN 全局不重复、同一枚号不会重复交付**。

- 前端：Vue 3 + TypeScript + Vite + Element Plus（简体中文 / English / Tiếng Việt）
- 后端：Python 3.12 + FastAPI + PyMySQL + structlog（`app/{api,core,db,middleware,models,schemas,services}`；SQL 直接写在 services 里）
- 数据库：**MySQL 8**（启动时自动建表，Flyway 兼容的 `flyway_schema_history`；操作痕迹按月分区）；业务时间一律按 UTC 存储，页面按生产组织的时区显示
- 外部：金蝶 K3 Cloud 生产订单 / 组织机构（只读同步到本地快照）；各生产组织的 MES（推送 + 拉取 + 回执）

## 文档

各文档的定位与阅读顺序见 [docs/README.md](docs/README.md)。

| 文档 | 内容 |
| --- | --- |
| [需求文档（PRD）](<docs/SN 防重管控产品需求文档PRD.md>) | 业务规则的唯一来源（V30）：功能 F-01 ～ F-23、通用规则 5.2、待确认事项、配置项与实现要点 |
| [业务流程说明](<docs/SN 防重管控业务说明.md>) | 给业务方的流程说明：订单 → 选规则 → 生成 → 分配 → 交付到现场，订单变化、暂停交付、转单与转组织；附录为生产订单同步规则 |
| [MES 对接说明（精简版）](<docs/SN 防重管控 · MES 对接说明（精简版）.md>) | 给 MES 方的接口契约：推送、换令牌、取批、回执、只读核对、报文字段、处理逻辑 |
| [系统技术方案](docs/系统技术方案.md) | 正式评审稿（Word 版用 `docs/build_tech_docx.py` 生成）：总体、数据、功能、非功能设计 |
| [运维手册](docs/运维手册.md) | MySQL 准备、`.env`、启动方式、上线步骤、清库重建、排障 |

## 快速开始

```bash
cp .env.example .env     # 填 DB_HOST / DB_PORT / DB_NAME（开发 sn_dev、测试 sn_test、正式 sn_prod）/ DB_USER / DB_PASSWORD、SN_SECRET（≥ 32 位随机串）、K3_*
python start.py          # Windows 可双击 start.bat；需要 Python 3.12+、Node.js 20+
```

浏览器打开 `http://127.0.0.1:8000`，用 `.env` 里的 `SN_INIT_ADMIN_EMP_NO` + `SN_INIT_ADMIN_PASSWORD`（默认 `admin` / `Admin@123`）登录。只有这个内置管理员能建、改账户；同一账户只在最后登录的那个页面有效。
本机没有金蝶时可设 `SN_DEMO_SEED=true` 灌入演示数据（**连公司库务必关闭**）。

> 建表脚本 `V1__init.sql` 已按 PRD V30 重写，旧的演示 / 测试库不能原地升级，须清空本系统的表后重建（见运维手册 8.1）。

服务器：`docker compose up -d --build`（详见运维手册）。接口文档：`/docs`（MES 接口在 `open` 分组）。

## 功能一览

| 页面 | 角色 | 功能 |
| --- | --- | --- |
| 首页 | 全部（按可看范围） | 各状态枚数（待分配只给总部看）、暂停交付、推送中断、拉取未回执、待处理转组织、订单变更提醒、交付告警、今日作业量、最近生成任务与最近失败批次 |
| 生产订单 | 总部、查询员 | 金蝶快照（只含已审核单据）：左表按单据汇总，右表物料行（附件项次、物料、长度、数量、已生成、可生成、生效规则）；进页触发一次比对同步，另可按单据同步、立即比对、全量重建；附件项次、长度为空的标红「缺失」（在生成页填写）；订单变更提醒（PI / 生产组织 / 客户 / 数量 / 物料 / 附件项次变化、反审核、删除）与处理，相关单据暂停交付 |
| SN 生成 | 总部 | 每张 PI 一行（待生成 / 生成中 / 已生成完）；详情按单据 → 物料行展示，金蝶缺失的附件项次（必填）和长度（选填，默认 000，按字符串原样写入 SN）在物料行里直接填写，物料行有号后置灰（撤销生成后可改，分配后不能改），规则按「物料行选定 ＞ PI 绑定 ＞ 客户默认」生效，可从规则库选或自定义（已用于别的 PI 的版本须换新前缀）；勾选单据或物料行作为范围，按物料行整行预演、生成（不填数量，大批量后台执行、可取消）；行末改规则、撤销，也可勾选后批量：从流水最末往前撤到所选行为止（流水在其后的行一并撤）；只撤未分配的号 |
| SN 分配 | 总部 | 左侧 PI 列表（待处理 / 已分配完），右侧待分配的号按物料行列出（号段、分到的生产组织、交付方式），勾选物料行或整张 PI 分配到订单的生产组织（对接 MES 的自动分批推送，未对接的待领取）；推送进度与续推；分配记录（未确认、失败的批次人工确认或作废） |
| 规则库 | 总部 | 规则（前缀 + 可选长度 + 流水 + 后缀，10 / 16 / 32 / 36 进制）只做分类（未分类 / 客户 / PI）；前缀全局不能相同（只是开头相同如 SD 与 SDD 可以）；用过的版本改格式须另出一版并换新前缀 |
| 页面领取 | 全部（查询员只读） | 每个「生产组织 + PI」一行，按 PI 整批领取（请求号幂等，超过 5000 枚分段认领，中途失败可重试）并下载领取文件；对接 MES 的组织只能领取未入批的号；领取批次可反复下载 |
| 转单与转组织 | 全部（转单仅总部；查询员只读） | 转单：整单作废（VOID）或拆单（SPLIT），号随单转到目标单据；转组织：生产组织申请、总部确认 / 驳回或直接转；已交付 MES 的号按改派、新号或转出批次通知新旧 MES；转出号清单供来源现场作废旧标签 |
| SN 与批次查询 | 全部 | SN 明细（按组织 / 客户 / PI / 单据 / 物料 / 长度 / 状态 / 交付方式 / 批次 / 流水范围筛选，导出）；交付批次（推送、拉取、转出）明细与逐枚回执，总部人工确认或作废；领取批次；交付告警（冲突、版本过期、自检问题）处理 |
| 操作痕迹 | 全部（按范围） | 登录、账户、生产组织与 MES 配置、订单同步与变更、规则、生成、分配、推送、拉取、回执、领取、转单、转组织全流程留痕（含失败）；筛选、导出；保存期与归档 |
| 账户 / 生产组织 | 总部（账户仅内置管理员） | 页面账户（系统管理员 / 生产组织操作员 / 查询员）与 MES 接口账户（绑一个对接 MES 的组织，密钥只显示一次，可设来源地址白名单，连续认证失败锁定）；生产组织从金蝶同步，总部为每个组织配置 MES 对接（地址、认证方式、凭证加密保存、批次大小、超时、并发、时区），连接测试通过后才能开启 |

MES 接口（`/api/mes/v1`）：`POST /auth/token`、`POST /batches/next`、`POST /batches/{batchNo}/receipt`、`GET /sn/delivered`、`GET /batches/{batchNo}/items`；本系统推送到 MES 提供的地址。契约见 [MES 对接说明（精简版）](<docs/SN 防重管控 · MES 对接说明（精简版）.md>)。

## 开发与测试

```bash
uv sync && uv run uvicorn app.main:app --reload --port 8000   # 后端（读仓库根 .env）
cd frontend && npm install && npm run dev      # 前端 5173，代理 /api 到 8000

uv run pytest -q                               # 单元 + 集成测试（集成测试需 MySQL，默认 127.0.0.1:3306/sndb_test_py，会整库重建，不要指向 sn_dev / sn_test / sn_prod；连不上自动跳过）
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
tests/                       pytest：单元测试 + 集成测试（真实 MySQL + 假金蝶 + 假 MES）
frontend/                    Vue 3 + Vite + Element Plus
  src/views/                 各页面
  scripts/                   多语言词条源表（ui_messages.py 公共、ui/*.py 各页面、error_messages.py 错误码）与生成脚本
docs/                        需求文档（PRD）、业务流程说明、MES 对接说明、系统技术方案、运维手册；k3/ 金蝶接口参考
.env.example                 配置模板（.env 不提交）
pyproject.toml / uv.lock     Python 依赖
Dockerfile / frontend/Dockerfile / docker-compose.yml
start.py / start.bat / stop.bat
```
