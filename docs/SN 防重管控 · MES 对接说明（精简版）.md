# SN 防重管控 · MES 对接说明（精简版）

> **分工**：SN 系统负责 **推送、取批、转移通知**；过站、LINK、MES 内部数据链由 **MES 自行处理**。

---

## 1. 接口概览

| 类别 | # | 接口名称 | 方向 | 请求方式与路径 | 调用时机 | 详情 |
| --- | --- | --- | --- | --- | --- | --- |
| 推送 | ① | 接收批次 | SN → MES（**MES 提供**） | `POST {MES 地址}` | 分配后、转移后、续推时；连接测试 | 7.1 |
| 拉取 | ③ | 取下一批 | MES → SN | `POST /api/mes/v1/batches/next` | 本地号不够用、推送失败后、每 5 分钟兜底 | 7.2 |
| 回执 | ④ | 提交回执 | MES → SN | `POST /api/mes/v1/batches/{batchNo}/receipt` | ③ 取到的批次落库后 | 7.3 |
| 其他（对账，可选） | ⑤ | 查询已交付的号 | MES → SN | `GET /api/mes/v1/sn/delivered` | 对账 | 7.4 |
| 其他（对账，可选） | ⑥ | 查询批次明细 | MES → SN | `GET /api/mes/v1/batches/{batchNo}/items` | 对账 | 7.5 |

- **认证**：① 由 MES 校验 SN 系统（固定令牌 + IP 白名单，或 HMAC 签名）；③～⑥ 由 SN 系统校验 MES（`Authorization: Bearer <工号>:<密钥>`）。详见第 6.2 节
- 推送 ① 的回执放在**同一个应答**里，不用再调 ④；④ 只给 ③ 取到的批次用
- 编号沿用历史编号，② 换令牌接口已移除

---

## 2. 整体业务接口调用时序图

![图 2-1 SN 业务流程](images/MES对接说明/f1-business-flow.png)

![图 2-2 整体接口调用](images/MES对接说明/f2-interface.png)

- **主通道是推送 ①**：分配、转移后 SN 系统主动推，MES 处理完在同一应答里回执
- **转移时**：同组织转移只给本组织 MES 推 `REASSIGN`；跨组织转移给转入组织推交付批次，同时给原组织推 `TRANSFER_OUT`，两边互不等待
- **备用通道是取批 ③ + 回执 ④**：推送失败后、本地号不够用、每 5 分钟兜底一次（收回推送没送到的批次和 `TRANSFER_OUT`）
- 推送和取批的**批次格式完全相同**，MES 只写一套处理逻辑（第 3 章）；同一个批次可能两条路都到，按 `batchNo` 只处理一次
- ⑤⑥ 只用来对账，不能据此存号、打标或删号

---

## 3. 接口重要字段与 MES 收批处理逻辑

### 3.1 重要字段

MES 做判断只用到下面这些字段，其余字段原样保存即可。完整报文见第 7 章。

| 字段 | 所在位置 | 取值 | MES 拿它做什么 |
| --- | --- | --- | --- |
| `batchNo` | 批次头、回执 | 如 `PS-110-20261019-000123`，全局唯一 | 去重：收过的批次不再处理，原样交回上次回执 |
| `itemsDigest` | 批次头 | 如 `sha256:9f2c…` | 同一 `batchNo` 再来时比字符串：相同 → 重放回执；不同 → 409 |
| `batchType` | 批次头 | `DELIVERY` / `TRANSFER_OUT` / `PING` | 分流，决定走哪种处理 |
| `orgCode` | 批次头 | 组织编码 | 不是本组织 → 整批退回 |
| `count` | 批次头 | 0～5000 | 必须等于 `items` 条数，否则整批退回 |
| `pi` | 批次头、号 | PI 号 | 交付批次每枚号的 `pi` 必须等于批次 `pi` |
| `sn` | 号 | ≤ 64 位，大小写敏感 | 号的唯一标识（多组织共用一套 MES 时用「组织 + SN」） |
| `rev` | 号 | 整数，挂靠每变一次加 1 | **与本地比大小**，决定收不收（3.2 节） |
| `pushType` | 号 | `NEW` / `REASSIGN` / `TRANSFER_OUT` | 只说明原因，收不收看 `rev` |
| `fromOrgCode` | 交付项 | 组织编码，转移来的号才有 | 与 `orgCode` 比：相等是同组织转移，不等是跨组织转入（5.2 节） |
| `from` | 转出项 | 转走前的挂靠 | 本地没有这个号时按它入库 |
| `outcome` | 回执 | `PROCESSED` / `REJECTED` | 整批处理结果 |
| `result` | 回执逐枚 | `ACCEPTED` / `DUPLICATE` / `CONFLICT` / `STALE` | 每枚号的处理结果（3.2 节） |

### 3.2 MES 收到批次怎么处理

![图 3-1 MES 收到批次的判断](images/MES对接说明/f3-batch-check.png)

**① 按 `batchType` 分流**

