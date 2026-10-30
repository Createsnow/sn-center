# SN 防重管控系统 · MES 对接接口规范 V1.2

| 项目 | 内容 |
| --- | --- |
| 文档版本 | V1.2 |
| 协议版本 | `schemaVersion = 1.0` |
| 更新日期 | 2026-10-29 |
| 适用对象 | MES 方开发人员、SN 系统对接人员 |
| 相关文档 | [MES 业务接口逻辑](MES业务接口逻辑.md)：各接口的业务逻辑、异常与重试、人工处理；[MES 对接说明](MES对接说明.md)：MES 收到批次后的处理规则 |
| 修订记录 | V1.2（2026-10-29）：按代码校对——拉取接口 `pi`、`billNo`、`batchNo` 不是字符串返回 400 `INVALID_REQUEST`；查询接口 `cursor` 不是上一页的 `nextCursor` 返回 400（不再当作首页）；3.1.4 示例字段更正为 `receivedAt`；3.2 未确认批次补「已收到、等回执的」<br />V1.1（2026-10-29）：推送接口应答码统一为 `OK`（连接测试相同）；新增 3.3.6 回执送达确认；回执接口补充失败批次再次提交的返回；全文改用接口名称（推送接口、拉取接口、回执接口、查询接口） |
| MES 接口负责人 | MES 侧填写：姓名、联系方式 |
|                |                                 |

## 目录

