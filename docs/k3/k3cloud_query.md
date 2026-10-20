# 生产订单查询接口

单查询（`View`）和全查询（`ExecuteBillQuery`）成功时返回同一种结构：`rows` 中每一项是一张生产订单。表头字段只出现一次，明细放在 `entries`。全查先按明细取回多行，再按单据编号合并。

只保留单据状态为 **已审核**（`C`）的单据。创建、审核中、重新审核、暂存不返回。

业务对象：`PRD_MO`（生产订单）。客户编码、PI、PO、生产组织在表头。附件项次、物料编码、物料名称、长度、数量在明细。单据内码不返回。

金蝶单查返回的是整张单据，每个参数的注释见 [生产订单完整数据结构](prd_mo_structure.md)。下面是整理后的结果。

## 统一返回

成功：

```jsonc
{
  // 是否成功，boolean
  "success": true,
  // 单据。一张生产订单一项；没有可返回单据时为 []
  "rows": [
    {
      // 单据编号，string
      "billNo": "1-ADT202312225",
      // 单据状态，string。只会是 C 已审核
      "documentStatus": "C",
      // 客户编码，string。没有客户时为 ""
      "customerNumber": "1339",
      // PI，string
      "pi": "ADT231212-1339-01",
      // PO，string。没有值时为 ""
      "po": "",
      // 生产组织编码，string
      "prdOrgNumber": "100",
      // 生产组织，string。简体中文
      "prdOrgName": "深圳市爱德泰科技股份有限公司",
      // 明细。一行一条；没有明细时为 []
      "entries": [
        {
          // 附件项次，string。没有值时为 ""
          "fterm": "1",
          // 物料编码，string
          "materialNumber": "9144683",
          // 物料名称，string。简体中文
          "materialName": "带接头光纤线",
          // 长度，string。没有值时为 ""
          "length": "51.5",
          // 数量，number。没有值时为 0
          "qty": 125.0
        },
        {
          "fterm": "2",
          "materialNumber": "9144682",
          "materialName": "带接头光纤线",
          "length": "51.5",
          "qty": 125.0
        }
      ]
    }
  ]
}
```

上面是 `1-ADT202312225` 的实际返回。单据状态为已审核，两条明细的附件项次分别是 `1` 和 `2`。`VT2026090001` 的单据状态是重新审核，不会出现在 `rows` 里。

失败：

```jsonc
{
  // 是否成功，boolean
  "success": false,
  // 错误说明，string。来自 Errors[].Message；登录失败时用登录返回的 Message
  "message": "错误说明",
  // 失败时固定为空数组
  "rows": []
}
```

| 字段 | 类型 | 含义 | 来源 |
|---|---|---|---|
| `billNo` | string | 单据编号 | 表头 `FBillNo` / `BillNo` |
| `documentStatus` | string | 单据状态 | 表头 `FDocumentStatus` / `DocumentStatus`，只会出现 `C` |
| `customerNumber` | string | 客户编码 | 表头 `F_ora_kh.FNumber`；单查取 `F_ora_Base.Number` |
| `pi` | string | PI | 表头 `F_HX_PI` |
| `po` | string | PO | 表头 `F_HX_PO` |
| `prdOrgNumber` | string | 生产组织编码 | 表头 `FPrdOrgId.FNumber`；单查取 `PrdOrgId.Number` |
| `prdOrgName` | string | 生产组织 | 表头 `FPrdOrgId.FName`，取简体中文 |
| `entries` | array | 明细 | 见下表。全查按 `FBillNo` 把多行收成这一项 |

`entries` 的每一项：

| 字段 | 类型 | 含义 | 来源 |
|---|---|---|---|
| `fterm` | string | 附件项次 | 明细 `FTERM` |
| `materialNumber` | string | 物料编码 | 明细 `FMaterialId.FNumber` |
| `materialName` | string | 物料名称 | 明细 `FMaterialId.FName`，取简体中文 |
| `length` | string | 长度 | 明细 `Flength` |
| `qty` | number | 数量 | 明细 `FQty` |

每张单固定这 8 个键，每条明细固定这 5 个键。没有值时字符串用 `""`，数量用 `0`，不省略键。没有可返回的单据时 `rows` 为 `[]`。一张已审核单据没有明细时，单据仍返回，`entries` 为 `[]`。

客户编码、PI、PO、生产组织编码、生产组织在表头。附件项次、物料编码、物料名称、长度、数量在明细，一张单有多条时放进 `entries`，按分录顺序排列。没有客户时 `customerNumber` 为 `""`。单据内码不出现在 `rows` 里。

## 单据状态

单据状态在表头，不在明细。单查路径是 `DocumentStatus`，全查字段是 `FDocumentStatus`。

| 代码 | 名称 | 是否返回 |
|---|---|---|
| `A` | 创建 | 不返回 |
| `B` | 审核中 | 不返回 |
| `C` | 已审核 | 返回 |
| `D` | 重新审核 | 不返回 |
| `Z` | 暂存 | 不返回 |