| `batchType` | 含义 | MES 动作 |
| --- | --- | --- |
| `DELIVERY` | 新号（`NEW`）或挂靠变了（`REASSIGN`） | 存入或覆盖；本地没有的 `REASSIGN` 当新号存 |
| `TRANSFER_OUT` | 号已转到别的组织 | 标「已转出」，停止打标，不删 |
| `PING` | 连接测试，没有号 | 只校验身份和 `orgCode`，回 `PROCESSED`，不存、不记批次号 |

**② 整批校验**（不通过 → 回执 `REJECTED`，不记这个 `batchNo`）：`orgCode` 是本组织；`count` = `items` 条数；批内 SN 不重复；交付批次每枚 `pi` = 批次 `pi`；交付批次不含转出项、转出批次不含交付项；必填字段齐全。

**③ 去重**：`batchNo` 收过 → `itemsDigest` 相同则原样交回上次回执（`replayed = true`），不同则 409 `BATCH_CONTENT_MISMATCH`。

**④ 逐枚比 `rev`**（依据：批次里的 `rev` 与本地 `rev`、本地 `lastBatchNo`）

| 比较结果 | 回执 `result` | `DELIVERY` 的号 | `TRANSFER_OUT` 的号 |
| --- | --- | --- | --- |
| 本地没有 | `ACCEPTED` | 按批次信息存 | 按 `from` 存，标「已转出」 |
| 批次更大 | `ACCEPTED` | 新信息覆盖，去掉「已转出」 | 标「已转出」 |
| 相等，`lastBatchNo` 是本批次 | `DUPLICATE` | 不动 | 不动 |
| 相等，`lastBatchNo` 是别的批次 | `CONFLICT` | 锁号不许打标，等总部处理 | 同左 |
| 批次更小 | `STALE` | 不动 | 不动 |

`CONFLICT`、`STALE` 回执要带本地的 `existingRev`、`existingBatchNo`。

**⑤ 落库后回执**

- 按 `batchNo` 加锁，同一批次不会被两个线程同时处理
- 批次号、`itemsDigest`、整张回执和号的数据**在一个事务里落库**，存成功后再回执；5000 枚建议 10 秒内处理完（推送最多等 60 秒）
- `rev` 原样保存，MES 不要自己加减

---

## 4. SN 状态变更

![图 4-1 SN 状态与 MES 收到的批次](images/MES对接说明/f9-sn-state.png)

| SN 状态 | 含义 | 和 MES 的关系 |
| --- | --- | --- |
| 待分配 | 已生成，还没分到组织 | MES 收不到 |
| 待推送（未入批） | 已分到对接 MES 的组织，还没组批 | 等推送或取批 |
| 待推送（批次未确认） | 已组批，MES 还没回执 | MES 已收到或正在收 `DELIVERY` |
| 待推送（批次失败） | MES 整批退回，或回执与批次不符 | MES 修正后用 ③ 取回重做，或总部续推 |
| 已推送 | MES 已确认（推送应答、取批回执或总部人工确认） | MES 已有这个号 |
| 已分配 | 分到不对接 MES 的组织 | 不经过 MES |
| 申请中 | 已提交转组织申请，等总部处理 | 只在 ⑤⑥ 对账结果里作提醒（`APPLYING`） |

- **SN 号永不变**，变的只是挂靠（组织、PI、单据行、物料、客户）；挂靠每变一次 `rev` 加 1，**越大越新**
- 已推送的号转移后回到「待推送（未入批）」，再推给 MES 的是 `REASSIGN`
- 总部作废未确认的批次后，号回到「待推送（未入批）」，会重新组批送来；MES 收到 409 `BATCH_VOIDED` 时丢弃旧批
- 单据反审核或订单变更待确认时，未交付的号**暂停交付**（状态不变，不组批）；③ 应答的 `paused` 是暂停的枚数

---

## 5. SN 转移业务逻辑

### 5.1 SN 系统侧业务流程

![图 5-1 SN 系统侧转移流程](images/MES对接说明/f10-transfer-sn.png)

1. **发起**：转单、转组织由总部或生产组织在 SN 系统发起；一次最大转一整张单据，不能整张 PI 转
2. **校验**：号所在的转出批次还没确认的，不能再转，提示先在原转组织单上继续推送转出。这样原组织 MES 一定先收到转出，后收到转回来的号
3. **改挂**：清掉号原来的批次，`rev` 加 1，记下 `fromOrgCode`（转移前的组织）
4. **推送**：
   - **同组织**：已交给本组织 MES 的号推 `REASSIGN`；还没交的照常推 `NEW`
   - **跨组织**：给转入组织 MES 推交付批次（原组织 MES 收过这个号 → `REASSIGN`，没收过 → `NEW`）；同时给原组织 MES 推 `TRANSFER_OUT`（只发给收过这个号的原组织），两边互不等待
5. **不等 MES**：SN 系统不向 MES 发撤回、不等 MES 同意，MES 只接收结果；推送失败按普通批次重试、续推，MES 也可用 ③ 取回

### 5.2 MES 侧业务流程

**怎么分辨收到的是哪种转移**

