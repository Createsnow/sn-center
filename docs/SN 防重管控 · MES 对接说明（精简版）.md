# SN 防重管控 · MES 对接说明（精简版）

Oct 19, 2026 · @magic　|　规则以《MES 接口规格》（PRD V30）为准

**三个概念**

- **批次**：一组号，最多 5000 枚。批次号唯一，建好后内容不变
- **回执**：MES 处理完一批后返回的结果，逐枚说明是否收下
- **rev**：号的版本号，号每变动一次加 1。MES 只用更大的 rev 覆盖本侧数据

---

## 1. 接口

| # | 接口 | 方向 | 作用 | 必做 |
| --- | --- | --- | --- | --- |
| ① | `POST {MES 地址}` | 本系统 → MES | 推送批次，MES 在应答里返回回执 | 是（MES 提供） |
| ② | `POST /api/mes/v1/auth/token` | MES → 本系统 | 用工号和密钥换令牌，有效 12 小时 | 是 |
| ③ | `POST /api/mes/v1/batches/next` | MES → 本系统 | 取下一批；带 `batchNo` 时取回指定批次 | 是 |
| ④ | `POST /api/mes/v1/batches/{batchNo}/receipt` | MES → 本系统 | 提交回执，或整批拒收 | 是 |
| ⑤ | `GET /api/mes/v1/sn/delivered` | MES → 本系统 | 查询已交付给本组织的号 | 否，仅核对 |
| ⑥ | `GET /api/mes/v1/batches/{batchNo}/items` | MES → 本系统 | 查询某个批次里的号 | 否，仅核对 |

> 号到达 MES 有两条路：① 本系统推送，或 ③ MES 主动取批。两条路送的批次相同，处理逻辑也相同（见第 3 节），处理完都要回执。

---

## 2. 调用流程

### A. 推送（主路）

1. 本系统通过 ① 推送一批
2. MES 执行 `process(batch)`
3. MES 在 ① 的应答里返回回执

### B. 取批（备用路）

在本地号不够时取批。另外每 5 分钟不带范围取一次，用来收回推送中断的批次和转出批次。

1. 调 ② 换令牌（令牌用掉 80% 时长后换新）
2. 调 ③ 取批；返回 `batch = null` 时，间隔 1 分钟以上再取
3. 执行 `process(batch)`
4. 调 ④ 提交回执
5. 应答中 `remaining > 0` 时，回到第 2 步继续取

### C. 某一批处理不了

1. 返回整批拒收：推送来的批次在 ① 应答里返回，取来的批次通过 ④ 提交
2. 修好后调 ③ 并带上 `batchNo`，取回这一批，重新处理后用 ④ 提交回执
3. 如果 ③ 或 ④ 返回 409 `BATCH_VOIDED`，说明总部已作废这一批，直接丢弃。这些号会换新批次重新送来

### 两条路撞车

推送超时后本系统会重试，MES 也可能取到同一批。MES 按批次号只处理一次，两边拿到的回执相同。

> [图：推送与取批 · 同一批次的两条送达路]

---

## 3. 处理逻辑 `process(batch)`（核心）

推送和取批调用同一个函数，并共用一把按批次号加的锁。处理分四步。

### 第 1 步：校验（不通过就整批拒收）

- `orgCode` 等于本组织
- `count` 等于 `items` 条数，且不超过单批上限
- 批内 SN 不重复，必填字段齐全
- 交付批次：每枚的 `pi` 等于批次头的 `pi`，且不含转出项。转出批次只含转出项

整批拒收时**不登记批次号**。否则修好后再收到同一批，只会返回旧的拒收结果。

### 第 2 步：查重（加锁后查批次表）

- 已处理过，且 `itemsDigest` 相同：原样返回存下的回执（`replayed = true`），结束
- 已处理过，但 `itemsDigest` 不同：返回 409 `BATCH_CONTENT_MISMATCH`（说明本系统有缺陷），结束

### 第 3 步：逐枚比对 rev（整批一个事务）

