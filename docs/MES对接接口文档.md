# SN 防重管控 · MES 对接接口文档

> 本文只定义接口：怎么调、传什么、回什么。MES 收到批次后的处理规则（rev 比对、批次类型、转移、异常处理）见 [MES 对接说明](MES对接说明.md)。

## 目录

- [1. 概述](#1-概述)
- [2. 公共约定](#2-公共约定)
  - [2.1 传输与编码](#21-传输与编码)
  - [2.2 数据格式](#22-数据格式)
  - [2.3 认证与签名](#23-认证与签名)
  - [2.4 应答格式](#24-应答格式)
  - [2.5 限流](#25-限流)
- [3. 接口定义](#3-接口定义)
  - [3.1 ① 接收批次](#31--接收批次)
  - [3.2 ③ 拉取批次](#32--拉取批次)
  - [3.3 ④ 提交回执](#33--提交回执)
  - [3.4 ⑤ 查询已下发 SN](#34--查询已下发-sn)
  - [3.5 ⑥ 查询批次明细](#35--查询批次明细)
- [4. 数据字典](#4-数据字典)
- [5. 错误码](#5-错误码)
- [修订记录](#修订记录)

---

## 1. 概述

SN 系统把分配给生产组织的 SN 分批下发给该组织的 MES：主通道为 SN 系统推送（①），补偿通道为 MES 拉取（③ → ④），⑤⑥ 供 MES 对账。协议版本 `1.0`。

| # | 接口 | 调用方 → 提供方 | 方法与地址 | 调用时机 |
| --- | --- | --- | --- | --- |
| ① | 接收批次 | SN 系统 → **MES** | `POST {MES 地址}` | 分配、转移、续推后；连接测试 |
| ③ | 拉取批次 | MES → SN 系统 | `POST /api/mes/v1/batches/next` | 推送失败后、本地 SN 不足、每 5 分钟兜底 |
| ④ | 提交回执 | MES → SN 系统 | `POST /api/mes/v1/batches/{batchNo}/receipt` | ③ 拉取的批次落库后 |
| ⑤ | 查询已下发 SN | MES → SN 系统 | `GET /api/mes/v1/sn/delivered` | 可选，对账 |
| ⑥ | 查询批次明细 | MES → SN 系统 | `GET /api/mes/v1/batches/{batchNo}/items` | 可选，对账 |

![图 1-1 业务接口调用时序](images/MES对接说明/f14-business-calls.png)

| 阶段 | 触发 | 调用的接口 |
| --- | --- | --- |
| 1 上线前 | 总部填写 MES 配置，点「连接测试」 | ①（`PING`） |
| 2 分配后推送 | 总部分配 SN | ①（`NEW`），MES 在应答中回执 |
| 3 拉取补偿 | 推送失败、本地 SN 不足、每 5 分钟兜底 | ③ 取批 → ④ 回执，`remaining > 0` 时继续 |
| 4 转移 | 总部转单 / 转组织 | 转入方 ①（`SAME_ORG_TRANSFER` / `TRANSFER_IN`）；原组织 ①（`TRANSFER_OUT`） |
| 5 对账 | MES 按需 | ⑤ / ⑥ |

整批退回（`NG`）后的重处理流程见 [MES 对接说明](MES对接说明.md) 图 5-1。

- ① 的回执在同一应答中返回；④ 只用于 ③ 拉取的批次
- 推送与拉取的批次结构完全相同；同一批次可能经两条通道到达，MES 按 `batchNo` 只处理一次
- 编号沿用历史编号（② 已移除）

---

## 2. 公共约定

### 2.1 传输与编码

| 项目 | 约定 |
| --- | --- |
| 协议 | HTTPS，不跟随 3xx 跳转 |
| 格式 | JSON，UTF-8；请求带 `Content-Encoding: gzip` 时请求体为 gzip，先解压再解析 |
| 应答压缩 | 请求带 `Accept-Encoding: gzip` 时应答可 gzip；SN 系统对 1 KB 以上的应答压缩 |
| ① 请求体 | 默认原始 JSON；生产组织开启「gzip 压缩」时为 gzip（开启前须确认 MES 支持解压） |

### 2.2 数据格式

| 项目 | 约定 |
| --- | --- |
| 字段命名 | 英文小驼峰 |
| 时间 | 国内时间 **UTC+8**，格式 `yyyy-MM-dd HH:mm:ss.SSS`，如 `2026-10-26 11:47:05.303`；批次号中的日期同样按 UTC+8 |
| SN | ≤ 64 位，仅字母、数字、`-`、`.`、`_`；区分大小写 |
| 字符串型数字 | `serialDec`、`length` 按字符串保存，不得转数字（防止丢失前导 0 或溢出） |
| 兼容性 | 后续只增加字段、不修改已有字段；**不认识的字段直接忽略** |
| 协议版本 | SN 系统发出的批次和应答带 `schemaVersion`；MES 发出的请求、回执无需携带 |

### 2.3 认证与签名

**① SN 系统 → MES（待 MES 方填写）**

① 由 MES 方提供，目前尚未给出地址与认证方式。请 MES 方填写下表并书面回复，确认后更新本节和 3.1 的请求头。

| # | 项目 | MES 方填写 | 说明 / SN 系统已支持的选项 |
| --- | --- | --- | --- |
| 1 | 接口地址（测试环境） | | 完整 URL，HTTPS |
| 2 | 接口地址（正式环境） | | 同上 |
| 3 | 多个生产组织是否共用一个地址 | | 共用时按报文 `orgCode` 区分 |
| 4 | 认证方式 | | A 固定令牌 / B HMAC 签名（推荐），见下方参考；其他方式请写明 |
| 5 | 认证请求头名称 | | A：`Authorization: Bearer <令牌>`；B：`X-Sn-Timestamp`、`X-Sn-Signature` |
| 6 | 签名算法与签名串 | | B 默认见下方参考 |
| 7 | 时间戳格式与允许偏差 | | 默认 `yyyy-MM-dd HH:mm:ss.SSS`（UTC+8），偏差 ≤ 5 分钟 |
| 8 | 凭证交付方式 | | 令牌 / 密钥线下交付，不写入文档 |
| 9 | 是否限制来源 IP | | 需要时由 SN 系统提供服务器出口 IP |
| 10 | HTTPS 证书 | | 公网 CA 签发 / 自签（自签须提供 CA 证书） |
| 11 | 是否支持 gzip 请求体 | | 默认发原始 JSON |
| 12 | 单批枚数、报文大小上限 | | 默认单批 ≤ 5000 枚 |
| 13 | 处理时长 | | SN 系统默认等待 60 秒；建议 5000 枚 10 秒内处理完 |
| 14 | 接口负责人及联系方式 | | |

**SN 系统已支持的认证方式（参考）**

| 方式 | 请求头 | MES 校验 |
| --- | --- | --- |
| A 固定令牌（内网） | `Authorization: Bearer <令牌>` | 核对令牌，限制来源为 SN 系统服务器 IP |
| B HMAC 签名（公网，推荐） | `X-Sn-Timestamp`、`X-Sn-Signature` | 时间差 ≤ 5 分钟；重算签名，常量时间比对 |

```text
签名串 = X-Sn-Timestamp + "\n" + hex(SHA256(解压后的请求体字节))
签名   = hex(HMAC-SHA256(密钥, 签名串))        // 小写十六进制，UTF-8；时间戳按请求头原文参与签名
```

![图 2-1 HMAC 签名与校验](images/MES对接说明/f8-hmac.png)

**③～⑥ MES → SN 系统**

- 每次请求带 `Authorization: Bearer <工号>:<密钥>`，无需换取令牌；账户由总部在「账户 → MES 接口账户」创建后提供
- 账户只能访问所绑定的生产组织；可设来源 IP 白名单；连续认证失败 10 次锁定 15 分钟

### 2.4 应答格式

每次调用都有应答，应答固定带结果码、说明、相关批次号、时间；成功与出错结构相同。

**SN 系统的应答（③～⑥）**

```jsonc
{
  "schemaVersion": "1.0",                 // 必填，String，协议版本
  "code": "OK",                           // 必填，String，结果码：成功为 OK，出错为错误码（第 5 章）；程序按它判断
  "message": "成功",                       // 必填，String，中文说明，仅供阅读
  "batchNo": "PL-110-20261026-000046",    // 必填，String | null，相关批次号；不涉及批次时为 null
  "timestamp": "2026-10-26 11:47:05.512", // 必填，String，应答时间
  "details": []                           // 仅出错时有，Array，错误明细 [{field, reason}]，无明细时为空数组
  // 成功时另带各接口的数据字段，见第 3 章
}
```

- 成功：HTTP 200 且 `code = OK`；出错：HTTP 状态码一定不是 200

**MES 的应答（① 应答、④ 请求体，统称「回执」）**

```jsonc
{
  "batchNo": "PS-110-20261026-000123",       // 必填，String，照抄批次号
  "code": "OK",                              // 必填，String，OK 已处理 / NG 整批退回 / 出错时为错误码（第 5 章）
  "message": "已处理",                        // 必填，String，处理说明；NG 时为退回原因，显示在总部页面
  "processedAt": "2026-10-26 11:47:05.303"   // 必填，String，MES 处理完成时间；重复送达时返回首次处理的回执
}
```

### 2.5 限流

③～⑥ 每个生产组织每分钟 60 次；超出返回 429 `RATE_LIMITED`，按应答头 `Retry-After` 秒数等待。

---

## 3. 接口定义

### 3.1 ① 接收批次

SN 系统向 MES 推送一个批次，MES 处理并落库后在同一应答中返回回执。**由 MES 方提供，地址与认证方式待书面确认。**

`POST {MES 地址}`

![图 3-1 推送与回执](images/MES对接说明/f6-push.png)

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Content-Type` | 是 | `application/json; charset=utf-8` | 固定 |
| `Content-Encoding` | 否 | `gzip` | 开启「gzip 压缩」时才带 |
| `Accept-Encoding` | 是 | `gzip` | 应答可 gzip |
| `Authorization` | 方式 A | `Bearer 7f3c…` | 见 2.3（待 MES 方确认） |
| `X-Sn-Timestamp` | 方式 B | `2026-10-26 11:47:05.303` | 签名时间，见 2.3（待 MES 方确认） |
| `X-Sn-Signature` | 方式 B | `a3f9…`（64 位小写十六进制） | HMAC 签名，见 2.3（待 MES 方确认） |
| `X-Sn-Attempt` | 是 | `1` | 第几次送达，仅供日志 |
| `X-Request-Id` | 是 | UUID | 请求编号，仅供日志，不得用于去重 |

**请求体：交付批次**（`batchType` 为 `NEW` / `SAME_ORG_TRANSFER` / `TRANSFER_IN` / `REMAP`）

```jsonc
{
  "schemaVersion": "1.0",                    // 必填，String，协议版本
  "batchNo": "PS-110-20261026-000123",       // 必填，String(40)，批次号，全局唯一；MES 幂等键
  "batchType": "NEW",                        // 必填，String，批次类型，见数据字典
  "orgCode": "110",                          // 必填，String(64)，收件生产组织编码
  "createdAt": "2026-10-26 11:47:05.000",    // 必填，String，建批时间
  "itemsDigest": "sha256:9f2c4e…",           // 必填，String(71)，明细内容指纹；同一 batchNo 再次到达时按字符串比对
  "items": [                                 // 必填，Array，SN 明细，≤ 5000 条
    {
      "sn": "HX100000001A",                  // 必填，String(64)，完整 SN
      "rev": 1,                              // 必填，Number，挂靠版本，正整数，越大越新
      "serial": "000001",                    // 必填，String(12)，流水号（可能为 36 进制写法）
      "serialDec": "1",                      // 必填，String(20)，十进制流水
      "serialPi": "000001",                  // 必填，String(64)，流水所属 PI；转入的 SN 可能与 pi 不同
      "pi": "000001",                        // 必填，String(64)，当前所属 PI；一个交付批次只含一张 PI
      "billNo": "VT2026090001",              // 必填，String(64)，单据号（EEP 生产订单号）
      "billSeq": 1,                          // 必填，Number，单据行号
      "materialCode": "9148363",             // 必填，String(64) | null，物料编码
      "length": "0100",                      // 必填，String(32) | null，米标，原样保存
      "customerCode": "0001"                 // 必填，String(64)，客户编码
    }
  ]
}
```

**请求体：转移批次**（`batchType = TRANSFER_OUT`，跨组织转移后发给原组织）

```jsonc
{
  "schemaVersion": "1.0",
  "batchNo": "XO-110-20261026-000007",
  "batchType": "TRANSFER_OUT",
  "orgCode": "110",                          // 原组织（收件方）
  "createdAt": "2026-10-26 11:50:00.000",
  "itemsDigest": "sha256:51ab07…",
  "items": [
    {
      "sn": "HX100000003A",                  // 必填，String(64)，完整 SN
      "rev": 3,                              // 必填，Number，挂靠版本
      "toOrgCode": "120"                     // 必填，String(64)，转入生产组织编码
    }
  ]
}
```

**请求体：测试批次**（`batchType = PING`，总部点「连接测试」时发送）

```json
{"schemaVersion": "1.0", "batchNo": "PING-20261026114705303", "batchType": "PING", "orgCode": "110", "createdAt": "2026-10-26 11:47:05.303", "itemsDigest": "sha256:…", "items": []}
```

**应答**：回执（2.4）

```json
{"batchNo": "PS-110-20261026-000123", "code": "OK", "message": "已处理", "processedAt": "2026-10-26 11:47:07.215"}
```

```json
{"batchNo": "PS-110-20261026-000123", "code": "NG", "message": "materialCode 超过本侧字段长度 20", "processedAt": "2026-10-26 11:47:06.008"}
```

**HTTP 状态码**

| 场景 | MES 返回 | SN 系统处理 |
| --- | --- | --- |
| 处理完成或整批退回 | 200 + 回执（`OK` / `NG`） | `OK`：批次确认；`NG`：批次记失败并显示原因 |
| 同一批次正在处理 | 409，`code = BATCH_IN_PROGRESS` | 稍后重送 |
| 批次号相同、内容不同 | 409，`code = BATCH_CONTENT_MISMATCH` | 不重送，告警 |
| 认证失败 | 401 / 403 | 不重送 |
| 地址错误 | 404 | 不重送 |
| 报文过大 | 413 | 不重送 |
| 报文无法解析 | 400 | 不重送 |
| 超时、5xx、网络中断、429、503 | — | 1、5、15 分钟后用相同内容重送 |

- 能拆包处理就返回 200 + 回执；缺字段、`orgCode` 不符等业务问题返回 200 + `NG`，4xx 只用于完全无法处理
- 非 200 应答体也按回执结构写明 `code`、`message`
- HTTP 200 但 `code` 不是 `OK` / `NG` 时，SN 系统记为应答无效，不重送

---

### 3.2 ③ 拉取批次

MES 主动拉取一个批次：优先返回范围内最早的未回执批次（推送未送达的、拉取后未回执的、转移批次），没有时才新建。

`POST /api/mes/v1/batches/next`

![图 3-2 拉取与提交回执](images/MES对接说明/f7-pull.png)

**请求头**

| 参数 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 是 | `Bearer M110001:xxxxxxxx` | 见 2.3 |
| `Content-Type` | 否 | `application/json` | — |
| `Content-Encoding` | 否 | `gzip` | 请求体 gzip 时填写 |

**请求体**

```jsonc
{
  "pi": "000001",    // 选填，String(64)，限定 PI
  "billNo": null,    // 选填，String(64)，限定单据；可与 pi 同时使用
  "batchNo": null    // 选填，String(40)，取回指定批次（如被退回后修正重处理）；填写后 pi、billNo 不生效，不新建批次
}
```

**应答**

```jsonc
{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "成功",                          // 无批次时为「暂无可拉取批次」（有暂停时注明枚数）
  "batchNo": "PL-110-20261026-000046",        // 本次返回的批次号；无批次时为 null
  "timestamp": "2026-10-26 11:48:15.442",
  "batch": { /* 批次，结构同 3.1 请求体 */ },   // Object | null，null 表示暂无可拉取批次，1 分钟以上再试
  "remaining": 1200,                         // Number，范围内剩余可拉取枚数；> 0 时继续拉取
  "paused": 0                                // Number，暂停下发的枚数
}
```

- 处理完成后立即调 ④ 提交回执；回执前重复拉取返回同一批次
- 错误码：`ORG_NOT_ALLOWED`、`BATCH_NOT_FOUND`、`BATCH_CONFIRMED`、`BATCH_VOIDED` 及公共错误码

---

### 3.3 ④ 提交回执

对 ③ 拉取的批次提交处理结果（含整批退回）。推送的批次已随 ① 应答回执，无需再提交。按批次号幂等。

`POST /api/mes/v1/batches/{batchNo}/receipt`

**请求头**：同 3.2。

**Path 参数**

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `batchNo` | String(40) | 是 | 批次号，须与请求体 `batchNo` 一致 |

**请求体**：回执（2.4）

```json
{"batchNo": "PL-110-20261026-000046", "code": "OK", "message": "已处理", "processedAt": "2026-10-26 11:48:20.117"}
```

**应答**

```jsonc
{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "回执已确认，批次成功",
  "batchNo": "PL-110-20261026-000046",
  "timestamp": "2026-10-26 11:48:20.305",
  "batchResult": "SUCCESS",                  // String，SN 系统中该批次的结果，见数据字典
  "firstConfirmation": true                  // Boolean，true 本次首次确认；false 此前已确认，本次只存档
}
```

- 请求体 `batchNo` 与路径不一致、`code` 不是 `OK` / `NG` 时返回 422 `RECEIPT_MISMATCH`
- 错误码：`RECEIPT_MISMATCH`、`BATCH_VOIDED`、`BATCH_NOT_FOUND` 及公共错误码

---

### 3.4 ⑤ 查询已下发 SN

返回 SN 系统认定已下发给本组织 MES 的 SN（含推送和拉取），已转至其他组织的不返回。仅用于对账，不得据此入库、打标或删除。

`GET /api/mes/v1/sn/delivered`

**请求头**：`Authorization: Bearer <工号>:<密钥>`

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `pi` | String(64) | 否 | 不限 | 限定 PI |
| `cursor` | String | 否 | 空 | 分页游标：首页留空，之后填上一页的 `nextCursor` |
| `pageSize` | Number | 否 | 1000 | 每页条数，最大 5000 |

**应答**

```jsonc
{
  "schemaVersion": "1.0",
  "code": "OK",
  "message": "成功",
  "batchNo": null,
  "timestamp": "2026-10-26 12:00:00.512",
  "items": [
    {
      "sn": "HX100000001A",                  // 以下 11 个字段同 3.1 交付批次明细
      "rev": 2,
      "serial": "000001",
      "serialDec": "1",
      "serialPi": "000001",
      "pi": "000001",
      "billNo": "VT2026090001",
      "billSeq": 1,
      "materialCode": "9148363",
      "length": "0100",
      "customerCode": "0001",
      "status": "PUSHED",                    // String，SN 状态，见数据字典
      "batchNo": "PS-110-20261026-000123",   // String | null，当前所在批次
      "deliveredAt": "2026-10-26 11:47:07.000" // String | null，确认已下发的时间
    }
  ],
  "nextCursor": "10086"                      // String | null，下一页游标；null 表示最后一页
}
```

- 错误码：公共错误码

---

### 3.5 ⑥ 查询批次明细

返回指定批次中仍属于该批次的 SN（已转至其他组织的不返回）。仅用于对账。

`GET /api/mes/v1/batches/{batchNo}/items`

**请求头**：同 3.4。

**参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `batchNo` | Path | String(40) | 是 | 批次号 |
| `cursor` | Query | String | 否 | 同 3.4 |
| `pageSize` | Query | Number | 否 | 同 3.4 |

**应答**：同 3.4，`batchNo` 为所查批次。

- 错误码：`BATCH_NOT_FOUND` 及公共错误码

---

## 4. 数据字典

**batchType 批次类型**（一批只有一种；业务含义见 MES 对接说明 1.3、3.3）

| 取值 | 说明 | 批次号前缀 | 明细 |
| --- | --- | --- | --- |
| `NEW` | 首次下发 | `PS`（推送时建）/ `PL`（拉取时建） | 交付明细 |
| `SAME_ORG_TRANSFER` | 同组织转移 | 同上 | 交付明细 |
| `TRANSFER_IN` | 跨组织转入 | 同上 | 交付明细 |
| `REMAP` | 订单变更改挂、告警重推 | 同上 | 交付明细 |
| `TRANSFER_OUT` | 跨组织转出（发给原组织） | `XO` | 转移明细 |
| `PING` | 连接测试 | `PING` | 空 |

**回执 code**

| 取值 | 说明 |
| --- | --- |
| `OK` | 整批已处理（含逐枚跳过的旧版本） |
| `NG` | 整批退回，未写入任何数据 |

**batchResult 批次结果**（④ 应答）

| 取值 | 说明 |
| --- | --- |
| `SUCCESS` | 成功（回执 `OK`） |
| `FAILED` | 失败（回执 `NG`） |

**status SN 状态**（⑤⑥ 应答）

| 取值 | 说明 |
| --- | --- |
| `PUSHED` | 已推送（MES 已确认） |
| `TO_PUSH` | 改挂后待推送 |
| `APPLYING` | 转组织申请中，仅提醒 |

---

## 5. 错误码

**SN 系统返回（③～⑥）**

| HTTP | `code` | 接口 | 说明 | 处理 |
| --- | --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | ③～⑥ | 请求体不是合法 gzip / JSON，或参数不合法 | 修正请求 |
| 401 | `INVALID_CREDENTIALS` | ③～⑥ | 缺少认证头，或工号、密钥错误 | 核对后重试 |
| 403 | `IP_NOT_ALLOWED` | ③～⑥ | 来源 IP 不在白名单 | 联系总部 |
| 403 | `ACCOUNT_DISABLED` | ③～⑥ | 账户已停用 | 联系总部 |
| 403 | `FORBIDDEN` | ③④ | 非 MES 接口账户 | 改用 MES 接口账户 |
| 403 | `ORG_NOT_ALLOWED` | ③ | 该 PI 下没有分到本组织的 SN，也没有本组织的转移批次 | 检查 `pi` |
| 404 | `BATCH_NOT_FOUND` | ③④⑥ | 批次号不存在，或不属于本组织 | 检查 `batchNo` |
| 409 | `BATCH_CONFIRMED` | ③ | 批次已回执 | 无需再处理 |
| 409 | `BATCH_VOIDED` | ③④ | 批次已作废 | 丢弃，不得用本地缓存处理 |
| 422 | `RECEIPT_MISMATCH` | ④ | 回执与批次不符 | 按 `details` 改正后重交 |
| 423 | `ACCOUNT_LOCKED` | ③～⑥ | 连续认证失败已锁定 | 等待解锁 |
| 429 | `RATE_LIMITED` | ③～⑥ | 调用过于频繁 | 按 `Retry-After` 等待 |
| 5xx / 超时 | — | ③～⑥ | — | 直接重试（③ 返回同一批次，④ 以同一回执重交） |

**MES 返回（①）**

| HTTP | `code` | 说明 |
| --- | --- | --- |
| 409 | `BATCH_IN_PROGRESS` | 同一批次正在处理 |
| 409 | `BATCH_CONTENT_MISMATCH` | 批次号相同但 `itemsDigest` 不同 |

---

## 修订记录

| 日期 | 变更 |
| --- | --- |
| 2026-10-26 | 从《MES 对接说明》拆出，只保留接口定义；业务处理规则留在《MES 对接说明》。字段与上一版一致：应答统一带 `code`、`message`、`batchNo`、`timestamp`，回执带 `processedAt`，时间统一 UTC+8 |
| 2026-10-26 | 第 1 章换为完整业务接口调用时序图（图 1-1）并列出各阶段调用的接口；2.3 中 ① 的认证改为待 MES 方填写的清单，SN 系统已支持的方式作为参考 |