依据：号上的 `fromOrgCode`（最近一次转移前的组织）与批次 `orgCode`（本组织）；`pushType` 说明本 MES 之前有没有这个号。**不要用 `transferKind` 判断**：转单也可能转到别的组织的订单。

| 判断 | 含义 | MES 会收到 |
| --- | --- | --- |
| 没有 `fromOrgCode` | 普通交付，不是转移 | `DELIVERY` / `NEW` |
| `fromOrgCode` = `orgCode` | 同组织转移 | 号已交给本 MES：`REASSIGN`；还没交：照常 `NEW` |
| `fromOrgCode` ≠ `orgCode` | 跨组织转入 | 原组织 MES 收过这个号：`REASSIGN`；没收过：`NEW`。本地都没有，按新号存 |
| 批次类型 `TRANSFER_OUT` | 跨组织转出 | 只发给收过这个号的原组织 MES |

**同组织转移**（会议决策）

![图 5-2 同组织转移](images/MES对接说明/f4-same-org.png)

1. **客户 SN 过站**：可以转。MES 收下 `REASSIGN` 后，要把这个号整条数据链的订单关联换到新订单；有数据混乱风险，由 MES 自行控制
2. **内部 SN 过站**：客户 SN **已 LINK 的不允许转移**，未 LINK 的直接转移

SN 系统不知道过站方式和 LINK 状态，所以「已 LINK 不转」由**现场发起转移前在 MES 确认**。号一旦转了，MES 收到的 `REASSIGN` 按 `rev` 照常收下（`ACCEPTED`），回执里没有「拒绝转移」这一项。

**跨组织转移**（会议决策：直接同步到新组织订单，**不考虑原组织 MES 的 SN 数据**）

![图 5-3 跨组织转移](images/MES对接说明/f5-cross-org.png)

- 新组织 MES：本地没有这个号，按收到的挂靠存下，不用和原组织核对
- 原组织 MES：不用同步、回滚或清理原有数据链；只按 `TRANSFER_OUT` 把号标「已转出」、停止打标，防止两个工厂用同一个号
- 已打出的旧标签由原组织现场按「转出号清单」作废

---

## 6. 接口通用规则与通用错误

### 6.1 通用规则

| 项目 | 规则 |
| --- | --- |
| 传输 | HTTPS + JSON（UTF-8），可 gzip（请求头 `Content-Encoding: gzip`）；不跟随 3xx 跳转 |
| 字段 | 英文小驼峰；每个报文带 `schemaVersion`（现为 `1.0`）；以后只加字段不改字段，**看不懂的字段直接忽略** |
| 时间 | 一律 UTC，ISO 8601 格式，如 `2026-10-19T02:37:00Z` |
| SN | ≤ 64 位，只有字母、数字、`-`、`.`、`_`；**大小写算不同的号** |
| 字符串型数字 | `serialDec`、`length` 按字符串存，不要转数字（会丢前导 0 或溢出） |
| 多组织 | 一套 MES 服务多个组织时，以「组织 + SN」作唯一标识，数据按组织分开存 |
| 成功判定 | ③～⑥：HTTP 200 且 `code = OK`；出错一定不是 200，程序按 `code` 判断，`message` 给人看 |
| 限流 | ③～⑥ 每个组织每分钟 60 次，超出 429 `RATE_LIMITED`，按应答头 `Retry-After` 秒数等待 |

### 6.2 认证与签名

**① SN 系统 → MES**（每个组织二选一，总部在「MES 配置」里设定）

| 方式 | 适用 | 请求头 | MES 怎么校验 |
| --- | --- | --- | --- |
| A 固定令牌 | 内网 | `Authorization: Bearer <令牌>` | 核对令牌，并只允许 SN 系统服务器 IP |
| B HMAC 签名 | 走公网（推荐） | `X-Sn-Timestamp`、`X-Sn-Signature` | 按下图重算签名比对，并校验时间差 |

![图 6-1 HMAC 签名与校验](images/MES对接说明/f8-hmac.png)

```text
签名串  = X-Sn-Timestamp + "\n" + batchNo + "\n" + hex(SHA256(解压后的请求体字节))
签名    = hex(HMAC-SHA256(密钥, 签名串))          // 均为小写十六进制，UTF-8 编码
```

- 摘要按**解压后的原始字节**算，不要先解析 JSON 再序列化（字段顺序、空格会变）
- 时间差 > 5 分钟拒绝；比较签名用常量时间比较
- 密钥由总部在「MES 配置」里设定，线下交给 MES 方

**③～⑥ MES → SN 系统**

- 每次请求带 `Authorization: Bearer <工号>:<密钥>`，不换令牌；工号、密钥由总部在「账户 → MES 接口账户」创建后交给 MES 方
- 账户只能访问所绑的生产组织；可设来源 IP 白名单
- 连续认证失败 10 次锁定 15 分钟

### 6.3 通用错误（③～⑥）

**出错报文**

```jsonc
{
  "schemaVersion": "1.0",              // 协议版本
  "code": "RECEIPT_MISMATCH",          // ★ 错误码，程序按它处理
  "message": "回执枚数与批次不符",       // 中文说明，给人看
  "batchNo": "PL-110-20261019-000045", // 出问题的批次号，没有时为 null
  "details": [                         // 具体哪里不对，可能为空数组
    {
      "field": "results",              // 出问题的字段
      "reason": "期望 5000 枚，收到 4999 枚" // 原因
    }
  ]
}
```