| 情况 | 处理 | 回执 |
| --- | --- | --- |
| 本侧没有此 SN | 交付项：按收到的挂靠入库。转出项：按 `from` 入库，标「已转出」，记录 `toOrgCode` | `ACCEPTED` |
| 收到的 rev 更大 | 交付项：用收到的挂靠覆盖（单据、序号、物料、客户、PI、组织），清除「已转出」。转出项：用 `from` 覆盖挂靠，标「已转出」，记录 `toOrgCode` | `ACCEPTED` |
| rev 相同，`lastBatchNo` 是本批 | 不改 | `DUPLICATE`（正常） |
| rev 相同，`lastBatchNo` 是别的批 | 不改；该号挂起，禁止打标 | `CONFLICT`，带回 `existingRev`、`existingBatchNo` |
| 收到的 rev 更小 | 不改 | `STALE`，带回 `existingRev`、`existingBatchNo` |

以上两行（`ACCEPTED`）都要：保存 rev，`lastBatchNo` 记为本批；保留打标状态，解除冲突挂起。

转出项不带新的挂靠，只有转出前的挂靠 `from` 和去向 `toOrgCode`，所以用 `from` 写入挂靠，不要留空或猜测新挂靠。

**口诀：rev 大就收，rev 小不收；rev 相同看批次号，同一批是重复，不同批是冲突。** 只有 `ACCEPTED` 会修改本侧数据。

rev 只由本系统生成。MES 原样存下收到的值，不自行加减。

### 第 4 步：先落库，再回执

保存批次号、来源（推送或取批）、`itemsDigest` 和整份回执，提交事务后再返回回执。顺序不能反：如果先回执后落库，落库失败时本系统会以为 MES 已收下，这批号就丢了。

5000 枚一批建议在 10 秒内处理完（推送超时为 60 秒）。

### 示例：一枚号多次变动时的 rev

以 `HX100000001A` 为例。

| 步骤 | rev | 组织 110 的 MES 收到 | 组织 120 的 MES 收到 |
| --- | --- | --- | --- |
| 分配给 110 | 1 | 交付 `NEW`：入库 | — |
| 在 110 内转单 | 2 | 交付 `REASSIGN`：覆盖挂靠 | — |
| 转组织到 120 | 3 | 转出 `TRANSFER_OUT`：标「已转出」，禁止打标 | 交付：入库 |
| 转回 110 | 4 | 交付 `REASSIGN`：覆盖挂靠并清除「已转出」 | 转出 `TRANSFER_OUT`：标「已转出」 |

规律：号离开的组织收到「转出」，号到达或留在的组织收到「交付」。

---

## 4. 接口要点

### ① 接收推送（MES 提供）

- **地址**：由 MES 提供，总部在对接设置中填写
- **请求头**：认证（见 6.1）、`X-Sn-Batch-No`、`X-Sn-Attempt`（第几次发送）、`X-Request-Id`、`Content-Encoding: gzip`
- **请求体**：一个批次。同一批重发时，请求体逐字节相同

| 情况 | 应答 |
| --- | --- |
| 处理完，或整批拒收 | **都返回 200** + 回执（拒收时 `outcome = REJECTED`）；返回存下的旧回执时 `replayed = true` |
| 同一批正在处理 | 等处理完返回同一份回执，或返回 409 `BATCH_IN_PROGRESS` |
| 批次号相同但内容不同 | 409 `BATCH_CONTENT_MISMATCH` |
| 认证失败 / 地址错 / 报文过大 / 无法解析 | 401 或 403 / 404 / 413 / 400 |

- 4xx 只表示请求没有进入处理逻辑；能处理的一律返回 200 + 回执
- 遇到超时（60 秒）、5xx、断连、409 `BATCH_IN_PROGRESS`、429、503 时，本系统在 1、5、15 分钟后各重试一次；其他情况不重试
- **连接测试**：收到 `batchType = PING`、`items = []` 的批次时，只校验认证和 `orgCode`，通过就返回 `outcome = PROCESSED`、`summary` 全为 0。不登记，也不写数据

### ② 换令牌

- 请求：`{"empNo": "MES110", "secret": "<密钥>"}`
- 应答：`accessToken`、`expiresIn: 43200`、`expiresAt`
- 之后每次请求带 `Authorization: Bearer <token>`
- 换新令牌不会使旧令牌失效，多台服务器可以各持一个。密钥重置后，旧令牌全部失效
- 错误：401 `INVALID_CREDENTIALS`；连续错 10 次锁定 15 分钟，返回 423 `ACCOUNT_LOCKED`；403 `IP_NOT_ALLOWED` 或 `ACCOUNT_DISABLED`

### ③ 取下一批

