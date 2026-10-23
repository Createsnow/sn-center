# MES 新协议替换评估

> 评估对象：MES 方提出的新协议（推送、回执、拉取三段报文，见「接口json.txt」），对照《MES对接接口文档》与当前代码（`app/services/delivery/`、`app/services/mes_client.py`）。
> 结论：**不能整体替换**。拉取请求与现协议一致，可直接沿用；推送报文只能采纳字段说明的改进；回执报文不能替换。已采纳部分已改入《MES对接接口文档》8.1。

## 1. 结论一览

| 报文 | 结论 | 说明 |
| --- | --- | --- |
| 拉取（③ 请求体） | **可替换**（与现协议逐字段相同） | 无需改动 |
| 推送（① 请求体） | **部分采纳** | 字段说明的改进已合入；合并批次类型、去掉 `from`/`PING`、整体同步作废三处与业务逻辑冲突，不采纳 |
| 回执 | **不能替换** | 去掉了逐枚结果，回执核对、冲突告警、每日自检都依赖逐枚结果 |

## 2. 推送报文

### 2.1 已采纳（不影响业务逻辑，已改入文档）

| 字段 | 新协议说明 | 处理 |
| --- | --- | --- |
| `serial` | 流水号，可能为 36 进制写法 | 与现说明一致，措辞合入 |
| `billNo` | EEP 生产订单号 | 合入：即金蝶同步的生产订单号 |
| `billSeq` | EEP 单据明细项次 | 合入，保留「同一单据可能有相同物料，以项次区分」 |
| `itemsDigest` | 字符相同代表两次推送数据相同，不同需要处理 | 合入；「不同」的处理即 409 `BATCH_CONTENT_MISMATCH`（4.2 步骤 3） |

### 2.2 不采纳

**① `batchType` 只保留 `DELIVERY`，转移也走 `DELIVERY`**

- 跨组织转移时，原组织 MES 需要知道「这些 SN 已转走、停止打标」（PRD 5.2.8 第 4 条，防止两个工厂使用同一 SN）。现协议用独立的转移批次 `TRANSFER_OUT`（`XO` 开头）通知原组织，代码中转出批次单独建批、单独推送任务（`XO_KEY`）、确认时清除 MES 持有组织（`receipts.confirm`）。
- 新协议把它并入 `DELIVERY`，只靠每枚的 `toOrgCode` 区分，存在三个问题：
  1. `DELIVERY` 要求每枚 `pi` 等于批次 `pi`，而转出的 SN 可来自多张 PI（转出批次 `pi` 可为 null）；
  2. 去掉了 `from`（转出前挂靠）：原组织 MES 本地没有这个 SN 时（如推送已确认但 MES 数据丢失）无法建档标记「已转走」；
  3. MES 处理 `DELIVERY` 的动作是「新增或覆盖并清除已转走」，与转出动作「标记已转走」相反，同一类型承载两种相反动作容易误处理。
- 同组织转移、跨组织转入本来就走 `DELIVERY`（`pushType = REASSIGN / NEW`），新协议这部分与现协议一致。

**② 去掉测试批次 `PING`**

总部「连接测试」（`factories.test_connection` → `mes_client.ping_body`）发送 `PING` 批次校验地址、认证与压缩。去掉后连接测试无法进行，保留。

**③ 「拉取内容与本地不一致：多的新增，少的作废标记」**

现协议的批次是**增量**的：每批 ≤ 5000 枚（按组织「单批枚数」拆分），一张 PI 的号分在多个批次、经推送和拉取两条通道到达。若 MES 把一个批次当作全量快照、将批次中没有的本地 SN 标记作废，会把其他批次已下发的 SN 误作废，造成号丢失。

- SN 不再属于本组织时，SN 系统一定会发转移批次 `TRANSFER_OUT`，MES 据此标记，不需要比对推断；
- MES 需要核对全量时用 ⑤ 查询已下发 SN，且 ⑤⑥ 只用于对账，**不得据此删除或作废**（3.2）。

**④ 去掉 `transferKind`、`transferDocNo`、`fromOrgCode`**

这三个字段是选填、仅供追溯，SN 系统照常发送，MES 不处理可忽略（7.2「不认识的字段直接忽略」），不影响接收规则。但 5.3 按 `fromOrgCode` 识别转移类型，MES 若不需要区分，可以不实现，文档保留。

**⑤ 每枚必填 `toOrgCode`**

交付批次中 `toOrgCode` 恒等于批次 `orgCode`，信息重复；`toOrgCode` 仅在转移批次中有意义（转入组织，记录备查），现协议已有。不加入交付批次。

**⑥ `serialDec` 说明为「进制，可能为 36 进制」**

不正确。`serialDec` 是**十进制**流水（代码 `str(seq_dec)`），按字符串传是因为 36 进制 12 位流水换算成十进制超出 JavaScript 安全整数；36 进制写法在 `serial`。保留现说明。

## 3. 回执报文

新协议回执：

```jsonc
{"schemaVersion": "1.0", "code": "OK", "batchNo": "PL-110-20261019-000046", "batchResult": "SUCCESS", "firstConfirmation": true}
```

该结构与现协议 **④ 提交回执的应答**（SN 系统返回给 MES）基本相同，不是 MES 返回的回执。若作为 MES 回执（① 应答、④ 请求体）使用：

| 去掉的内容 | 依赖它的业务逻辑 | 替换后果 |
| --- | --- | --- |
| `results` 逐枚结果（`ACCEPTED`/`DUPLICATE`/`CONFLICT`/`STALE`） | 回执核对（`receipts._verify`：条数、SN、`rev` 逐枚对应） | 所有回执判为「回执与批次不符」，批次记失败，号冻结 |
| `existingRev`、`existingBatchNo` | 冲突、过期告警「疑似重复交付」「版本过期」（PRD 5.2.5 第 3、8 条） | 第 3 层防重失效，重复交付无法发现 |
| `summary` | 批次的接收、重复、冲突、过期枚数；每日交付自检（回执枚数核对） | 自检无法进行 |
| `outcome` + `rejectCode`/`rejectMessage` | 整批退回、冻结后续推送、修正后重处理（6.2） | 改为 `code = NG`、`batchResult` 写原因后可表达退回，但须同时保留逐枚结果，收益不大 |
| — | `firstConfirmation` 表示「SN 系统是否首次确认」，只有 SN 系统知道，MES 无法填写 | — |

若新协议的「回执」指 ④ 应答：现协议已是该结构，只有 `batchResult` 取值不同——现为 `SUCCESS` / `SUCCESS_CONFLICT`（成功但含冲突 SN）/ `FAILED`（整批退回已登记）；出错时 HTTP 非 200，按错误码（如 422 `RECEIPT_MISMATCH`）返回原因，不在 `batchResult` 写原因，也没有 `code = NG`。改为「SUCCESS / 失败原因」会丢失「成功但含冲突」，保留现协议。

## 4. 拉取请求

新协议拉取请求体（`schemaVersion`、`pi`、`billNo`、`batchNo`）与现协议 8.2 逐字段、逐说明相同，直接沿用。

## 5. 需与 MES 方确认

1. 转出通知继续使用独立的 `TRANSFER_OUT` 批次（含 `from`），MES 需实现该分支；
2. 保留 `PING` 测试批次；
3. MES 不得把单个批次当作全量、据此作废本地 SN；
4. 回执保留逐枚 `results`、`summary`，冲突、过期带 `existingRev`、`existingBatchNo`；
5. `serialDec` 为十进制流水字符串。