**通用错误码**

| HTTP | `code` | 含义 | MES 怎么做 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 请求体不是合法 gzip / JSON，或参数不合法 | 修正请求 |
| 401 | `INVALID_CREDENTIALS` | 缺少认证头，或工号、密钥不对（含密钥已被重置） | 核对后重试 |
| 423 | `ACCOUNT_LOCKED` | 连续错 10 次锁 15 分钟，期间密钥正确也拒绝 | 等解锁 |
| 403 | `IP_NOT_ALLOWED` | 来源 IP 不在白名单 | 联系总部改白名单 |
| 403 | `ACCOUNT_DISABLED` | 账号已停用 | 联系总部 |
| 403 | `FORBIDDEN` | 不是 MES 接口账户却调了 ③④ | 换用 MES 接口账户 |
| 429 | `RATE_LIMITED` | 调得太频繁 | 按 `Retry-After` 等待 |

---

## 7. 接口详情

> 报文用带注释的 JSON，**注释只用于说明，实际报文里没有注释**。
> 注释格式：`必填 / 选填，类型，含义`；**★** 标出 MES 要拿它做判断的字段。

### 7.1 ① 接收批次（MES 提供，SN → MES）

#### 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 接收批次 |
| 接口描述 | SN 系统把批次（交付、转出、连接测试）推给 MES；MES 按第 3.2 节处理、落库后，在**同一个应答**里返回回执 |
| 请求方式 | `POST` |
| 请求 URL | `{MES 地址}`，由 MES 方提供，总部在「生产组织 → MES 配置」里填写 |
| 调用时机 | 分配后、转移后、续推时；总部点「连接测试」时发 `PING` |
| 负责人 | 接口实现：MES 方（待填写）；调用方：SN 系统 @magic |

![图 7-1 推送与回执](images/MES对接说明/f6-push.png)

#### 2. 请求参数

**Header**

| 参数名 | 类型 | 必填 | 示例值 | 描述 |
| --- | --- | --- | --- | --- |
| `Content-Type` | String | 是 | `application/json; charset=utf-8` | 固定 |
| `Content-Encoding` | String | 是 | `gzip` | 请求体已 gzip 压缩，先解压 |
| `Accept-Encoding` | String | 是 | `gzip` | 应答可压缩 |
| `Authorization` | String | 方式 A 必填 | `Bearer 7f3c…` | 固定令牌（6.2 节） |
| `X-Sn-Timestamp` | String | 方式 B 必填 | `2026-10-19T02:37:00Z` | 签名时间（UTC） |
| `X-Sn-Signature` | String | 方式 B 必填 | `a3f9…`（64 位小写十六进制） | HMAC 签名（6.2 节） |
| `X-Sn-Batch-No` | String | 是 | `PS-110-20261019-000123` | ★ 批次号，与报文 `batchNo` 相同；可不解析报文先按它加锁 |
| `X-Sn-Attempt` | String | 是 | `1` | 第几次送，只记日志 |
| `X-Request-Id` | String | 是 | UUID | 请求编号，只查日志；**不能用它去重** |

**Body A：交付批次（`DELIVERY`）** —— 新号、挂靠变了都用它，③ 取批拿到的也是同一结构

```jsonc
{
  "schemaVersion": "1.0",              // 必填，String，协议版本
  "batchNo": "PS-110-20261019-000123", // 必填，String，批次号，全局唯一（PS 推送时建批 / PL 取批时建批）★ 判断是否收过
  "batchType": "DELIVERY",             // 必填，String，DELIVERY / TRANSFER_OUT / PING ★ 决定走哪种处理
  "orgCode": "110",                    // 必填，String，收件组织 ★ 不是本组织 → 整批退回
  "pi": "000001",                      // 必填，String，交付批次只装这一张 PI 的号 ★ 每枚号的 pi 必须等于它
  "count": 1,                          // 必填，Number，枚数 ≤ 5000 ★ 必须等于 items 条数
  "createdAt": "2026-10-19T02:37:00Z", // 必填，String，建批时间（UTC）
  "itemsDigest": "sha256:9f2c4e…",     // 必填，String，内容指纹，MES 存下直接比字符串，不用自己算 ★ 同一 batchNo 再来时比它
  "items": [                           // 必填，Array，交付项
    {
      "pushType": "REASSIGN",          // 必填，String，NEW 新号 / REASSIGN 挂靠变了；只说明原因，收不收看 rev
      "sn": "HX100000001A",            // 必填，String，完整 SN
      "rev": 2,                        // 必填，Number，挂靠版本，越大越新 ★ 与本地比（3.2 节）
      "serial": "000001",              // 必填，String，流水号（可能是 36 进制写法）
      "serialDec": "1",                // 必填，String，十进制流水，按字符串存
      "serialPi": "000001",            // 必填，String，流水最初所属 PI；转入号可能 ≠ pi，不要互相校验
      "pi": "000001",                  // 必填，String，当前所属 PI
      "billNo": "VT2026090001",        // 必填，String，单据号
      "billSeq": 1,                    // 必填，Number，单据行号（同一单据可能有相同物料，靠行号区分）
      "materialCode": "9148363",       // 选填，String | null，物料编码
      "length": "0100",                // 选填，String | null，米标，原样保存（不要变成 100）
      "customerCode": "0001",          // 必填，String，客户编码
      "transferKind": "BILL_TRANSFER", // 选填，String，转移来的号才有：BILL_TRANSFER 转单 / ORG_TRANSFER 转组织；只供核对
      "transferDocNo": "TB202610190236001234", // 选填，String，转移单号（TB 转单 / TO 转组织）；只供核对
      "fromOrgCode": "110"             // 选填，String，最近一次转移前的组织 ★ = orgCode 同组织转移；≠ orgCode 跨组织转入
    }
  ]
}
```