请求：`{"schemaVersion": "1.0", "pi": "000001", "billNo": null, "batchNo": null}`

- `pi`、`billNo` 都不填时取本组织全部；填了就只在该范围内取
- 填了 `batchNo`：只读取回这一批，不新建批次，也不改状态
- **给哪一批**：先给范围内最早的未签收批次（推送卡住的、取走后没回执的、转出批次），没有才新建。失败和已作废的批次不再给，不会卡住后面的号
- **转出批次的范围**：按每枚的 `from.pi`、`from.billNo` 判断。按 PI 取时，批内任一枚 `from.pi` 等于该 PI 即在范围内；按单据取时看 `from.billNo`；按 PI + 单据取时两者都要满足。整批返回，不拆开
- 按单据取时，可能拿到包含其他单据的整批，照常收下

| 应答字段 | 含义 |
| --- | --- |
| `batch` | 批次对象，与推送请求体相同；没有可给的批次时为 `null` |
| `batchSource` | `PUSH` 表示推送时建的，`PULL` 表示取批时建的 |
| `newlyCreated` | 是否本次新建 |
| `batchResult` | 一般为 `UNCONFIRMED`；按批次号取回时也可能是 `FAILED` |
| `remaining` | 范围内还能取的枚数（含其余未确认的批次，包括转出批次），大于 0 就继续取 |
| `paused` | 暂停交付的枚数，在等总部处理，不必反复取 |

| 错误 | MES 怎么做 |
| --- | --- |
| 401 `TOKEN_EXPIRED` / `TOKEN_INVALID` | 重新换令牌再取 |
| 403 `ORG_NOT_ALLOWED` | 该 PI 下既没有号分到本组织，也没有本组织的转出批次，核对 PI |
| 404 `BATCH_NOT_FOUND` | 批次号不存在，或不属于本组织 |
| 409 `BATCH_CONFIRMED`（按批次号取回时） | 该批已确认，不用再处理 |
| 409 `BATCH_VOIDED`（按批次号取回时） | 该批已作废，丢弃 |
| 429 `RATE_LIMITED` | 每分钟超过 60 次，按 `Retry-After` 等待 |
| 5xx / 超时 | 直接再取，拿到的仍是同一批 |

### ④ 提交回执

请求体就是处理逻辑产出的回执（见 6.5）。本系统会核对：

- 批次号一致
- 结果条数等于 `count`
- 每枚 SN 恰好出现一次，且都属于本批
- 每枚的 `rev` 与批次一致
- `summary` 与明细一致

| 应答 | 含义 |
| --- | --- |
| 200，`firstConfirmation = true` | 核对通过，批次成功 |
| 200，`firstConfirmation = false` | 该批早已确认，本次只存档 |
| 200，`batchResult = FAILED` | 整批拒收，号冻结，首页告警 |
| 422 `RECEIPT_MISMATCH` | 回执与批次对不上，`details` 写明差异；批次记为失败，修正后重新提交 |
| 409 `BATCH_VOIDED` | 批次已作废 |
| 404 `BATCH_NOT_FOUND` | 批次号不存在，或不属于本组织 |

遇到超时或 5xx 时，用同一份回执重试即可。

### ⑤⑥ 只读核对（可选）

- ⑤ `GET /api/mes/v1/sn/delivered?pi=000001&cursor=&pageSize=1000`
- ⑥ `GET /api/mes/v1/batches/{batchNo}/items?cursor=&pageSize=1000`
- 每页默认 1000 条，最多 5000 条；用 `nextCursor` 翻页，为 `null` 时是末页
- **只用于核对**，不能据此入库、打标或删除

---

## 5. 异常处理

| 情况 | MES 要做什么 |
| --- | --- |
| 推送超时、取批后宕机、回执提交丢失 | 无需特殊处理：重试或再取拿到的仍是同一批，按批次号去重；回执用同一份重新提交 |
| 某一批处理不了（如字段超长） | 返回拒收回执并写明原因 → 修好后按批次号取回 → 补交回执。期间该批失败、号冻结，后面的批次照常 |
| 回执被拒（422） | 排查修正后重新提交 |
| 报文太大（413） | 能放宽就放宽，等待续推；放不宽就由总部作废，号重新组批（之后的新批按调小的枚数组包） |
| 地址配错，A 的号推到了 B | 校验 `orgCode`，整批拒收 |
| 同一号被送进两批，或本系统从备份恢复导致数据回退 | 照常回执 `CONFLICT` / `STALE`，冲突的号挂起；等总部用更大的 rev 重推，收下即解除挂起 |
| 号转出后又转回来源组织 | 收到 rev 更大的改挂，清除「已转出」，号恢复可用 |
| 组织关闭「对接 MES」后，曾推送的号被转走 | 保留接收接口，照常处理转出批次（也可以取批取回） |