- [1. 概述](#1-概述)
  - [1.1 接口清单](#11-接口清单)
  - [1.2 调用时序](#12-调用时序)
- [2. 接入规范](#2-接入规范)
  - [2.1 通信协议（含认证）](#21-通信协议含认证)
  - [2.2 数据格式](#22-数据格式)
  - [2.3 幂等与重试](#23-幂等与重试)
  - [2.4 限流](#24-限流)
- [3. 接口说明](#3-接口说明)
  - [3.1 推送接口](#31-推送接口)
  - [3.2 拉取接口](#32-拉取接口)
  - [3.3 回执接口](#33-回执接口)
  - [3.4 查询已下发 SN 接口](#34-查询已下发-sn-接口)
  - [3.5 查询批次明细接口](#35-查询批次明细接口)
- [附录 A 数据字典](#附录-a-数据字典)
- [附录 B 错误码](#附录-b-错误码)

---

## 1. 概述

### 1.1 接口清单

| 接口 | 用途 | 调用方向 | 方法 | 地址 | 调用时机 |
| --- | --- | --- | --- | --- | --- |
| 推送接口 | 推送批次 | SN 系统 → **MES** | `POST` | `{MES 地址}`（MES 提供） | 分配、转移、续推后；连接测试 |
| 拉取接口 | 拉取批次 | MES → SN 系统 | `POST` | `/api/mes/v1/batches/next` | 推送失败后、本地 SN 不足、每 5 分钟兜底 |
| 回执接口 | 提交回执 | MES → SN 系统 | `POST` | `/api/mes/v1/receipts` | 推送来的、拉取到的批次落库后（每批都要） |
| 查询已下发 SN 接口 | 对账 | MES → SN 系统 | `POST` | `/api/mes/v1/sn/delivered` | 可选，对账 |
| 查询批次明细接口 | 对账 | MES → SN 系统 | `POST` | `/api/mes/v1/batches/items` | 可选，对账 |

拉取、回执、查询接口一律 `POST` + JSON 请求体，参数都放在请求体中，地址中不带参数；两个查询接口虽为 `POST`，仍是只读接口。

**批次确认以回执为准**：推送接口推送批次时 MES 收到即应答「已收到」（`code = OK`，只表示收到，不表示已处理），处理并落库后调回执接口，并以回执接口的响应确认回执已送达（3.3.6）；SN 系统收到回执 `OK` 才确认这一批、推送下一批（同一生产组织、同一 PI 的批次串行推送），收到 `NG` 停止推送并在总部页面显示原因。

**两个「OK」不要混淆**：推送接口应答的 `code = OK` 只表示 MES **已收到**；回执接口请求里的 `code = OK` 表示 MES **已处理**；回执接口响应里的 `code = OK` 表示 SN 系统**已收到这份回执**（批次结果看 `batchResult`）。

### 1.2 调用时序

![图 1-1 业务接口调用时序](images/MES对接说明/f14-business-calls.png)

---

## 2. 接入规范

表中「MES 侧」列为待 MES 方书面确认的事项，确认后以回复为准；`—` 表示无需填写。

### 2.1 通信协议（含认证）

| 序号 | 事项 | SN 系统 | MES 侧 |
| --- | --- | --- | --- |
| 1 | 推送接口地址（测试环境） | http://10.0.200.17:8052/ | |
| 2 | 推送接口地址（正式环境） |  | |
| 3 | 推送接口多组织地址 | 多个生产组织可共用一个地址，按请求体 `orgCode` 区分 | |
| 4 | 协议 | HTTPS；不跟随 3xx 跳转 |  |
| 5 | 推送接口 HTTPS 证书 |  | |
| 6 | 字符集 | UTF-8 |  |
| 7 | 数据格式 | JSON，`Content-Type: application/json` |  |
| 8 | 请求压缩 | 带 `Content-Encoding: gzip` 的请求体为 gzip，先解压再解析。推送接口默认发原始 JSON，生产组织开启「gzip 压缩」后才发 gzip；拉取接口、查询接口请求体可为 gzip；回执接口报文很小，**不支持 gzip**，须发原始 JSON | 推送接口 ☐ 支持 ☐ 不支持 |
| 9 | 响应压缩 | 请求带 `Accept-Encoding: gzip` 时，SN 系统对 1 KB 以上的响应做 gzip；回执接口的响应不足 1 KB，不压缩 |  |
| 10 | 推送接口单批大小 | 默认 ≤ 5000 枚 | 枚数、报文大小上限： |
| 11 | 推送接口超时 | SN 系统等待应答 60 秒；MES 收到即可应答已收到（`OK`），落库后再调回执接口。应答已收到后 SN 系统等回执 1、5、10 分钟，超时按原批次重推（最多 3 次）；建议 5000 枚 10 秒内落库并回执 | 处理时长： |
| 12 | 认证方式 | 工号 + 密钥 |  |
| 13 | 认证请求头 | `Authorization: Bearer <工号>:<密钥>`，如 `Bearer SN110:7f3c9a1e5b2d4f60` |  |
| 14 | 认证校验 | 核对工号、密钥 |  |
| 15 | 来源 IP |  |  |
| 16 | 凭证交付 | 线下交付 |  |
| 17 | 认证失败 | 401：缺少请求头，或工号、密钥错误<br />403：账户已停用<br />其他 |  |

### 2.2 数据格式

| 项目 | 约定 |
| --- | --- |
| 字段命名 | 英文小驼峰，如 `batchNo` |
| 时间 | **UTC+8**，格式 `yyyy-MM-dd HH:mm:ss.SSS`，如 `2026-10-26 11:47:05.303`；批次号中的日期同样按 UTC+8 |
| SN | ≤ 64 位，仅字母、数字、`-`、`.`、`_`；区分大小写 |
| 字符串型数字 | `serialDec`、`length` 按字符串保存，不得转数字（防止丢失前导 0 或溢出） |
| 类型标注 | `String(n)` 最长 n 个字符；`Number` 为 JSON 整数；`\| null` 表示可为 `null` |
| 兼容性 | 后续只增加字段、不修改已有字段；**不认识的字段直接忽略** |
| 协议版本 | SN 系统发出的批次和响应带 `schemaVersion`；MES 发出的请求、回执无需携带 |

### 2.3 幂等与重试

- **幂等键 `batchNo`**
  - 同一批次可能重复送达，也可能分别经推送接口和拉取接口到达，MES 只处理一次
  - 重复到达时，`itemsDigest` 相同：经推送接口到达的应答已收到，并按首次的处理结果**重新提交回执**（首次的回执可能没交上来）；经拉取接口取到的直接提交首次回执。不同返回 409 `BATCH_CONTENT_MISMATCH`
  - `X-Request-Id`、`X-Sn-Attempt` 每次送达都会变，只供日志，**不得用于去重**
- **SN 系统重送批次（推送接口）**
  - 没送达：超时、网络中断、5xx、429、409 `BATCH_IN_PROGRESS`，1、5、15 分钟后以**相同内容**重送；MES 响应带 `Retry-After`（秒）时取两者中较大值
  - 已收到但没有回执：应答已收到后等回执 1 分钟，超时以**相同内容**重送、再等 5 分钟，再重送、再等 10 分钟，再重送、再等 10 分钟（共重送 3 次）
  - 不重送：400 / 401 / 403 / 404 / 413、409 `BATCH_CONTENT_MISMATCH`、HTTP 200 但 `code` 不是 `OK` 或 `batchNo` 不是这一批
  - 重送用尽仍没有回执：停止推送，批次留待 MES 补交回执或经拉取接口拉取；等待期间后续批次不推。MES 补交后这一批即确认，后续批次由总部「继续推送」或 MES 经拉取接口取得
  - SN 系统收到回执即停止重送，与是否正在等待无关
- **MES 重试拉取、回执、查询接口**
  - 超时、5xx：直接重试；拉取接口在回执前返回同一批次，回执接口以同一回执重交直到送达（3.3.6；SN 系统未收到回执时会经推送接口重送，MES 照常应答已收到并重交回执），查询接口以同一 `cursor` 重查
  - 429：按 `Retry-After` 等待后重试
  - 其他 4xx：按错误码处理，不要原样重试

### 2.4 限流

拉取、回执、查询接口合计，每个生产组织每分钟 60 次（查询接口每页算一次）。超出返回 429 `RATE_LIMITED`，响应头 `Retry-After` 为需等待的秒数：

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 18

{"schemaVersion": "1.0", "code": "RATE_LIMITED", "message": "请求过于频繁", "batchNo": null, "timestamp": "2026-10-26 11:48:42.006", "details": []}
```

---

## 3. 接口说明

每个接口按同一顺序说明：基本信息（接口完整地址、请求头、请求参数、响应体结构）→ 请求示例 → 成功示例 → 失败示例 → 错误码。各接口的错误码只列重点；认证、限流、参数不合法等通用错误码见附录 B。

### 3.1 推送接口

SN 系统向 MES 推送一个批次。MES 收到后**立即应答「已收到」**（`code = OK`，只表示收到），处理并落库后调 **回执接口**（3.3），并确认回执已送达（3.3.6）；SN 系统收到回执 `OK` 才确认这一批并推送下一批。

```
SN 系统 ──推送接口：批次──▶ MES     （应答：已收到 OK）
                                    MES 校验、落库
SN 系统 ◀──回执接口：OK / NG── MES  （OK 已处理 / NG 整批退回）
SN 系统 ──回执接口的响应──▶ MES     （200 + OK：回执已送达；否则 MES 重交）
SN 系统 ──推送接口：下一批──▶ MES …
```

#### 3.1.1 基本信息

| 接口完整地址 |
| --- |
|  |

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Content-Type` | 是 | `application/json; charset=utf-8` | 固定 |
| `Accept` | 是 | `application/json` | 固定 |
| `Accept-Encoding` | 是 | `gzip` | 响应可为 gzip |
| `Content-Encoding` | 否 | `gzip` | 开启「gzip 压缩」时才带 |
| `Authorization` | 是 | `Bearer SN110:7f3c9a1e5b2d4f60` | 工号 + 密钥（2.1）；生产组织认证方式为「无认证」时不带 |
| `X-Sn-Attempt` | 是 | `1` | 第几次送达，仅供日志 |
| `X-Request-Id` | 是 | UUID | 每次送达都不同，仅供日志，**不得用于去重** |

**请求体结构**

各类批次（`batchType`，附录 A）共用同一个请求体，`items[]` 每条都带下列 12 个字段。批次头的 `toOrgCode` 只有跨组织转出批次（`TRANSFER_OUT`）有值（一批只转到一个组织），其他批次为 `null`；`count` 为 `items` 条数，MES 核对不符时整批退回；测试批次（`PING`，连接测试时发送）不带 `pi`，`count` 为 0，`items` 为 `[]`。

```jsonc
{
  "schemaVersion": "1.0",                    // String           必填  协议版本，固定 1.0
  "batchNo": "PS-110-20261026-000123",       // String(40)       必填  批次号，全局唯一；MES 幂等键
  "batchType": "NEW",                        // String           必填  批次类型（附录 A），决定 MES 怎样处理
  "orgCode": "110",                          // String(64)       必填  收件生产组织编码；转移批次为原组织
  "toOrgCode": null,                         // String(64)|null  必填  转入生产组织编码；仅 TRANSFER_OUT 有值，其他为 null
  "pi": "000001",                            // String(64)       必填  本批的 PI，一批只含一张 PI；转移批次为转出前的 PI
  "count": 1,                                // Number           必填  明细条数，等于 items 条数；不符时整批退回
  "createdAt": "2026-10-26 11:47:05.000",    // String           必填  建批时间
  "itemsDigest": "sha256:9f2c4e…",           // String(71)       必填  明细指纹（sha256: + 64 位十六进制）；同一 batchNo 再次到达时按字符串比对，不必重算
  "items": [                                 // Array            必填  SN 明细，≤ 5000 条
    {
      "sn": "HX100000001A",                  // String(64)       必填  完整 SN
      "rev": 1,                              // Number           必填  挂靠版本，正整数，越大越新
      "serial": "000001",                    // String(12)       必填  流水号（可能为 36 进制写法）
      "serialDec": "1",                      // String(20)       必填  十进制流水，按字符串保存
      "serialPi": "000001",                  // String(64)       必填  流水所属 PI；转入的 SN 可能与 pi 不同
      "pi": "000001",                        // String(64)       必填  当前所属 PI，与批次头 pi 相同
      "billNo": "VT2026090001",              // String(64)       必填  单据号（EEP 生产订单号）
      "billSeq": 1,                          // Number           必填  单据行号
      "attachNo": "A01",                     // String(64)|null  必填  附件项次，生成时写定，转单、转组织不变
      "materialCode": "9148363",             // String(64)|null  必填  物料编码
      "length": "0100",                      // String(64)|null  必填  米数，原样保存（未补 0）
      "customerCode": "0001"                 // String(64)       必填  客户编码
    }
  ]
}
```

**响应体结构**

MES 收到批次后应答「已收到」；处理结果不在这里返回，由 MES 落库后调回执接口：

```jsonc
{
  "batchNo": "PS-110-20261026-000123",       // String(40)       必填  照抄请求体的批次号
  "code": "OK",                              // String           必填  OK 已收到（只表示收到，处理结果由回执接口提交）；HTTP 非 200 时填错误码（3.1.5）
  "message": "已收到",                       // String           必填  说明
  "receivedAt": "2026-10-26 11:47:05.215"    // String           必填  MES 收到时间
}
```

测试批次（`PING`）不落库、**不调回执接口**：与其他批次一样应答 `OK` 即连接测试通过；`orgCode` 不是本组织时应答 `NG`。

#### 3.1.2 请求示例

```http
POST /sn/receive HTTP/1.1
Host: mes-test.example.com
Content-Type: application/json; charset=utf-8
Accept: application/json
Accept-Encoding: gzip
Authorization: Bearer SN110:7f3c9a1e5b2d4f60
X-Sn-Attempt: 1
X-Request-Id: 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed

{
  "schemaVersion": "1.0",
  "batchNo": "PS-110-20261026-000123",
  "batchType": "NEW",
  "orgCode": "110",
  "toOrgCode": null,
  "pi": "000001",
  "count": 1,
  "createdAt": "2026-10-26 11:47:05.000",
  "itemsDigest": "sha256:9f2c4e…（64 位十六进制）",
  "items": [
    {
      "sn": "HX100000001A", "rev": 1, "serial": "000001", "serialDec": "1", "serialPi": "000001",
      "pi": "000001", "billNo": "VT2026090001", "billSeq": 1, "attachNo": "A01", "materialCode": "9148363",
      "length": "0100", "customerCode": "0001"
    }
  ]
}
```

同一批次每次送达，请求体逐字节相同，只有 `X-Sn-Attempt`、`X-Request-Id` 会变。

#### 3.1.3 成功示例

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"batchNo": "PS-110-20261026-000123", "code": "OK", "message": "已收到", "receivedAt": "2026-10-26 11:47:05.215"}
```

随后 MES 落库，调回执接口（3.3.2）：

```json
{"batchNo": "PS-110-20261026-000123", "code": "OK", "message": "已处理", "processedAt": "2026-10-26 11:47:07.215"}
```

#### 3.1.4 失败示例

**整批退回**：照常应答已收到，再以回执 `code = NG` 退回（`message` 为原因）

```json
{"batchNo": "PS-110-20261026-000123", "code": "NG", "message": "本组织 110 没有 PI 000001", "processedAt": "2026-10-26 11:47:06.008"}
```

**同一批次正在处理**（HTTP 409，SN 系统稍后重送）

```http
HTTP/1.1 409 Conflict
Content-Type: application/json; charset=utf-8
Retry-After: 60

{"batchNo": "PS-110-20261026-000123", "code": "BATCH_IN_PROGRESS", "message": "批次正在处理", "receivedAt": "2026-10-26 11:47:05.900"}
```

#### 3.1.5 错误码

| HTTP | `code` | 场景 | SN 系统处理 |
| --- | --- | --- | --- |
| 200 | `OK` | 已收到 | 等回执：`OK` 批次确认、推下一批；`NG` 批次记失败、停止推送并显示原因；1、5、10 分钟没有回执以相同内容重送（最多 3 次） |
| 200 | 其他 | `code` 不是 `OK`（含在应答里直接回 `NG`）、`batchNo` 不是这一批 | 应答无效，不重送，批次记失败 |
| 409 | `BATCH_IN_PROGRESS` | 同一批次正在处理 | 稍后重送 |
| 409 | `BATCH_CONTENT_MISMATCH` | 批次号相同、`itemsDigest` 不同 | 不重送，告警 |
| 429 / 5xx / 超时 | 由 MES 方定义 | MES 繁忙、故障或网络中断 | 1、5、15 分钟后以相同内容重送 |
| 其他 4xx | 由 MES 方定义 | 报文、认证或地址有误，完全无法处理 | 不重送 |

业务问题（缺字段、`orgCode` 不符、本组织没有该 PI 等）一律应答已收到，再以回执 `NG` 整批退回，不要用 4xx。

---

### 3.2 拉取接口

MES 主动拉取一个批次：优先返回范围内最早的未回执批次（推送后未确认的——未送达、等重试或已收到等回执，拉取后未回执的，转出批次），没有时才新建；整批退回（失败）的批次不在其中，须带 `batchNo` 取回。

#### 3.2.1 基本信息

| 接口完整地址 |
| --- |
| `POST http://10.0.200.17:8052/api/mes/v1/batches/next` |

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 是 | `Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF` | 工号 + 密钥（2.1） |
| `Content-Type` | 否 | `application/json` | 有请求体时填写 |
| `Content-Encoding` | 否 | `gzip` | 请求体为 gzip 时填写 |
| `Accept-Encoding` | 否 | `gzip` | 响应可为 gzip |
| `X-Request-ID` | 否 | UUID | 请求号，SN 系统在响应头原样返回；不带时由 SN 系统生成 |

**请求体结构**

筛选条件均为选填，不限范围时发 `{}` 或空请求体。

```jsonc
{
  "pi": "000001",                            // String(64)       选填  限定 PI。从失败处开始：范围内最早的未确认或失败批次 → 号池新建（有失败批次的 PI 不建）；失败批次只挡它所在的 PI
  "billNo": "VT2026090001",                  // String(64)       选填  限定单据；可与 pi 同时使用
  "batchNo": null                            // String(40)       选填  取回指定批次（如整批退回后修正重处理）；填写后 pi、billNo 不生效，不新建批次
}
```

**响应体结构**

```jsonc
{
  "schemaVersion": "1.0",                    // String           总是    协议版本
  "code": "OK",                              // String           总是    OK 返回了一个批次；NO_BATCH 暂无可拉取批次（HTTP 200，不是错误，停止拉取）；出错为错误码
  "message": "成功",                          // String           总是    说明；NO_BATCH 时为「暂无可拉取批次」，有暂停时注明枚数
  "batchNo": "PL-110-20261026-000046",       // String|null      总是    本次返回的批次号；NO_BATCH 时为 null
  "timestamp": "2026-10-26 11:48:15.442",    // String           总是    响应时间
  "batch": {                                 // Object|null      仅成功  批次，结构与推送接口请求体相同（3.1.1）；OK 时一定有值，NO_BATCH 时为 null
    "schemaVersion": "1.0",
    "batchNo": "PL-110-20261026-000046",
    "batchType": "NEW",
    "orgCode": "110",
    "toOrgCode": null,                       //                          同推送接口；仅 TRANSFER_OUT 为转入组织
    "pi": "000001",                          //                          同推送接口；转移批次为转出前的 PI
    "count": 1,                              //                          同推送接口，明细条数
    "createdAt": "2026-10-26 11:48:15.400",
    "itemsDigest": "sha256:c07e3a…",
    "items": [ … ]                           //                          批次明细，同推送接口，≤ 5000 条
  },
  "remaining": 1200,                         // Number           仅成功  范围内剩余可拉取枚数；OK 且 > 0 时继续拉取（NO_BATCH 时一律停止）
  "paused": 0,                               // Number           仅成功  范围内暂停下发的枚数
  "details": []                              // Array            仅出错  错误明细 [{field, reason}]
}
```

#### 3.2.2 请求示例

```http
POST /api/mes/v1/batches/next HTTP/1.1
Host: sn.example.com
Authorization: Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF
Content-Type: application/json
Accept-Encoding: gzip
X-Request-ID: 7d3e9a10-5c2b-4f8e-b1a4-0c6d2e8f9b57

{"pi": "000001", "billNo": null, "batchNo": null}
```

#### 3.2.3 成功示例

**有批次**

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 7d3e9a10-5c2b-4f8e-b1a4-0c6d2e8f9b57

{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "成功",
  "batchNo": "PL-110-20261026-000046",
  "timestamp": "2026-10-26 11:48:15.442",
  "batch": {
    "schemaVersion": "1.0",
    "batchNo": "PL-110-20261026-000046",
    "batchType": "NEW",
    "orgCode": "110",
    "toOrgCode": null,
    "pi": "000001",
    "count": 1,
    "createdAt": "2026-10-26 11:48:15.400",
    "itemsDigest": "sha256:c07e3a…",
    "items": [
      {
        "sn": "HX100005001A", "rev": 1, "serial": "005001", "serialDec": "5001", "serialPi": "000001",
        "pi": "000001", "billNo": "VT2026090001", "billSeq": 1, "attachNo": "A01", "materialCode": "9148363",
        "length": "0100", "customerCode": "0001"
      }
    ]
  },
  "remaining": 1200,
  "paused": 0
}
```

**暂无可拉取批次**（HTTP 200，`code = NO_BATCH`，不是错误：停止拉取，等下次触发）

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 0c4f6a2e-8b1d-4e3a-9f57-6d2c1b0a9e84

{"schemaVersion": "1.0", "code": "NO_BATCH", "message": "暂无可拉取批次，30 枚暂停下发", "batchNo": null, "timestamp": "2026-10-26 11:53:00.118", "batch": null, "remaining": 0, "paused": 30}
```

#### 3.2.4 失败示例

**指定的批次不存在**（HTTP 404）

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
X-Request-ID: 3e8a1f5c-2d7b-4c96-a0e4-9b6f2c1d8a73

{"schemaVersion": "1.0", "code": "BATCH_NOT_FOUND", "message": "批次号不存在，或不属于本组织", "batchNo": "PL-110-20261026-000099", "timestamp": "2026-10-26 11:48:15.442", "details": []}
```

**指定的批次已回执**（HTTP 409）

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
X-Request-ID: 5b2d9c7e-1a4f-4e8b-b3c6-0f9a8d7e6c51

{"schemaVersion": "1.0", "code": "BATCH_CONFIRMED", "message": "该批已确认，不用再处理", "batchNo": "PL-110-20261026-000046", "timestamp": "2026-10-26 11:48:16.020", "details": []}
```

#### 3.2.5 错误码

| HTTP | `code` | 场景 | 处理 |
| --- | --- | --- | --- |
| 200 | `NO_BATCH` | 不是错误：范围内暂无可拉取批次，`batch` 为 `null`（带 `batchNo` 取回指定批次时不会出现） | 停止拉取，等下次触发 |
| 400 | `INVALID_REQUEST` | 请求体不是合法 JSON / gzip，或 `pi`、`billNo`、`batchNo` 不是字符串 | 按 `details` 修正请求 |
| 403 | `ORG_NOT_ALLOWED` | 该 PI 下没有分到本组织的 SN，也没有本组织的转移批次 | 检查 `pi` |
| 404 | `BATCH_NOT_FOUND` | 指定的批次号不存在，或不属于本组织 | 检查 `batchNo` |
| 409 | `BATCH_CONFIRMED` | 指定的批次已回执 | 无需再处理 |
| 409 | `BATCH_VOIDED` | 指定的批次已作废 | 丢弃，不得用本地缓存处理 |

---

### 3.3 回执接口

对推送来的、拉取到的批次提交处理结果（含整批退回）。**每一批都要提交**：推送接口的应答只表示已收到，SN 系统收到回执才确认批次、推送下一批；没有回执时 SN 系统等 1、5、10 分钟后经推送接口重送（2.3）。

#### 3.3.1 基本信息

| 接口完整地址 |
| --- |
| `POST {SN 系统地址}/api/mes/v1/receipts` |

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 是 | `Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF` | 工号 + 密钥（2.1） |
| `Content-Type` | 是 | `application/json` | 固定 |
| `X-Request-ID` | 否 | UUID | 请求号，SN 系统在响应头原样返回；不带时由 SN 系统生成 |

本接口不支持 gzip：请求体须为原始 JSON，不得带 `Content-Encoding: gzip`（带了返回 400 `INVALID_REQUEST`）；响应不足 1 KB，不压缩，无需带 `Accept-Encoding`。

**请求体结构**

MES 的回执：

```jsonc
{
  "batchNo": "PL-110-20261026-000046",       // String(40)       必填  所回执的批次号（推送接口请求体或拉取接口取到的 batch.batchNo），按原样填写
  "code": "OK",                              // String           必填  OK 已处理 / NG 整批退回，只能为这两个值
  "message": "已处理",                       // String           必填  处理说明；NG 时为退回原因，显示在总部页面
  "processedAt": "2026-10-26 11:48:20.117"   // String           必填  MES 处理完成时间
}
```

**响应体结构**

```jsonc
{
  "schemaVersion": "1.0",                    // String           总是    协议版本
  "code": "OK",                              // String           总是    成功为 OK，出错为错误码
  "message": "回执已确认，批次成功",           // String           总是    受理结果说明（见下表）
  "batchNo": "PL-110-20261026-000046",       // String           总是    所回执的批次号
  "timestamp": "2026-10-26 11:48:20.305",    // String           总是    响应时间
  "batchResult": "SUCCESS",                  // String           仅成功  SN 系统中该批次的结果：SUCCESS / FAILED
  "firstConfirmation": true,                 // Boolean          仅成功  true 本次回执已生效（首次确认，或整批退回已登记）；false 此前已确认，本次只存档
  "details": []                              // Array            仅出错  错误明细 [{field, reason}]
}
```

| 情形 | `message` | `batchResult` | `firstConfirmation` |
| --- | --- | --- | --- |
| 首次提交 `OK` | 回执已确认，批次成功 | `SUCCESS` | `true` |
| 首次提交 `NG` | 整批退回已登记，批次失败 | `FAILED` | `true` |
| 已确认后再次提交 | 批次此前已确认，本次回执只存档 | 首次确认的结果 | `false` |
| 整批退回后修正，再提交 `OK`（如经拉取接口带 `batchNo` 取回重处理） | 回执已确认，批次成功 | `SUCCESS` | `true` |
| 整批退回后再次提交 `NG` | 整批退回已登记，批次失败 | `FAILED` | `true` |

以上情形 HTTP 都是 200、`code` 都是 `OK`，表示回执已送达（3.3.6）。

#### 3.3.2 请求示例

```http
POST /api/mes/v1/receipts HTTP/1.1
Host: sn.example.com
Authorization: Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF
Content-Type: application/json

{"batchNo": "PL-110-20261026-000046", "code": "OK", "message": "已处理", "processedAt": "2026-10-26 11:48:20.117"}
```

#### 3.3.3 成功示例

**首次确认**

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 4d1e8b2a-6c3f-4a97-b5e0-8f2d1c0b9a63

{"schemaVersion": "1.0", "code": "OK", "message": "回执已确认，批次成功", "batchNo": "PL-110-20261026-000046", "timestamp": "2026-10-26 11:48:20.305", "batchResult": "SUCCESS", "firstConfirmation": true}
```

**重复提交**

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 8b5f2c9d-3e1a-4d76-a4c8-1e0f9d8c7b52

{"schemaVersion": "1.0", "code": "OK", "message": "批次此前已确认，本次回执只存档", "batchNo": "PL-110-20261026-000046", "timestamp": "2026-10-26 11:49:02.771", "batchResult": "SUCCESS", "firstConfirmation": false}
```

#### 3.3.4 失败示例

**参数不合法**（HTTP 400）：`batchNo` 缺失或不是字符串、`code` 不是 `OK` / `NG`、请求体为 gzip。批次状态不变，按 `details` 改正后重交

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
X-Request-ID: 6f1c2a9e-3b7d-4c55-9a0e-2d4f8b1e7c33

{"schemaVersion": "1.0", "code": "INVALID_REQUEST", "message": "参数不合法：code", "batchNo": "PL-110-20261026-000046", "timestamp": "2026-10-26 11:48:20.305", "details": [{"field": "code", "reason": "只能是 OK / NG"}]}
```

**批次不存在**（HTTP 404）：批次号不存在、不属于本组织，或与下发的批次号不完全一致（区分大小写、不得带空格）

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
X-Request-ID: 2a7d4e1b-8c3f-4e96-b5a0-9d8c7b6a5f43

{"schemaVersion": "1.0", "code": "BATCH_NOT_FOUND", "message": "批次号不存在，或不属于本组织", "batchNo": "PL-110-20261026-000099", "timestamp": "2026-10-26 11:48:20.305", "details": []}
```

**批次已作废**（HTTP 409）

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
X-Request-ID: 1c7e4a9b-5d2f-4b68-9e03-7a6b5c4d3e21

{"schemaVersion": "1.0", "code": "BATCH_VOIDED", "message": "批次已作废，不得再处理这批内容", "batchNo": "PL-110-20261026-000046", "timestamp": "2026-10-26 11:48:20.305", "details": []}
```

#### 3.3.5 错误码

| HTTP | `code` | 场景 | 处理 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | `batchNo` 缺失或不是字符串、`code` 不是 `OK` / `NG`、请求体为 gzip；批次状态不变 | 按 `details` 改正后重交 |
| 404 | `BATCH_NOT_FOUND` | 批次号不存在，或不属于本组织 | 检查 `batchNo` |
| 409 | `BATCH_VOIDED` | 批次已作废 | 丢弃，不得用本地缓存处理 |

#### 3.3.6 回执送达确认（MES 侧）

MES 调回执接口后，**以回执接口的响应判断回执是否送达 SN 系统**，并在本地记下每批回执的提交状态（待提交 / 已送达 / 送达失败 / 已作废）：

| 回执接口的响应 | 判定 | MES 处理 |
| --- | --- | --- |
| HTTP 200 + `code = OK`，且 `batchNo` 与提交的一致 | **已送达**（`firstConfirmation` 为 `true` 或 `false` 都算） | 记为「已送达」，结束 |
| 超时、断连、5xx | 未送达（SN 系统可能收到也可能没收到） | 用**同一份回执**重交：立即 1 次，之后 10 秒、1 分钟、5 分钟，再每 5 分钟 1 次，直到送达 |
| 429 `RATE_LIMITED` | 未送达 | 按 `Retry-After` 等待后重交 |
| 400 `INVALID_REQUEST` | 送达失败（回执有误，批次状态不变） | 按 `details` 改正后重交 |
| 401 / 403 / 423 | 送达失败（账户问题） | 告警，核对 MES 接口账户后重交 |
| 404 `BATCH_NOT_FOUND` | 送达失败 | 告警，人工核对 `batchNo`，不自动重交 |
| 409 `BATCH_VOIDED` | 批次已作废 | 记为「已作废」，丢弃本批，不再重交 |

- **先存后交**：回执先写入 MES 本地（待提交），再调回执接口；MES 重启后继续提交未送达的回执。
- **不确定时直接重交**：回执接口按批次号幂等，重交不会重复处理；已确认的批次返回 `firstConfirmation = false`，同样表示已送达。
- **SN 系统没收到回执的信号**：同一批次再次经推送接口送达，或拉取接口仍返回这一批，说明 SN 系统还没收到回执：照常应答已收到，并立即重交首次的回执。
- **一直送达不了**：SN 系统等回执超时（1、5、10 分钟，经推送接口重送 3 次）后停止推送，批次在总部「交付批次」页显示为等回执超时；MES 补交成功后批次即确认，后续批次由总部「继续推送」或 MES 经拉取接口拉取。

---

### 3.4 查询已下发 SN 接口

返回 SN 系统认定已下发给本组织 MES 的 SN（含推送和拉取），已转至其他组织的不返回。仅用于对账，**不得据此入库、打标或删除**。

#### 3.4.1 基本信息

| 接口完整地址 |
| --- |
| `POST {SN 系统地址}/api/mes/v1/sn/delivered` |

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 是 | `Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF` | 工号 + 密钥（2.1） |
| `Content-Type` | 是 | `application/json` | 固定 |
| `Content-Encoding` | 否 | `gzip` | 请求体为 gzip 时填写 |
| `Accept-Encoding` | 否 | `gzip` | 响应可为 gzip，建议填写 |
| `X-Request-ID` | 否 | UUID | 请求号，SN 系统在响应头原样返回；不带时由 SN 系统生成 |

**请求体结构**

均为选填，不限 PI、取首页时发 `{}`。

```jsonc
{
  "pi": "000001",                            // String(64)|null  选填  限定 PI；不填不限
  "cursor": null,                            // String|null      选填  分页游标：首页不填或填 null，之后原样填上一页的 nextCursor，直到 nextCursor = null；其他值返回 400
  "pageSize": 1000                           // Number|null      选填  每页条数，默认 1000，最大 5000
}
```

**响应体结构**

```jsonc
{
  "schemaVersion": "1.0",                    // String           总是    协议版本
  "code": "OK",                              // String           总是    成功为 OK，出错为错误码
  "message": "成功",                          // String           总是    说明
  "batchNo": null,                           // null             总是    本接口不涉及单个批次，固定为 null
  "timestamp": "2026-10-26 12:00:00.512",    // String           总是    响应时间
  "items": [                                 // Array            仅成功  本页 SN；无数据时为 []
    {
      "sn": "HX100000001A",                  // …                必有    批次明细的 12 个字段（3.1.1），此处从略
      "status": "PUSHED",                    // String           必有    SN 状态（附录 A）
      "batchNo": "PS-110-20261026-000123",   // String|null      必有    当前所在批次
      "deliveredAt": "2026-10-26 11:47:07.000" // String|null    必有    确认已下发的时间
    }
  ],
  "nextCursor": "10086",                     // String|null      仅成功  下一页游标；null 表示最后一页
  "details": []                              // Array            仅出错  错误明细 [{field, reason}]
}
```

#### 3.4.2 请求示例

```http
POST /api/mes/v1/sn/delivered HTTP/1.1
Host: sn.example.com
Authorization: Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF
Content-Type: application/json
Accept-Encoding: gzip

{"pi": "000001", "cursor": null, "pageSize": 1000}
```

下一页在请求体中填上一页的 `nextCursor`，如 `{"pi": "000001", "cursor": "10086", "pageSize": 1000}`。

#### 3.4.3 成功示例

**有下一页**

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 2e9b6d3a-8c1f-4a75-b0e6-4d3c2b1a0f98

{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "成功",
  "batchNo": null,
  "timestamp": "2026-10-26 12:00:00.512",
  "items": [
    {
      "sn": "HX100000001A", "rev": 2, "serial": "000001", "serialDec": "1", "serialPi": "000001",
      "pi": "000001", "billNo": "VT2026090001", "billSeq": 1, "attachNo": "A01", "materialCode": "9148363",
      "length": "0100", "customerCode": "0001",
      "status": "PUSHED", "batchNo": "PS-110-20261026-000123", "deliveredAt": "2026-10-26 11:47:07.000"
    }
  ],
  "nextCursor": "10086"
}
```

**最后一页**

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 7a4c1e8b-2d6f-4b93-a5e7-0c9b8a7d6e45

{"schemaVersion": "1.0", "code": "OK", "message": "成功", "batchNo": null, "timestamp": "2026-10-26 12:00:01.204", "items": [], "nextCursor": null}
```

#### 3.4.4 失败示例

**参数不合法**（HTTP 400）

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
X-Request-ID: 5e2a9c6d-1b8f-4d37-9a40-3f2e1d0c9b86

{"schemaVersion": "1.0", "code": "INVALID_REQUEST", "message": "参数不合法：pageSize", "batchNo": null, "timestamp": "2026-10-26 12:00:00.512", "details": [{"field": "pageSize", "reason": "须为整数"}]}
```

#### 3.4.5 错误码

无专有错误码，只返回通用错误码（附录 B）；`cursor` 不是上一页返回的 `nextCursor`、`pageSize` 不是整数时返回 400 `INVALID_REQUEST`。

---

### 3.5 查询批次明细接口

返回指定批次中仍属于该批次的 SN（已转至其他组织的不返回）。仅用于对账。

#### 3.5.1 基本信息

| 接口完整地址 |
| --- |
| `POST {SN 系统地址}/api/mes/v1/batches/items` |

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 是 | `Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF` | 工号 + 密钥（2.1） |
| `Content-Type` | 是 | `application/json` | 固定 |
| `Content-Encoding` | 否 | `gzip` | 请求体为 gzip 时填写 |
| `Accept-Encoding` | 否 | `gzip` | 响应可为 gzip，建议填写 |
| `X-Request-ID` | 否 | UUID | 请求号，SN 系统在响应头原样返回；不带时由 SN 系统生成 |

**请求体结构**

```jsonc
{
  "batchNo": "PS-110-20261026-000123",       // String(40)       必填  批次号
  "cursor": null,                            // String|null      选填  分页游标，用法同查询已下发 SN 接口
  "pageSize": 1000                           // Number|null      选填  每页条数，默认 1000，最大 5000
}
```

**响应体结构**：同查询已下发 SN 接口（3.4.1），区别仅在 `batchNo` 为所查批次号。

#### 3.5.2 请求示例

```http
POST /api/mes/v1/batches/items HTTP/1.1
Host: sn.example.com
Authorization: Bearer M110001:Xk9pQ2vL7sRtY3wZ8aBcD4eF
Content-Type: application/json
Accept-Encoding: gzip

{"batchNo": "PS-110-20261026-000123", "cursor": null, "pageSize": 1000}
```

#### 3.5.3 成功示例

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-ID: 3b8e5a2c-9d1f-4c64-a7b3-6e5d4c3b2a19

{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "成功",
  "batchNo": "PS-110-20261026-000123",
  "timestamp": "2026-10-26 12:01:10.031",
  "items": [
    {
      "sn": "HX100000001A", "rev": 2, "serial": "000001", "serialDec": "1", "serialPi": "000001",
      "pi": "000001", "billNo": "VT2026090001", "billSeq": 1, "attachNo": "A01", "materialCode": "9148363",
      "length": "0100", "customerCode": "0001",
      "status": "PUSHED", "batchNo": "PS-110-20261026-000123", "deliveredAt": "2026-10-26 11:47:07.000"
    }
  ],
  "nextCursor": null
}
```

#### 3.5.4 失败示例

**批次不存在**（HTTP 404）

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
X-Request-ID: 6d3a0f8c-2e5b-4a71-9c46-1b0a9f8e7d52

{"schemaVersion": "1.0", "code": "BATCH_NOT_FOUND", "message": "批次号不存在，或不属于本组织", "batchNo": "PS-110-20261026-000999", "timestamp": "2026-10-26 12:01:10.031", "details": []}
```

#### 3.5.5 错误码

| HTTP | `code` | 场景 | 处理 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 缺少 `batchNo`，`cursor`、`pageSize` 类型不对，或 `cursor` 不是上一页返回的 `nextCursor` | 修正请求 |
| 404 | `BATCH_NOT_FOUND` | 批次号不存在，或不属于本组织 | 检查 `batchNo` |

---

## 附录 A 数据字典

**batchType 批次类型**（一批只有一种；业务含义见《MES 对接说明》1.3、3.3）

| 取值 | 说明 | 批次号前缀 | 批次头的 `toOrgCode` |
| --- | --- | --- | --- |
| `NEW` | 首次下发 | `PS`（推送时建）/ `PL`（拉取时建） | `null` |
| `SAME_ORG_TRANSFER` | 同组织转移 | 同上 | `null` |
| `TRANSFER_IN` | 跨组织转入 | 同上 | `null` |
| `REMAP` | 订单变更改挂、告警重推 | 同上 | `null` |
| `TRANSFER_OUT` | 跨组织转出（发给原组织） | `XO` | 转入组织 |
| `PING` | 连接测试 | `PING` | `null`（`items` 为 `[]`） |

**推送接口应答 code**

| 取值 | 说明 |
| --- | --- |
| `OK` | 已收到（只表示收到；处理结果由回执接口提交）。连接测试 `PING` 同样应答 `OK` |

**回执 code**（回执接口请求体）

| 取值 | 说明 |
| --- | --- |
| `OK` | 整批已处理（含逐枚跳过的旧版本） |
| `NG` | 整批退回，未写入任何数据 |

**batchResult 批次结果**（回执接口响应）

| 取值 | 说明 |
| --- | --- |
| `SUCCESS` | 成功（回执 `OK`） |
| `FAILED` | 失败（回执 `NG`） |

**status SN 状态**（查询接口响应）

| 取值 | 说明 |
| --- | --- |
| `PUSHED` | 已推送（MES 已确认） |
| `TO_PUSH` | 改挂后待推送 |
| `APPLYING` | 转组织申请中，仅提醒 |

---

## 附录 B 错误码

SN 系统返回（拉取、回执、查询接口）的全部错误码如下；MES 在推送接口中的响应码见 3.1.5。HTTP 200 时 `code` 为 `OK`，拉取接口暂无可拉取批次时为 `NO_BATCH`（不是错误，见 3.2.5），其余都是错误码。

| HTTP | `code` | 接口 | 说明 | 处理 |
| --- | --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 拉取、回执、查询接口 | 请求体不是合法 gzip / JSON（回执接口不支持 gzip），或参数缺失、类型不对、取值不合法（`details` 指出字段） | 修正请求 |
| 401 | `INVALID_CREDENTIALS` | 拉取、回执、查询接口 | 缺少认证头，或工号、密钥错误 | 核对后重试 |
| 403 | `IP_NOT_ALLOWED` | 拉取、回执、查询接口 | 来源 IP 不在白名单 | 联系总部 |
| 403 | `ACCOUNT_DISABLED` | 拉取、回执、查询接口 | 账户已停用 | 联系总部 |
| 403 | `FORBIDDEN` | 拉取、回执接口 | 非 MES 接口账户 | 改用 MES 接口账户 |
| 403 | `ORG_NOT_ALLOWED` | 拉取接口 | 该 PI 下没有分到本组织的 SN，也没有本组织的转移批次 | 检查 `pi` |
| 404 | `BATCH_NOT_FOUND` | 拉取、回执、查询批次明细接口 | 批次号不存在，或不属于本组织 | 检查 `batchNo` |
| 409 | `BATCH_CONFIRMED` | 拉取接口 | 批次已回执 | 无需再处理 |
| 409 | `BATCH_VOIDED` | 拉取、回执接口 | 批次已作废 | 丢弃，不得用本地缓存处理 |
| 423 | `ACCOUNT_LOCKED` | 拉取、回执、查询接口 | 连续认证失败已锁定 | 15 分钟后再试 |
| 429 | `RATE_LIMITED` | 拉取、回执、查询接口 | 调用过于频繁 | 按 `Retry-After` 等待 |
| 5xx / 超时 | — | 拉取、回执、查询接口 | SN 系统故障或网络中断 | 直接重试（2.3） |