**Body B：转出批次（`TRANSFER_OUT`）** —— 告诉原组织「这些号不归你了」

```jsonc
{
  "schemaVersion": "1.0",              // 必填，String，协议版本
  "batchNo": "XO-110-20261019-000007", // 必填，String，XO 开头 ★ 判断是否收过
  "batchType": "TRANSFER_OUT",         // 必填，String ★ 只能装转出项
  "orgCode": "110",                    // 必填，String，原组织（收件方）★ 不是本组织 → 整批退回
  "pi": "000001",                      // 必填，String | null，号来自多张 PI 时为 null，按每枚的 from.pi 归档
  "count": 1,                          // 必填，Number ★ 等于 items 条数
  "createdAt": "2026-10-19T02:50:00Z", // 必填，String，建批时间（UTC）
  "itemsDigest": "sha256:51ab07…",     // 必填，String ★ 同上
  "items": [                           // 必填，Array，转出项
    {
      "pushType": "TRANSFER_OUT",      // 必填，String，固定值
      "sn": "HX100000003A",            // 必填，String，完整 SN
      "rev": 3,                        // 必填，Number ★ 与本地比（3.2 节）
      "serial": "000003",              // 必填，String，流水号
      "serialDec": "3",                // 必填，String，十进制流水
      "serialPi": "000001",            // 必填，String，流水最初所属 PI
      "from": {                        // 必填，Object，转走之前的挂靠 ★ 本地没有这个号时按它入库；不要猜转走后的信息
        "pi": "000001",                // 必填，String，原 PI
        "billNo": "VT2026090001",      // 必填，String，原单据号
        "billSeq": 1,                  // 必填，Number，原单据行号
        "materialCode": "9148363",     // 选填，String | null，原物料编码
        "customerCode": "0001"         // 必填，String，原客户编码
      },
      "toOrgCode": "120",              // 必填，String，转去哪个组织，记下来
      "transferKind": "ORG_TRANSFER",  // 必填，String，BILL_TRANSFER 转单 / ORG_TRANSFER 转组织
      "transferDocNo": "TO202610190250004321" // 必填，String，转移单号，现场按它查找
    }
  ]
}
```

**Body C：连接测试（`PING`）** —— 总部点「连接测试」时发

```jsonc
{
  "schemaVersion": "1.0",                // 必填，String，协议版本
  "batchNo": "PING-202610190237001234",  // 必填，String，不用记下，也不用去重
  "batchType": "PING",                   // 必填，String ★ 只校验身份和 orgCode，什么都不存
  "orgCode": "110",                      // 必填，String ★ 不是本组织 → 退回
  "pi": null,                            // 必填，固定 null
  "count": 0,                            // 必填，Number，固定 0
  "createdAt": "2026-10-19T02:37:00Z",   // 必填，String，建批时间（UTC）
  "itemsDigest": "sha256:…",             // 必填，String
  "items": []                            // 必填，空数组
}
```

#### 3. 响应结果（回执）

推送时放在 ① 的应答里；取批时用 ④ 提交，格式相同。