### 批次兜底：每一批都有出口

一批长期等不到有效回执时（MES 长期停机、已停用、修不好），总部在拿到 MES 方书面确认后二选一：

- **人工确认**：MES 实际已入库，只是回执交不上来。批次算成功（确认方式 `MANUAL`），之后 MES 再交回执只存档
- **作废**：MES 确实没处理过（整批拒收、413、认证或地址错误，或书面确认未处理）
  - 交付批次：号退回，rev 不变，之后进新批次重新送达
  - 转出批次：按原内容重建新批次再推，来源 MES 照样会收到转出

作废后，MES 取回或提交这一批会得到 409 `BATCH_VOIDED`。此时**必须丢弃这批内容，不得用本地缓存处理**；否则其中的号在新批次中会回执「冲突」并触发告警。

回执对不上、内容不符的批次，说明 MES 已经处理过，不能作废。

### 冲突、过期告警

总部核查后二选一：

- 以本系统为准重推：把该号的 rev 调到大于 `existingRev`，以 `REASSIGN` 重推，MES 收下后自动解除挂起
- 直接关闭告警：MES 侧的挂起由现场按总部结论人工放行

---

## 6. 报文字段

### 6.1 通用约定

| 项 | 约定 |
| --- | --- |
| 传输 | HTTPS（TLS 1.2+）、JSON（UTF-8），请求和应答都支持 gzip；不跟随 3xx 跳转 |
| 字段名 | 英文小驼峰；请求和应答都带 `schemaVersion`（首版 `1.0`）；新增字段只加不改，不认识的字段忽略 |
| 时间 | ISO 8601，一律 UTC，如 `2026-10-19T02:37:00Z` |
| SN | 不超过 64 位，**区分大小写**（`AX` 与 `ax` 是两个号），只含字母、数字、`.`、`_` |
| `length`、`serialDec` | 都按字符串原样传，不要转成数字。`serialDec` 由 36 进制 12 位流水换算后可能超出 JavaScript 安全整数 |
| `rev` | 整数，从 1 起，同一 SN 只增不减 |
| 批次号 | 不超过 40 位，全局唯一 |
| `X-Request-Id` | 每次请求都带（UUID），双方记日志；它不是去重依据 |
| 成功判断 | HTTP 200 且 `code = OK` 为成功，出错不得返回 200；程序只按 `code` 判断，`message` 给人看 |

**认证**

- **本系统调 MES**（按组织二选一）
  - A：固定 Bearer 令牌 + 来源 IP 白名单
  - B：HMAC-SHA256 签名（越南等跨境公网推荐）。签名串为 `timestamp + "\n" + batchNo + "\n" + hex(SHA256(解压后的原始请求体字节))`，放在 `X-Sn-Timestamp`、`X-Sn-Signature`。不要解析后重新序列化再计算；与本地时间相差超过 5 分钟的请求拒绝，双方用 NTP 校时
- **MES 调本系统**：用 ② 换令牌，之后带 `Authorization: Bearer <token>`

**错误结构**

```json
{
  "schemaVersion": "1.0",
  "code": "RECEIPT_MISMATCH",
  "message": "回执枚数与批次不符",
  "batchNo": "PL-110-20261019-000045",
  "details": [{"field": "results", "reason": "期望 5000 枚，收到 4999 枚"}]
}
```

### 6.2 SN 组成

**SN = 前缀 + 长度 + 流水号 + 后缀**，例：`HX100000001A` = `HX` + `100` + `000001` + `A`。SN 字符串一经生成永不改变，会变的是它挂靠的单据和组织。

| 组成 | 规则 | 报文字段 |
| --- | --- | --- |
| 前缀 | 每张 PI 专属，前缀之间互不为开头 | 含在 `sn` 中 |
| 长度 | 金蝶订单物料行的长度，去掉多余的 0（`0100` → `100`，`10.50` → `10.5`）；规则可设为不带长度 | `length`；不带长度时为 `null` |
| 流水号 | 按 PI 从 1 起连续编号，位数固定；可能用 32、36 等进制写出 | `serial`、十进制写法 `serialDec`、所属 PI `serialPi` |
| 后缀 | 可选 | 含在 `sn` 中 |