明细上的业务状态（`Status` / `FStatus`：计划、计划确认、下达、开工、完工、结案、结算）不参与筛选。已审核单据的全部明细都进入 `entries`。

筛选按整张单执行：

- 全查把条件写进 `FilterString`：`FDocumentStatus='C'`。其它过滤条件用 `AND` 接在后面。
- 单查不能在 `View` 入参里按状态过滤。先取整张单，`DocumentStatus` 不是 `C` 时不生成单据。
- `VT2026090001` 的单据状态是 `D`，因此单查和全查的 `rows` 都是 `[]`。

## 同步

前端提供两种同步，都只写入单据状态为已审核的生产订单。

| 方式 | 界面 | 查询 | 结果 |
|---|---|---|---|
| 全量同步 | 全量同步按钮 | 全查询，`FilterString` 为 `FDocumentStatus='C'`，分页直到最后一页 | 用本次返回的全部单据覆盖本地列表 |
| 按单据编号同步 | 输入单据编号后单独同步 | 单查询，`data.Number` 为该单据编号 | 用返回结果覆盖本地同一单据编号；单据状态不是已审核时，本地不保留这张单 |

按单据编号同步时不走全查询。用户未输入单据编号时，不发起单查询。

## 页面展示

左右两张横表。接口返回的 `rows` 每一项是左侧的一张单据，`entries` 是右侧的物料明细。

左边一张单据一行。单查通常只有一行；全查有多张单据时继续往下加行。点中一行，右边只显示该单的 `entries`。单查默认选中这一张，全查默认选中第一行。

### 左表：单据

| 列 | 字段 | 取值 |
|---|---|---|
| 客户编码 | `customerNumber` | 没有客户时为空 |
| 单据编号 | `billNo` | |
| PO | `po` | |
| PI | `pi` | |
| 数量合计 | `entries` 的 `qty` 之和 | 前端把该单明细数量相加，不作为接口字段返回 |
| 单据状态 | `documentStatus` | 显示「已审核」。列表里只会是 `C` |
| 生产组织编码 | `prdOrgNumber` | |
| 生产组织 | `prdOrgName` | |

### 右表：物料明细

只渲染当前选中单据的 `entries`。

| 列 | 字段 |
|---|---|
| 附件项次 | `fterm` |
| 物料编码 | `materialNumber` |
| 物料名称 | `materialName` |
| 长度 | `length` |
| 数量 | `qty` |

### 搜索筛选

同步完成后，在已同步的本地数据上筛选，不重新请求金蝶。筛的是左侧单据；右侧仍是当前选中单据的全部明细。当前选中单据被筛掉时，改为选中筛选结果的第一行；没有结果时右侧为空。

| 条件 | 字段 | 匹配 |
|---|---|---|
| 单据编号 | `billNo` | 包含 |
| 客户编码 | `customerNumber` | 包含 |
| PO | `po` | 包含 |
| PI | `pi` | 包含 |
| 生产组织编码 | `prdOrgNumber` | 包含 |
| 生产组织 | `prdOrgName` | 包含 |
| 附件项次 | `entries[].fterm` | 包含。命中任一明细即保留该单据 |
| 物料编码 | `entries[].materialNumber` | 包含。命中任一明细即保留该单据 |
| 物料名称 | `entries[].materialName` | 包含。命中任一明细即保留该单据 |
| 长度 | `entries[].length` | 包含。命中任一明细即保留该单据 |

多个条件同时填写时取交集。条件为空表示该列不限制。

## 单查询

金蝶接口：`Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc`

位置参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `formid` | 是 | 固定 `PRD_MO` |
| `data.Number` | 与 `Id` 二选一 | 单据编号，如 `1-ADT202312225` |
| `data.Id` | 与 `Number` 二选一 | 单据内码，如 `"100820"` |
| `data.CreateOrgId` | 否 | 创建组织内码，`0` 表示不按组织过滤 |
| `data.IsSortBySeq` | 否 | 明细是否按序号排序。本接口传 `true`，使 `entries` 与分录序号一致 |

`Number` 和 `Id` 不能同时为空。本接口不能指定返回列，成功时单据在 `Result.Result`。

`DocumentStatus` 不是 `C` 时不生成单据。`TreeEntity` 的每个元素生成 `entries` 的一项，表头字段只写一次。

| 统一字段 | 路径 |
|---|---|
| `billNo` | `Result.Result.BillNo` |
| `documentStatus` | `Result.Result.DocumentStatus` |
| `customerNumber` | `Result.Result.F_ora_Base.Number`。`F_ora_Base` 为空时用 `""` |
| `pi` | `Result.Result.F_HX_PI` |
| `po` | `Result.Result.F_HX_PO` |
| `prdOrgNumber` | `Result.Result.PrdOrgId.Number`。`PrdOrgId` 为空时用 `""` |
| `prdOrgName` | `Result.Result.PrdOrgId.Name` 中 `Key = 2052` 的 `Value` |
| `fterm` | `Result.Result.TreeEntity[].FTERM` |
| `materialNumber` | `Result.Result.TreeEntity[].MaterialId.Number` |
| `materialName` | `Result.Result.TreeEntity[].MaterialId.Name` 中 `Key = 2052` 的 `Value` |
| `length` | `Result.Result.TreeEntity[].Flength` |
| `qty` | `Result.Result.TreeEntity[].Qty` |