```jsonc
// 正常处理（含部分号冲突、过期）—— HTTP 200
{
  "schemaVersion": "1.0",              // 必填，String，协议版本
  "batchNo": "PS-110-20261019-000123", // 必填，String，照抄批次号
  "outcome": "PROCESSED",              // 必填，String，PROCESSED 已处理 / REJECTED 整批退回
  "replayed": false,                   // 选填，Boolean，true = 收过的批次，原样交回上次的回执
  "receivedAt": "2026-10-19T02:37:03Z",// 必填，String，第一次处理的时间（UTC）
  "summary": {                         // 必填，Object，各结果枚数，必须与 results 对得上
    "total": 3,                        // 必填，Number，总枚数 = 批次 count
    "accepted": 1,                     // 必填，Number，ACCEPTED 枚数
    "duplicate": 0,                    // 必填，Number，DUPLICATE 枚数
    "conflict": 1,                     // 必填，Number，CONFLICT 枚数
    "stale": 1                         // 必填，Number，STALE 枚数
  },
  "results": [                         // 必填，Array，每枚号一条，条数 = 批次 count
    {
      "sn": "HX100000005A",            // 必填，String，照抄批次
      "rev": 1,                        // 必填，Number，照抄批次
      "result": "ACCEPTED"             // 必填，String，ACCEPTED / DUPLICATE / CONFLICT / STALE（3.2 节）
    },
    {
      "sn": "HX100000001A",
      "rev": 2,
      "result": "CONFLICT",
      "existingRev": 2,                // CONFLICT / STALE 必填，Number，本地的 rev
      "existingBatchNo": "PS-110-20261019-000100" // CONFLICT / STALE 必填，String，本地 rev 的来源批次
    },
    {"sn": "HX100000002A", "rev": 1, "result": "STALE", "existingRev": 2, "existingBatchNo": "PS-110-20261019-000100"}
  ]
}

// 整批退回 —— 也是 HTTP 200；退回时不要记下这个 batchNo
{
  "schemaVersion": "1.0",              // 必填，String
  "batchNo": "PL-110-20261019-000046", // 必填，String，照抄批次号
  "outcome": "REJECTED",               // 必填，String
  "rejectCode": "FIELD_TOO_LONG",      // 必填，String，退回原因代码，MES 自定
  "rejectMessage": "materialCode 超过本侧字段长度 20" // 必填，String，退回原因说明
}

// 连接测试 —— HTTP 200
{"schemaVersion": "1.0", "batchNo": "PING-202610190237001234", "outcome": "PROCESSED",
 "summary": {"total": 0, "accepted": 0, "duplicate": 0, "conflict": 0, "stale": 0}, "results": []}
```

#### 4. 错误码（MES 返回的 HTTP 码）

| 情况 | MES 返回 | SN 系统怎么做 |
| --- | --- | --- |
| 处理完，或整批退回 | **200** + 回执 | 按回执确认或冻结该批 |
| 同一批正在处理中 | 等处理完返回同一张回执，或 409 `BATCH_IN_PROGRESS` | 409 时稍后重送 |
| 批次号相同但内容不同（`itemsDigest` 不同） | 409 `BATCH_CONTENT_MISMATCH` | 不重送，告警 |
| 认证失败（令牌错、签名错、时间超差） | 401 或 403 | 不重送 |
| 地址不对 | 404 | 不重送 |
| 报文太大 | 413 | 不重送，请总部作废后拆小重发 |
| 报文看不懂（解不开 gzip / JSON） | 400 | 不重送 |
| 超时、5xx、断网、429、503 | — | 1、5、15 分钟后重送 |

记住：**只要能拆包处理，就返回 200 + 回执**；4xx 只用于「根本没法处理」。缺字段、`orgCode` 不对这类问题回 200 + `REJECTED`，不是 4xx。

#### 5. 补充说明

- 失败按 1、5、15 分钟各重送一次，**重送内容一个字节都不变**（`batchNo`、`itemsDigest` 都相同）
- 同一 `batchNo` 再来：`itemsDigest` 相同 → 原样交回上次回执（`replayed = true`）；不同 → 409 `BATCH_CONTENT_MISMATCH`
- 认证与签名见 6.2 节

---

### 7.2 ③ 取下一批（MES → SN）

#### 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 取下一批 |
| 接口描述 | MES 主动来拿批次。先给范围内**最早的、还没回执的批次**（推送没送到的、上次取走没回执的、转出批次），都没有才新建；被退回、已作废的批次不会自动再给 |
| 请求方式 | `POST` |
| 请求 URL | `/api/mes/v1/batches/next` |
| 调用时机 | 本地号不够用、推送失败后、每 5 分钟不带条件兜底一次 |
| 负责人 | SN 系统 @magic |

![图 7-2 取批与交回执](images/MES对接说明/f7-pull.png)

#### 2. 请求参数

**Header**

| 参数名 | 类型 | 必填 | 示例值 | 描述 |
| --- | --- | --- | --- | --- |
| `Authorization` | String | 是 | `Bearer M110001:xxxxxxxx` | MES 接口账户的工号和密钥，用冒号连接 |
| `Content-Type` | String | 否 | `application/json` | — |
| `Content-Encoding` | String | 否 | `gzip` | 请求体压缩时填 |

**Body**

```jsonc
{
  "schemaVersion": "1.0", // 必填，String，协议版本
  "pi": "000001",         // 选填，String | null，只要这张 PI 的号；null = 不限
  "billNo": null,         // 选填，String | null，只要这张单据的号；可与 pi 一起填
  "batchNo": null         // 选填，String | null，只取回某个旧批次（如修好后重做退回的那批）；填了它 pi、billNo 不生效，也不会新建批次
}
```

转出批次按每枚号的 `from.pi`、`from.billNo` 判断在不在范围内。

#### 3. 响应结果