从别的 PI 转来的号保留原样，所以当前 PI（`pi`）可能不等于流水所属 PI（`serialPi`）。

MES 只接触号状态中「待推送 → 已推送」这一段：批次推来或被取走时为待推送，MES 回执后变为已推送。「暂停交付」不是单独状态，期间号保持原状态，只是暂时不能分配和交付。

> [图：号的状态流转 · 6 种状态]

### 6.3 交付批次（`DELIVERY`）

推送的请求体和取批应答里的 `batch` 是同一个对象。新增（`NEW`）和改挂（`REASSIGN`）可以混在一批，字段相同。

```json
{
  "schemaVersion": "1.0",
  "batchNo": "PS-110-20261019-000123",
  "batchType": "DELIVERY",
  "orgCode": "110",
  "pi": "000001",
  "count": 1,
  "createdAt": "2026-10-19T02:37:00Z",
  "itemsDigest": "sha256:9f2c4e…",
  "items": [
    {
      "pushType": "NEW",
      "sn": "HX100000001A",
      "rev": 1,
      "serial": "000001",
      "serialDec": "1",
      "serialPi": "000001",
      "pi": "000001",
      "billNo": "VT2026090001",
      "billSeq": 1,
      "materialCode": "9148363",
      "length": "100",
      "customerCode": "0001"
    }
  ]
}
```

**批次头**（以下字段均必填，`pi` 例外见说明）

| 字段 | 说明 |
| --- | --- |
| `batchNo` | 批次号，去重依据；推送时须与请求头 `X-Sn-Batch-No` 相同 |
| `batchType` | `DELIVERY` 交付 / `TRANSFER_OUT` 转出 / `PING` 连接测试 |
| `orgCode` | 接收方组织：交付批次为号当前所属组织，转出批次为来源组织。MES 须校验等于本组织 |
| `pi` | 交付批次必填，只属一张 PI。转出批次为来源 PI；涉及多张 PI 时为 `null`，以每枚的 `from.pi` 为准 |
| `count` | 等于 `items` 条数，不超过单批上限（默认 5000） |
| `createdAt` | 建批时间，之后不变 |
| `itemsDigest` | 内容摘要，`sha256:` + 64 位小写十六进制。MES 存下后按字符串比对，**不自行计算** |

**交付项**

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `pushType` | 是 | `NEW` / `REASSIGN`，只说明送达原因，不改变接收规则 |
| `sn` | 是 | 完整 SN，区分大小写，批内不重复 |
| `rev` | 是 | 版本号，MES 据此决定是否覆盖 |
| `serial` / `serialDec` / `serialPi` | 是 | 进制流水 / 十进制流水 / 流水所属 PI，都按字符串传 |
| `pi` | 是 | 当前 PI，须等于批次头 `pi` |
| `billNo` / `billSeq` | 是 | 单据编号 / 单据序号（同一单据物料可能重复，靠序号区分） |
| `materialCode` | 否 | 物料编码，为空时传 `null` |
| `length` | 否 | 长度，规则不带长度时传 `null` |
| `customerCode` | 是 | 客户编码 |
| `transferKind` | 否 | 因转移送达时为 `BILL_TRANSFER` 转单 / `ORG_TRANSFER` 转组织，否则为 `null` |
| `transferDocNo` | 否 | 转单单号或转组织单号，否则为 `null` |
| `fromOrgCode` | 否 | 转移前的生产组织编码，否则为 `null` |

附件项次不进报文。

`transferKind`、`transferDocNo`、`fromOrgCode` 只用于追溯（例如转入现场核对实物和旧标签），不影响接收规则，MES 可以忽略。按「新增字段只加不改」，`schemaVersion` 仍为 `1.0`。

### 6.4 转出批次（`TRANSFER_OUT`）

号转到别的组织后，通知来源组织的 MES「这些号已不归你」。转出批次只含转出项，`orgCode` 为来源组织。