`Result.ResponseStatus.IsSuccess` 不为 `true` 时，`success = false`，`message` 取 `Errors[].Message`。`MsgCode` 含义：

| MsgCode | 含义 |
|---|---|
| 0 | 默认 |
| 1 | 上下文丢失 |
| 2 | 没有权限 |
| 3 | 操作标识为空 |
| 4 | 异常 |
| 5 | 单据标识为空 |
| 6 | 数据库操作失败 |
| 7 | 许可错误 |
| 8 | 参数错误 |
| 9 | 指定字段或值不存在 |
| 10 | 未找到对应数据 |
| 11 | 验证失败 |
| 12 | 不可操作 |
| 13 | 网控冲突 |
| 14 | 调用限制 |
| 15 | 禁止管理员登录 |

## 全查询

金蝶接口：`Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc`

一个 JSON：

| 参数 | 必填 | 说明 |
|---|---|---|
| `FormId` | 是 | 固定 `PRD_MO` |
| `FieldKeys` | 是 | 见下方，顺序必须一致 |
| `FilterString` | 是 | 必须包含 `FDocumentStatus='C'` |
| `OrderString` | 分页时必填 | 使用 `FBillNo ASC, FTreeEntity_FEntryId ASC`。分录内码只用于稳定翻页，不返回 |
| `StartRow` | 分页时必填 | 起始行，从 `0` 开始 |
| `Limit` | 分页时必填 | 每页行数，最大 `2000` |
| `TopRowCount` | 否 | `0` 表示不额外限制总数 |

`FieldKeys`：

```text
FBillNo,FDocumentStatus,F_ora_kh.FNumber,F_HX_PI,F_HX_PO,FPrdOrgId.FNumber,FPrdOrgId.FName,FTERM,FMaterialId.FNumber,FMaterialId.FName,Flength,FQty
```

`FilterString` 至少是：

```text
FDocumentStatus='C'
```

还要按单据编号或日期限制时，用 `AND` 接上，例如：

```text
FDocumentStatus='C' AND FBillNo='1-ADT202312225'
FDocumentStatus='C' AND FDate>='2026-09-01'
```

返回是二维数组，列顺序与 `FieldKeys` 相同。带上明细字段后，一行就是一条明细。先按下表读出列，再把同一个 `FBillNo` 的多行收成一张单，明细按返回顺序放进 `entries`。

| 列序号 | FieldKeys | 统一字段 |
|---|---|---|
| 0 | `FBillNo` | `billNo` |
| 1 | `FDocumentStatus` | `documentStatus` |
| 2 | `F_ora_kh.FNumber` | `customerNumber` |
| 3 | `F_HX_PI` | `pi` |
| 4 | `F_HX_PO` | `po` |
| 5 | `FPrdOrgId.FNumber` | `prdOrgNumber` |
| 6 | `FPrdOrgId.FName` | `prdOrgName` |
| 7 | `FTERM` | `entries[].fterm` |
| 8 | `FMaterialId.FNumber` | `entries[].materialNumber` |
| 9 | `FMaterialId.FName` | `entries[].materialName` |
| 10 | `Flength` | `entries[].length` |
| 11 | `FQty` | `entries[].qty` |

名称列随登录语言返回。本接口登录语言为 `2052`（简体中文），因此 `materialName`、`prdOrgName` 直接是中文，不用再从多语言数组里取。

翻页直到本页行数小于 `Limit`。合并单据要在全部页取完之后进行，避免一张单被页边界拆开后变成两项。不要省略单据状态条件，否则会把未审核的单据也查出来。

失败时金蝶可能返回对象而不是数组。`Result.ResponseStatus.IsSuccess = false` 时，`message` 取 `Errors[].Message`，`rows` 为 `[]`。

## 调用前登录

两种查询都要先登录，会话放在 cookie `kdservice-sessionid`。

接口：`Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc`

位置参数顺序：账套 Id、用户名、AppId、AppSecret、语言 Id（`2052`）。

`LoginResultType = 1` 为成功。失败时不再查询，`message` 使用登录返回的 `Message`。

## 示例

`1-ADT202312225` 已查询确认：单据状态为 `C`，两条明细都返回。物料名称都是「带接头光纤线」，长度都是 `51.5`。单查和全查整理后都是统一返回里的那一张单。

`VT2026090001` 的单据状态是 `D`（重新审核）。按本接口规则，单查取回后整张丢弃，全查也会被 `FDocumentStatus` 条件排除。两种查询都返回：

```json
{
  "success": true,
  "rows": []
}
```