```jsonc
{
  "schemaVersion": "1.0",      // String，协议版本
  "code": "OK",                // String，成功固定 OK
  "batch": { /* 批次，结构同 7.1 Body A / B */ }, // Object | null ★ null = 现在没有可给的，1 分钟以上再来
  "batchSource": "PULL",       // String，PUSH 推送时建的 / PULL 取批时建的；处理方法一样
  "newlyCreated": true,        // Boolean，是不是这次新建的
  "batchResult": "UNCONFIRMED",// String，UNCONFIRMED 还没回执；用 batchNo 取回退回的批次时为 FAILED
  "remaining": 1200,           // Number ★ 范围内还剩多少枚可取，> 0 就接着取
  "paused": 0                  // Number，被总部暂停交付的枚数，等总部处理，不用一直来取
}
```

拿到批次后处理完**马上用 ④ 交回执**，不交的话下次来取还是这一批。

#### 4. 错误码

除 6.3 通用错误外：

| HTTP | `code` | 含义 | MES 怎么做 |
| --- | --- | --- | --- |
| 403 | `ORG_NOT_ALLOWED` | 这张 PI 下没有号分到本组织，也没有本组织的转出批次 | 检查 `pi` |
| 404 | `BATCH_NOT_FOUND` | 没有这个批次号，或不是本组织的 | 检查 `batchNo` |
| 409 | `BATCH_CONFIRMED` | 这批已经回执过 | 不用再处理 |
| 409 | `BATCH_VOIDED` | 这批已作废 | 丢弃，不能用本地缓存处理 |
| 5xx / 超时 | — | — | 直接再取，拿到的还是同一批 |

#### 5. 补充说明

- 没回执前反复取，拿到的是同一批（同一 `batchNo`、同一 `itemsDigest`）
- 取到的批次与推送来的同一批，MES 按 `batchNo` 只处理一次

---

### 7.3 ④ 提交回执（MES → SN）

#### 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 提交回执 |
| 接口描述 | 通过 ③ 拿到的批次，处理完用它交回执（含整批退回）。推送来的批次已在 ① 应答里回执，不用再交。按批次号幂等 |
| 请求方式 | `POST` |
| 请求 URL | `/api/mes/v1/batches/{batchNo}/receipt` |
| 调用时机 | ③ 取到的批次落库后马上交 |
| 负责人 | SN 系统 @magic |

#### 2. 请求参数

**Header**：同 7.2。

**Path**

| 参数名 | 类型 | 必填 | 示例值 | 描述 |
| --- | --- | --- | --- | --- |
| `batchNo` | String | 是 | `PL-110-20261019-000046` | 批次号，必须与请求体 `batchNo` 一致 |

**Body**：就是 7.1「3. 响应结果」里的回执，字段注释见那里。

SN 系统会核对：批次号一致；`results` 条数 = 批次枚数；每枚号恰好出现一次且都属于这一批；`rev` 一致；`summary` 与明细一致。

#### 3. 响应结果

```jsonc
{
  "schemaVersion": "1.0",              // String，协议版本
  "code": "OK",                        // String，成功固定 OK
  "batchNo": "PL-110-20261019-000046", // String，批次号
  "batchResult": "SUCCESS",            // String，SUCCESS 成功 / SUCCESS_CONFLICT 成功但有冲突的号 / FAILED 整批退回已登记
  "firstConfirmation": true            // Boolean，true 这次确认成功；false 之前已确认过，这次只存档
}
```

#### 4. 错误码

除 6.3 通用错误外：

| HTTP | `code` | 含义 | MES 怎么做 |
| --- | --- | --- | --- |
| 422 | `RECEIPT_MISMATCH` | 回执和批次对不上 | 按 `details` 改正后重交 |
| 409 | `BATCH_VOIDED` | 这批已作废 | 丢弃 |
| 404 | `BATCH_NOT_FOUND` | 没有这个批次号，或不是本组织的 | 检查 `batchNo` |
| 5xx / 超时 | — | — | 拿同一张回执再交 |

#### 5. 补充说明

- 同一张回执重复交不会出错，第二次起 `firstConfirmation = false`
- 回执 `REJECTED` 后同一张 PI 的后续推送暂停；修好后用 ③ 带 `batchNo` 取回重做、④ 交回执，再请总部「继续推送」

---

### 7.4 ⑤ 查询已交付的号（MES → SN，对账，可选）

#### 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 查询已交付的号 |
| 接口描述 | 返回 SN 系统认为已经交给本组织 MES 的号（含推送和取批）；已转到别的组织的不返回。**只用来对账**，不能据此存号、打标或删号 |
| 请求方式 | `GET` |
| 请求 URL | `/api/mes/v1/sn/delivered` |
| 负责人 | SN 系统 @magic |

#### 2. 请求参数

**Header**：`Authorization: Bearer <工号>:<密钥>`。

**Query**

| 参数名 | 类型 | 必填 | 默认值 | 示例值 | 描述 |
| --- | --- | --- | --- | --- | --- |
| `pi` | String | 否 | 不限 | `000001` | 只查这张 PI |
| `cursor` | String | 否 | 空 | `10086` | 翻页游标：第一页留空，之后填上一页的 `nextCursor` |
| `pageSize` | Number | 否 | `1000` | `2000` | 每页条数，最多 5000 |

#### 3. 响应结果