```json
{
  "schemaVersion": "1.0",
  "batchNo": "XO-110-20261019-000007",
  "batchType": "TRANSFER_OUT",
  "orgCode": "110",
  "pi": "000001",
  "count": 1,
  "createdAt": "2026-10-19T02:50:00Z",
  "itemsDigest": "sha256:51ab07…",
  "items": [
    {
      "pushType": "TRANSFER_OUT",
      "sn": "HX100000003A",
      "rev": 3,
      "serial": "000003",
      "serialDec": "3",
      "serialPi": "000001",
      "from": {"pi": "000001", "billNo": "VT2026090001", "billSeq": 1, "materialCode": "9148363", "customerCode": "0001"},
      "toOrgCode": "120",
      "transferKind": "ORG_TRANSFER",
      "transferDocNo": "TO-20261019-0007"
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `pushType`、`sn`、`rev`、`serial`、`serialDec`、`serialPi` | 同交付项；`rev` 为转移后加 1 的新值 |
| `from` | 转出前的挂靠：`pi`、`billNo`、`billSeq`、`materialCode`（可为 `null`）、`customerCode` |
| `toOrgCode` | 转入的生产组织编码 |
| `transferKind` | `BILL_TRANSFER` 转单 / `ORG_TRANSFER` 转组织 |
| `transferDocNo` | 转单单号或转组织单号 |

以上字段均必填。

### 6.5 回执

推送时放在 ① 的应答里，取批后通过 ④ 提交，两处字段相同。

```json
{
  "schemaVersion": "1.0",
  "batchNo": "PS-110-20261019-000123",
  "outcome": "PROCESSED",
  "receivedAt": "2026-10-19T02:37:03Z",
  "summary": {"total": 3, "accepted": 1, "duplicate": 0, "conflict": 1, "stale": 1},
  "results": [
    {"sn": "HX100000005A", "rev": 1, "result": "ACCEPTED"},
    {"sn": "HX100000001A", "rev": 2, "result": "CONFLICT", "existingRev": 2, "existingBatchNo": "PS-110-20261019-000100"},
    {"sn": "HX100000002A", "rev": 1, "result": "STALE", "existingRev": 2, "existingBatchNo": "PS-110-20261019-000100"}
  ]
}
```

整批拒收：

```json
{
  "schemaVersion": "1.0",
  "batchNo": "PL-110-20261019-000046",
  "outcome": "REJECTED",
  "rejectCode": "FIELD_TOO_LONG",
  "rejectMessage": "materialCode 超过本侧字段长度 20"
}
```

- `receivedAt` 为首次处理的时间
- `results` 每枚一条，条数等于批次 `count`；`result` 取 `ACCEPTED`、`DUPLICATE`、`CONFLICT`、`STALE` 之一
- `CONFLICT`、`STALE` 须带回 MES 已存的 `existingRev`、`existingBatchNo`，本系统据此找出是哪两批交出了同一枚号
- 批次结果：全部为收下或重复 → 成功；含冲突 → 成功（有冲突）并告警「疑似重复交付」；含过期 → 成功，首页告警「版本过期」

---

## 7. MES 实现清单（请书面确认）

- [ ] 批次报文结构与字段，含 `serialPi`、`pi`、字符串型流水（Q29、Q31）
- [ ] 认证方式：A 固定令牌 + 白名单，或 B HMAC 签名；服务器 NTP 校时
- [ ] 推送和取批共用同一个 `process`，按批次号加锁去重；再次送达返回存下的回执；同批次号不同内容报 409
- [ ] 校验 `orgCode` 等于本组织；整批拒收时不登记批次号
- [ ] 整批拒收也用 HTTP 200 + 拒收回执；4xx 只用于认证、地址、报文过大、无法解析
- [ ] 按 rev 逐枚回执四种结果，条数等于批次枚数；冲突、过期带回已存的 rev 和批次号
- [ ] 转出项用 `from` 写入挂靠，标「已转出」，记录 `toOrgCode`
- [ ] 按 PI 或单据取批时，也能取到本组织的转出批次（按 `from.pi`、`from.billNo` 匹配）
- [ ] `itemsDigest` 按字符串比对，不自行计算
- [ ] 整批一个事务，先提交后回执，5000 枚不超过 10 秒（单批枚数和超时可按实测调整）
- [ ] 取批后立即提交回执；处理不了就整批拒收，修好后取回补交
- [ ] 支持连接测试（`PING`）
- [ ] 正确处理 422 回执被拒和 409 `BATCH_VOIDED`（作废批次不再处理）；遇 401 能自动重新换令牌