```jsonc
{
  "schemaVersion": "1.0",              // String，协议版本
  "code": "OK",                        // String，成功固定 OK
  "items": [                           // Array，号的列表
    {
      "sn": "HX100000001A",            // String，完整 SN
      "rev": 2,                        // Number，挂靠版本
      "serial": "000001",              // String，流水号
      "serialDec": "1",                // String，十进制流水
      "serialPi": "000001",            // String，流水最初所属 PI
      "pi": "000001",                  // String，当前所属 PI
      "billNo": "VT2026090001",        // String，单据号
      "billSeq": 1,                    // Number，单据行号
      "materialCode": "9148363",       // String | null，物料编码
      "length": "0100",                // String | null，米标
      "customerCode": "0001",          // String，客户编码
      "orgCode": "110",                // String，当前所属组织
      "status": "PUSHED",              // String，SN 系统里的状态，如 PUSHED 已推送 / TO_PUSH 改挂待推送 / APPLYING 正在申请转走（只作提醒）
      "batchNo": "PS-110-20261019-000123" // String | null，当前所在批次
    }
  ],
  "nextCursor": "10086"                // String | null ★ 下一页游标；null = 最后一页
}
```

#### 4. 错误码

只有 6.3 通用错误。

---

### 7.5 ⑥ 查询批次明细（MES → SN，对账，可选）

#### 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 查询批次明细 |
| 接口描述 | 某个批次里**仍在这一批**的号（已转到别的组织的不返回，整批移出时为 0 枚）。只用来对账 |
| 请求方式 | `GET` |
| 请求 URL | `/api/mes/v1/batches/{batchNo}/items` |
| 负责人 | SN 系统 @magic |

#### 2. 请求参数

**Header**：`Authorization: Bearer <工号>:<密钥>`。

**Path / Query**

| 参数名 | 位置 | 类型 | 必填 | 默认值 | 描述 |
| --- | --- | --- | --- | --- | --- |
| `batchNo` | Path | String | 是 | — | 批次号 |
| `cursor` | Query | String | 否 | 空 | 同 ⑤ |
| `pageSize` | Query | Number | 否 | `1000` | 同 ⑤，最多 5000 |

#### 3. 响应结果

```jsonc
{
  "schemaVersion": "1.0",              // String，协议版本
  "code": "OK",                        // String，成功固定 OK
  "batchNo": "PS-110-20261019-000123", // String，查询的批次号
  "items": [ /* 结构同 7.4 items */ ],  // Array，仍在这一批的号
  "nextCursor": null                   // String | null，下一页游标；null = 最后一页
}
```

#### 4. 错误码

除 6.3 通用错误外：404 `BATCH_NOT_FOUND`（没有这个批次号，或不是本组织的）。

---

## 8. 异常处理

| 情况 | MES 动作 |
| --- | --- |
| 推送超时、MES 宕机 | 不用特别处理：SN 系统 1、5、15 分钟后重送同一批；或 MES 恢复后取批 |
| 批次处理不了 | 回执 `REJECTED` 写明原因（同一张 PI 后续推送暂停）；修好后用 ③ 带 `batchNo` 取回重做、④ 交回执，再请总部「继续推送」 |
| 409 `BATCH_VOIDED` | 丢弃该批次，不用本地缓存处理；号会重新组批送来 |
| 422 `RECEIPT_MISMATCH` | 按 `details` 改正后重交回执 |
| 回执里有 `CONFLICT` / `STALE` | 这些号先锁住；总部用更大的 `rev` 重送后自动解锁，或由现场人工放行 |
| 长期交不上回执 | 书面告知总部是否已入库：已入库由总部「人工确认」，没处理由总部「作废」 |

---

## 附录 A 错误码总表

| `code` | HTTP | 出现在 | 含义 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 | ③～⑥ | 请求体不是合法 gzip / JSON，或参数不合法 |
| `INVALID_CREDENTIALS` | 401 | ③～⑥ | 缺少认证头，或工号、密钥不对 |
| `ACCOUNT_LOCKED` | 423 | ③～⑥ | 连续认证失败已锁定 |
| `IP_NOT_ALLOWED` | 403 | ③～⑥ | 来源 IP 不在白名单 |
| `ACCOUNT_DISABLED` | 403 | ③～⑥ | 账号已停用 |
| `FORBIDDEN` | 403 | ③④ | 不是 MES 接口账户 |
| `RATE_LIMITED` | 429 | ③～⑥ | 调用太频繁 |
| `ORG_NOT_ALLOWED` | 403 | ③ | PI 与本组织无关 |
| `BATCH_NOT_FOUND` | 404 | ③④⑥ | 批次号不存在，或不属于本组织 |
| `BATCH_CONFIRMED` | 409 | ③ | 批次已回执 |
| `BATCH_VOIDED` | 409 | ③④ | 批次已作废 |
| `RECEIPT_MISMATCH` | 422 | ④ | 回执与批次不符 |
| `BATCH_IN_PROGRESS` | 409 | ①（MES 返回） | 同一批正在处理中 |
| `BATCH_CONTENT_MISMATCH` | 409 | ①（MES 返回） | 批次号相同但内容不同 |
