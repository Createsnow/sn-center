# 生产订单完整数据结构

业务对象 `PRD_MO`。单查 `View` 成功时，整张生产订单在 `Result.Result`。下面按单据 `VT2026090001` 的实际返回逐项注释。中文名、枚举和基础资料类型来自金蝶 `QueryBusinessInfo`。

查询脚本对外只返回单据状态为已审核的生产订单。`rows` 里每张单的表头字段只出现一次，明细在 `entries`，字段见 [生产订单查询接口](k3cloud_query.md)。那是筛选后的结果，不是这张单据的全部参数。原始参数以本文为准。

约定：

- 注释写在参数上一行。
- 基础资料同时有 `字段_Id` 和对象。未填写时内码为 `0`，对象为 `null`。
- 文本未填写时经常是一个空格 `" "`，键仍然存在。
- 多语言名称只保留简体中文，`Key = 2052`。实际返回里还可能有英文 `1033`、繁体 `3076`。
- 物料还返回使用组织、物料分组，以及基本、库存、计划、生产、辅助属性、库存属性和质量页签。这些属于物料主数据，本文不展开。
- `TreeEntity` 本样例只有 1 行。一张生产订单可以有多行，表头参数在每一行上重复。
- `ScheduledEntity`、`SerialSubEntity`、`FTREEENTITY_Link` 在这张样例里是空数组。元素形状附在文末。关联分录的取值来自 `ADP201904008`。

## 外层

成功时 `ResponseStatus` 只有 `IsSuccess`。失败时才返回 `MsgCode` 和 `Errors`。

```jsonc
{
  // 金蝶 WebAPI 返回体
  "Result": {
    // 调用状态。成功时通常只有 IsSuccess
    "ResponseStatus": {
      // 是否成功，boolean
      "IsSuccess": true
    },
    // 生产订单，结构见下一节
    "Result": {}
  }
}
```

失败时的状态：

```jsonc
{
  // 是否成功，boolean
  "IsSuccess": false,
  // 消息码，number。0 默认，1 上下文丢失，2 没有权限，5 单据标识为空，8 参数错误，10 未找到对应数据
  "MsgCode": 10,
  // 错误列表
  "Errors": [
    {
      // 出错字段。没有定位到字段时为 null
      "FieldName": null,
      // 错误说明，string
      "Message": "错误说明",
      // 数据行索引，number
      "DIndex": 0
    }
  ]
}
```

## 单据

`Result.Result`：

```jsonc
{
  // 单据内码，number。不进入统一返回
  "Id": 214414,
  // 业务对象标识，string。固定为 PRD_MO
  "FFormId": "PRD_MO",
  // 单据编号，string。统一返回的 billNo 取这里
  "BillNo": "VT2026090001",
  // 单据状态，string。A 创建，B 审核中，C 已审核，D 重新审核，Z 暂存。统一返回的 documentStatus 取这里，只保留 C
  "DocumentStatus": "D",
  // 审核人内码，number。未填写时为 0
  "ApproverId_Id": 0,
  // 审核人，基础资料 SEC_User，未填写时为 null
  "ApproverId": null,
  // 审核日期，日期时间，未填写时为 null
  "ApproveDate": null,
  // 修改人内码，number
  "ModifierId_Id": 9726223,
  // 修改人，基础资料 SEC_User，object
  "ModifierId": {
    // 内码，number
    "Id": 9726223,
    // 用户账号，string
    "UserAccount": "55047",
    // 姓名，string
    "Name": "林炎"
  },
  // 创建日期，string，日期时间
  "CreateDate": "2026-09-21T16:13:26.113",
  // 创建人内码，number
  "CreatorId_Id": 9726223,
  // 创建人，基础资料 SEC_User，object
  "CreatorId": {
    // 内码，number
    "Id": 9726223,
    // 用户账号，string
    "UserAccount": "55047",
    // 姓名，string
    "Name": "林炎"
  },
  // 修改日期，string，日期时间
  "ModifyDate": "2026-09-21T16:18:41.827",
  // 作废日期，日期时间，未填写时为 null
  "CancelDate": null,
  // 作废人内码，number。未填写时为 0
  "CANCELER_Id": 0,
  // 作废人，基础资料 SEC_User，未填写时为 null
  "CANCELER": null,
  // 作废状态，string。A 否，B 是
  "CancelStatus": "A",
  // 表头多语言文本，array。当前为空数组
  "MultiLanguageText": [],
  // 备注，array，多语言文本
  "Description": [],
  // 单据类型内码，string
  "BillType_Id": "281783bb73244d178d41ce550d877af0",
  // 单据类型，基础资料 BOS_BillType，object
  "BillType": {
    // 内码，string
    "Id": "281783bb73244d178d41ce550d877af0",
    // 编码，string
    "Number": "SCDD03_SYS",
    // 名称，多语言文本。这里只保留简体中文
    "Name": [
      // 简体中文。Key 2052；实际返回还可能包含 1033、3076
      { "Key": 2052, "Value": "直接入库-普通生产(光)" }
    ]
  },
  // 受托，boolean
  "Trustteed": false,
  // 生产车间内码，number。未填写时为 0
  "WorkShopID_Id": 0,
  // 生产车间，基础资料 BD_Department，未填写时为 null
  "WorkShopID": null,
  // 生产组织内码，number
  "PrdOrgId_Id": 4404370,
  // 生产组织，基础资料 ORG_Organizations，object。统一返回的 prdOrgNumber 取 Number，prdOrgName 取名称的简体中文
  "PrdOrgId": {
    // 内码，number
    "Id": 4404370,
    // 编码，string
    "Number": "110",
    // 名称，多语言文本。这里只保留简体中文
    "Name": [
      // 简体中文。Key 2052；实际返回还可能包含 1033、3076
      { "Key": 2052, "Value": "光纤连接（越南）有限公司" }
    ]
  },
  // 计划员内码，number。未填写时为 0
  "PlannerID_Id": 0,
  // 计划员，基础资料 BD_PLANNER，未填写时为 null
  "PlannerID": null,
  // 单据日期，string，日期时间
  "Date": "2026-09-21T00:00:00",
  // 货主类型，string。BD_Customer 客户，BD_OwnerOrg 业务组织
  "OwnerTypeId": "BD_OwnerOrg",
  // 货主内码，number。未填写时为 0
  "OwnerId_Id": 0,
  // 货主，基础资料 BOS_ItemClass，未填写时为 null
  "OwnerId": null,
  // 计划组内码，number。未填写时为 0
  "WorkGroupId_Id": 0,
  // 计划组，基础资料 BD_OperatorGroup，未填写时为 null
  "WorkGroupId": null,
  // 销售业务类型，string
  "BusinessType": "1",
  // 是否返工，boolean
  "IsRework": false,
  // 组织受托加工，boolean
  "IsEntrust": false,
  // 委托组织内码，number。未填写时为 0
  "ENTrustOrgId_Id": 0,
  // 委托组织，基础资料 ORG_Organizations，未填写时为 null
  "ENTrustOrgId": null,
  // 用料清单展开，string。1 按BOM展开，2 仅主产品
  "PPBOMType": "1",
  // 生产发料，boolean
  "IssueMtrl": false,
  // 期初生产订单，boolean
  "IsQCMO": false,
  // 接口附带字段，表单元数据中没有这一项。当前为 null
  "ScanBox": null,
  // PI，string。统一返回的 pi 取这里
  "F_HX_PI": "000001",
  // 跟单员内码，number。未填写时为 0
  "F_HX_GDY_Id": 0,
  // 跟单员，基础资料 BD_Saler，未填写时为 null
  "F_HX_GDY": null,
  // 计划完工日期，string，日期时间
  "F_BAM_Date": "2026-09-21T00:00:00",
  // PO，string。统一返回的 po 取这里，脚本会去掉首尾空格
  "F_HX_PO": " ",
  // 包装要求，string
  "F_TYLH_BZYQ": " ",
  // 发货要求，string
  "F_TYLH_FHYQ": " ",
  // 印字要求，string
  "F_TYLH_YZYQ": " ",
  // 其他要求，string
  "F_TYLH_QTYQ": " ",
  // TU_TXT(1)，string
  "F_TYLH_TXT_Img1": " ",
  // TU(一)，图片，string
  "F_TYLH_Img1": " ",
  // TU_TXT(2)，string
  "F_TYLH_TXT_Img2": " ",
  // TU(二)，图片，string
  "F_TYLH_Img2": " ",
  // 品质要求，string
  "F_TYLH_PZYQ": " ",
  // 标贴要求，string
  "F_TYLH_TBYQ": " ",
  // 销售员，string
  "F_ora_XSY": " ",
  // 打印次数，number
  "F_ora_PrintTimes": 0,
  // 打印时间，日期时间，未填写时为 null
  "F_ora_PrintDateTime": null,
  // 组织编码，string
  "F_ora_BHYJ": "VT",
  // 是否受托加工，string。1 是
  "F_ora_sfstjg": " ",
  // 类型依据，string
  "F_ora_djlx": " ",
  // 客户编码内码，number
  "F_ora_Base_Id": 145478,
  // 客户编码，基础资料 BD_Customer，object。字段标识是 F_ora_kh，全查列名是 F_ora_kh.FNumber。未填写时为 null。统一返回的 customerNumber 取 Number
  "F_ora_Base": {
    // 内码，number
    "Id": 145478,
    // 主数据内码，number。分配到组织后可与 Id 不同
    "msterID": 145478,
    // 编码，string
    "Number": "0001",
    // 名称，多语言文本。这里只保留简体中文
    "Name": [
      // 简体中文。Key 2052；实际返回还可能包含 1033、3076
      { "Key": 2052, "Value": "Cable Devices,Inc.DBA" }
    ]
  },
  // 自动生成，boolean
  "F_Box_qtr": false,
  // 用户内码，number
  "F_QFVQ_UserId_Id": 9726223,
  // 用户，基础资料 SEC_User，object
  "F_QFVQ_UserId": {
    // 内码，number
    "Id": 9726223,
    // 用户账号，string
    "UserAccount": "55047",
    // 姓名，string
    "Name": "林炎"
  },
  // 组织间受托加工adt，boolean
  "F_ora_adttrust": false,
  // 第三方单据编号，string
  "FTHIRDBILLNO": " ",
  // 多生产单文件，附件，string
  "FFileUpdatedu": " ",
  // 明细，array。一行一条。本样例只有 1 行，下面展开这一行的全部参数
  "TreeEntity": [
    {
      // 分录内码，number。不进入统一返回
      "Id": 734427,
      // 行号，number
      "Seq": 1,
      // 父级行主键，string。树形明细使用，普通行可能是空格
      "ParentRowId": " ",
      // 行展开类型，number
      "RowExpandType": 0,
      // 行标识，string
      "RowId": "d843aee6-e831-97a8-11f1-b593de0cc710",
      // 物料编码内码，number
      "MaterialId_Id": 6597779,
      // 物料编码，基础资料 BD_MATERIAL，object。统一返回的 materialNumber 取 Number，materialName 取名称的简体中文。下面只保留编码、名称和规格，物料的库存、计划、生产等页签不展开
      "MaterialId": {
        // 内码，number
        "Id": 6597779,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 6597774,
        // 编码，string
        "Number": "9148363",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "带接头光缆线" }
        ],
        // 规格型号。多语言数组，这里只保留简体中文
        "Specification": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "1xMTP(Female)/PC-1xMTP(Female)/PC OM4 24芯 4.5 PVC_OFNP 青绿色 150m(1393常用,康宁纤,标损,A端带拉手并绕轴)" }
        ]
      },
      // 产品类型，string。1 主产品，2 联产品，3 副产品
      "ProductType": "1",
      // 数量，number。统一返回的 qty 取这里
      "Qty": 10.0,
      // 计划完工时间，string，日期时间
      "PlanFinishDate": "2026-09-21T00:00:00",
      // 计划开工时间，string，日期时间
      "PlanStartDate": "2026-09-21T00:00:00",
      // 批号内码，number。未填写时为 0
      "Lot_Id": 0,
      // 批号，基础资料 BD_BatchMainFile，未填写时为 null
      "Lot": null,
      // 批号文本，string。与批号基础资料 Lot 一起返回
      "Lot_Text": " ",
      // 计划跟踪号，string
      "MTONo": " ",
      // 项目编号，string
      "ProjectNo": " ",
      // 基本单位数量，number
      "BaseUnitQty": 10.0,
      // 组别，number
      "Group": 2,
      // BOM版本内码，number
      "BomId_Id": 6597897,
      // BOM版本，基础资料 ENG_BOM，object
      "BomId": {
        // 内码，number
        "Id": 6597897,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 6597876,
        // 编码，string
        "Number": "9148363",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [],
        // 禁用状态，string。A 否，B 是
        "ForbidStatus": "A"
      },
      // 工艺路线内码，number。未填写时为 0
      "RoutingId_Id": 0,
      // 工艺路线，基础资料 ENG_Route，未填写时为 null
      "RoutingId": null,
      // 成品率%，number
      "YieldRate": 100.0,
      // 入库上限，number
      "StockInLimitH": 10.0,
      // 入库下限，number
      "StockInLimitL": 10.0,
      // 合格品入库选单数量，number
      "StockInQuaSelAuxQty": 0.0,
      // 基本单位合格品入库选单数量，number
      "StockInQuaSelQty": 0.0,
      // 基本单位不合格品入库选单数量，number
      "StockInFailSelQty": 0.0,
      // 不合格品入库选单数量，number
      "StockInFailSelAuxQty": 0.0,
      // 基本单位合格品入库数量，number
      "StockInQuaQty": 0.0,
      // 合格品入库数量，number
      "StockInQuaAuxQty": 0.0,
      // 基本单位不合格品入库数量，number
      "StockInFailQty": 0.0,
      // 不合格品入库数量，number
      "StockInFailAuxQty": 0.0,
      // 入库组织内码，number
      "StockInOrgId_Id": 4404370,
      // 入库组织，基础资料 ORG_Organizations，object
      "StockInOrgId": {
        // 内码，number
        "Id": 4404370,
        // 编码，string
        "Number": "110",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "光纤连接（越南）有限公司" }
        ]
      },
      // 仓库内码，number。未填写时为 0
      "StockId_Id": 0,
      // 仓库，基础资料 BD_STOCK，未填写时为 null
      "StockId": null,
      // 仓位内码，number
      "StockLocId_Id": 100013,
      // 仓位，object。F100001 是本账套的仓位维度标识
      "StockLocId": {
        // 仓位组合内码，number
        "Id": 100013,
        // 仓位编码，string。未填写时可能是空格
        "OPCODE": " ",
        // 仓位维度值内码，number
        "F100001_Id": 100009,
        // 仓位值，object
        "F100001": {
          // 内码，number
          "Id": 100009,
          // 编码，string
          "Number": "09",
          // 名称，多语言文本
          "Name": [
            // 简体中文。Key 2052；实际返回还可能包含 1033、3076
            { "Key": 2052, "Value": "成品区" }
          ]
        }
      },
      // 产出工序，number
      "OperId": 0,
      // 产出作业编码内码，number。未填写时为 0
      "ProcessId_Id": 0,
      // 产出作业编码，基础资料 ENG_Process，未填写时为 null
      "ProcessId": null,
      // 成本权重，number
      "CostRate": 100.0,
      // 分录多语言文本，array。当前为空数组
      "MultiLanguageText": [],
      // 销售订单项备注，array，多语言文本
      "Memo": [],
      // 计划确认日期，string，日期时间
      "PlanConfirmDate": "2026-09-21T16:17:13",
      // 排程日期，日期时间，未填写时为 null
      "ScheduleDate": null,
      // 下达日期，日期时间，未填写时为 null
      "ConveyDate": null,
      // 开工日期，日期时间，未填写时为 null
      "StartDate": null,
      // 完工日期，日期时间，未填写时为 null
      "FinishDate": null,
      // 结案日期，日期时间，未填写时为 null
      "CloseDate": null,
      // 结算日期，日期时间，未填写时为 null
      "CostDate": null,
      // 生成方式，string。1 手工录入，2 手工分解，3 MRP生成，4 MPS生成，5 初始化，6 拆分，7 订单展开
      "CreateType": "1",
      // 来源单据ID，number
      "SrcBillId": 0,
      // 来源单据分录内码，number
      "SrcBillEntryId": 0,
      // 销售订单ID，number
      "SaleOrderId": 0,
      // 需求单据，string。销售订单号
      "SaleOrderNo": " ",
      // 销售订单分录内码，number
      "SaleOrderEntryId": 0,
      // 需求组织内码，number
      "RequestOrgId_Id": 4404370,
      // 需求组织，基础资料 ORG_Organizations，object
      "RequestOrgId": {
        // 内码，number
        "Id": 4404370,
        // 编码，string
        "Number": "110",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "光纤连接（越南）有限公司" }
        ]
      },
      // 基本单位汇报选单数量，number
      "RepQuaSelQty": 0.0,
      // 基本单位不合格数量，number
      "RepFailQty": 0.0,
      // 汇报选单数量，number
      "RepQuaSelAuxQty": 0.0,
      // 合格数量，number
      "RepQuaAuxQty": 0.0,
      // 不合格数量，number
      "RepFailAuxQty": 0.0,
      // 基本单位合格数量，number
      "RepQuaQty": 0.0,
      // 业务状态，string。1 计划，2 计划确认，3 下达，4 开工，5 完工，6 结案，7 结算。不参与统一返回的筛选
      "Status": "2",
      // 单位内码，number
      "UnitId_Id": 5856707,
      // 单位，基础资料 BD_UNIT，object
      "UnitId": {
        // 内码，number
        "Id": 5856707,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 5856707,
        // 编码，string
        "Number": "tiaoj",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "条" }
        ]
      },
      // 基本单位内码，number
      "BaseUnitId_Id": 5856707,
      // 基本单位，基础资料 BD_UNIT，object
      "BaseUnitId": {
        // 内码，number
        "Id": 5856707,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 5856707,
        // 编码，string
        "Number": "tiaoj",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "条" }
        ]
      },
      // 辅助属性内码，number。未填写时为 0
      "AuxPropId_Id": 0,
      // 辅助属性，未填写时为 null
      "AuxPropId": null,
      // 基本单位入库上限，number
      "BaseStockInLimitH": 10.0,
      // 基本单位入库下限，number
      "BaseStockInLimitL": 10.0,
      // 入库上限比例，number
      "StockInUlRatio": 0.0,
      // 源单类型，string
      "SrcBillType": " ",
      // 源单编号，string
      "SrcBillNo": " ",
      // 源单分录行号，number
      "SrcBillEntrySeq": 0,
      // 需求单据行号，number
      "SaleOrderEntrySeq": 0,
      // 基本单位成品数量，number
      "BaseYieldQty": 10.0,
      // 联副产品分录内码，number
      "CopyEntryId": 0,
      // 挂起状态，string。0 未挂起，1 挂起
      "IsSuspend": "0",
      // 入库下限比例，number
      "StockInLlRatio": 0.0,
      // 业务流程内码，string
      "BFLowId_Id": "f11b462a-8733-40bd-8f29-0906afc6a201",
      // 业务流程，基础资料 BOS_BFVersion，object
      "BFLowId": {
        // 内码，string
        "Id": "f11b462a-8733-40bd-8f29-0906afc6a201",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "生产直接入库流程" }
        ]
      },
      // 基本单位汇报不合格选单数量，number
      "RepFailSelQty": 0.0,
      // 汇报不合格选单数量，number
      "RepFailSelAuxQty": 0.0,
      // 生产车间内码，number
      "WorkShopID_Id": 4701983,
      // 生产车间，基础资料 BD_Department，object
      "WorkShopID": {
        // 内码，number
        "Id": 4701983,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 4701983,
        // 编码，string
        "Number": "VT0022",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "VT1-生产部" }
        ]
      },
      // 需求类型，string。1 相关，2 独立
      "ReqType": "1",
      // 需求优先级，number
      "Priority": 0,
      // 备料套数，number
      "StockReady": 0.0,
      // 基本单位备料数量，number
      "BaseStockReady": 0.0,
      // 基本单位报废数量，number
      "BaseScrapQty": 0.0,
      // 报废数量，number
      "ScrapQty": 0.0,
      // 基本单位返修数量，number
      "BaseRepairQty": 0.0,
      // 返修数量，number
      "RepairQty": 0.0,
      // 基本单位报废品入库选单数量，number
      "BaseStockInScrapSelQty": 0.0,
      // 报废品入库选单数量，number
      "StockInScrapSelQty": 0.0,
      // 基本单位报废品入库数量，number
      "BaseStockInScrapQty": 0.0,
      // 报废品入库数量，number
      "StockInScrapQty": 0.0,
      // 入库货主类型，string。BD_Customer 客户，BD_OwnerOrg 业务组织
      "InStockOwnerTypeId": "BD_OwnerOrg",
      // 入库货主内码，number
      "InStockOwnerId_Id": 4404370,
      // 入库货主，基础资料 BOS_ItemClass，object
      "InStockOwnerId": {
        // 内码，number
        "Id": 4404370,
        // 主数据内码，number。分配到组织后可与 Id 不同
        "msterID": 4404370,
        // 编码，string
        "Number": "110",
        // 名称，多语言文本。这里只保留简体中文
        "Name": [
          // 简体中文。Key 2052；实际返回还可能包含 1033、3076
          { "Key": 2052, "Value": "光纤连接（越南）有限公司" }
        ]
      },
      // 入库类型-推入库单用，string。1 合格品入库，2 不合格品入库，3 报废品入库，5 返工品入库
      "InStockType": " ",
      // 产品检验，boolean
      "CheckProduct": false,
      // 产出序列，string
      "OutPutOptQueue": " ",
      // 基本单位汇报完成数量，number
      "BaseRptFinishQty": 0.0,
      // 汇报完成数量，number
      "RptFinishQty": 0.0,
      // 紧急放行，string。A 否，B 是
      "QAIP": "A",
      // 成品数量，number
      "YieldQty": 10.0,
      // 倒冲领料，boolean
      "ISBACKFLUSH": true,
      // 未入库数量，number
      "NoStockInQty": 10.0,
      // 基本单位未入库数量，number
      "BaseNoStockInQty": 10.0,
      // 需求来源，string。1 销售订单，2 预测单，3 组织间需求单，4 安全库存，5 生产订单，6 委外订单，7 计划订单
      "ReqSrc": " ",
      // 结案人内码，number。未填写时为 0
      "ForceCloserId_Id": 0,
      // 结案人，基础资料 SEC_User，未填写时为 null
      "ForceCloserId": null,
      // 产线内码，number。未填写时为 0
      "REMWorkShopId_Id": 0,
      // 产线，基础资料 ENG_WorkCenter，未填写时为 null
      "REMWorkShopId": null,
      // 排产序号，number
      "ScheduleSeq": 0.0,
      // 排程开工时间，日期时间，未填写时为 null
      "ScheduleStartTime": null,
      // 排程完工时间，日期时间，未填写时为 null
      "ScheduleFinishTime": null,
      // 结案类型，string。A 自动结案，B 手工结案，C 强制结案，D 拆分结案
      "CloseType": " ",
      // 排程工序拆分数，number
      "ScheduleProcSplit": 0,
      // 序列号单位内码，number。未填写时为 0
      "SNUnitID_Id": 0,
      // 序列号单位，基础资料 BD_UNIT，未填写时为 null
      "SNUnitID": null,
      // 序列号单位数量，number
      "SNQty": 0.0,
      // 退库数量，number
      "ReStkQty": 0.0,
      // 基本单位退库数量，number
      "BaseReStkQty": 0.0,
      // 合格品退库数量，number
      "ReStkQuaQty": 0.0,
      // 基本合格品退库数量，number
      "BaseReStkQuaQty": 0.0,
      // 不合格品退库数量，number
      "ReStkFailQty": 0.0,
      // 基本不合格品退库数量，number
      "BaseReStkFailQty": 0.0,
      // 报废品退库数量，number
      "ReStkScrapQty": 0.0,
      // 基本报废品退库数量，number
      "BaseReStkScrapQty": 0.0,
      // 领补套数，number
      "PickMtlQty": 0.0,
      // 基本单位领补套数，number
      "BasePickMtlQty": 0.0,
      // 是否手工新增联副产品，number
      "ISNEWLC": 0,
      // 领料状态，string。1 未领料，2 部分领料，3 全部领料，4 超额领料
      "PickMtrlStatus": "1",
      // 基本单位发料通知套数，number
      "BaseIssueQty": 0.0,
      // 发料通知套数，number
      "IssueQty": 0.0,
      // 源拆分订单编号，string
      "SrcSplitBillNo": " ",
      // 源拆分订单行号，number
      "SrcSplitSeq": 0,
      // 源拆分订单分录内码，number
      "SrcSplitEntryId": 0,
      // 源拆分生产订单内码，number
      "SrcSplitId": 0,
      // 变更标志，boolean
      "MOChangeFlag": false,
      // 上级订单BOM分录内码，number
      "SrcBomEntryId": 0,
      // 返工品退库数量，number
      "ReStkReMadeQty": 0.0,
      // 基本单位返工品退库数量，number
      "BaseReStkReMadeQty": 0.0,
      // 基本单位返工数量，number
      "BaseReMadeQty": 0.0,
      // 返工数量，number
      "ReMadeQty": 0.0,
      // 基本单位返工品入库数量，number
      "BaseStockInReMadeQty": 0.0,
      // 返工品入库数量，number
      "StockInReMadeQty": 0.0,
      // 基本单位返工品入库选单数量，number
      "BaseStockInReMadeSelQty": 0.0,
      // 返工品入库选单数量，number
      "StockInReMadeSelQty": 0.0,
      // 强制结案原因，array，多语言文本
      "CloseReason": [],
      // 基本单位已排产数量，number
      "BaseScheduledQtySum": 0.0,
      // 已排产数量，number
      "ScheduledQtySum": 0.0,
      // 排产状态，string。1 未排产，2 部分排产，3 全部排产
      "ScheduleStatus": "1",
      // 首检，boolean
      "IsFirstInspect": false,
      // 首检状态，string。A 空，B 待首检，C 首检中，D 首检完成
      "FirstInspectStatus": "A",
      // 计划确认人内码，number
      "ConfirmId_Id": 9726223,
      // 计划确认人，基础资料 SEC_User，object
      "ConfirmId": {
        // 内码，number
        "Id": 9726223,
        // 用户账号，string
        "UserAccount": "55047",
        // 姓名，string
        "Name": "林炎"
      },
      // 下达人内码，number。未填写时为 0
      "ReleaseId_Id": 0,
      // 下达人，基础资料 SEC_User，未填写时为 null
      "ReleaseId": null,
      // 开工人内码，number。未填写时为 0
      "StartID_Id": 0,
      // 开工人，基础资料 SEC_User，未填写时为 null
      "StartID": null,
      // 完工人内码，number。未填写时为 0
      "FinishId_Id": 0,
      // 完工人，基础资料 SEC_User，未填写时为 null
      "FinishId": null,
      // 已预留，boolean
      "IsMRP": false,
      // 基本单位样本破坏数，number
      "BaseSampleDamageQty": 0.0,
      // 样本破坏数，number
      "SampleDamageQty": 0.0,
      // 启用日排产，boolean
      "ISENABLESCHEDULE": false,
      // BOM展开路径，string
      "PathEntryId": " ",
      // 用料清单分录内码，number
      "PPBOMENTRYID": 0,
      // BOM分录内码，number
      "BOMENTRYID": 0,
      // 用料清单类型，string
      "SrcFormID": " ",
      // 预计齐套数量，number
      "MatchQty": 0.0,
      // 库存齐套数，number
      "InvMatchQty": 0.0,
      // 齐套状况，string。0 不齐套，1 库存齐套，2 预计齐套，3 无齐套件
      "CompleteCon": " ",
      // 跟进备注，string
      "FRemarks": " ",
      // 预计齐套日期，日期时间，未填写时为 null
      "MatchDate": null,
      // 联副产品备注，string
      "NOTECOBY": " ",
      // 直送合格品入库数量，number
      "DirectStockInQuaQty": 0.0,
      // 直送领料选单数，number
      "DirectPickMtrlSelQty": 0.0,
      // 直送基本合格品入库数量，number
      "BaseDirectStockInQuaQty": 0.0,
      // 直送基本单位领料选单数，number
      "BaseDirectPickMtrlSelQty": 0.0,
      // 首检控制方式，string。0 无，1 严格控制，2 非严格控制
      "FirstQCControlType": "0",
      // 已计划运算，boolean
      "ISMRPCAL": false,
      // 生成下级订单标志，boolean
      "IsGenerateOrder": false,
      // 齐套优先级，number
      "ANALYSEPRIORITY": 0,
      // 生产线别，基础资料 BOS_ASSISTANTDATA_SELECT，array
      "F_TYLH_ADT_SCXB": [],
      // 产线备注，string
      "F_TYLH_CXBZ": " ",
      // 客户物料编码P/N内码，string。未填写时可能是空格
      "F_ora_CUST_Id": " ",
      // 客户物料编码P/N，基础资料 Sal_CustMatMappingView，未填写时为 null
      "F_ora_CUST": null,
      // 订单项备注，string
      "F_ora_ddxbz": " ",
      // 产品描述(特殊)，string
      "F_ora_cpms": " ",
      // 开始序列(其他)，string
      "F_ora_xlh": " ",
      // 结束序列号(其他)，string
      "F_ORA_txm": " ",
      // 标贴描述(其他)，string
      "F_ora_btms1": " ",
      // 线标内容(其他)，string
      "F_ora_tbms2": " ",
      // 可委外数量，number
      "F_OutQty": 10.0,
      // 工作流代码，string
      "F_HX_XSPI": " ",
      // 图纸编号，string
      "Ftuzhi": " ",
      // 产品单位，string
      "FProductUnit": " ",
      // 长度，string。统一返回的 entries[].length 取这里，脚本会去掉首尾空格
      "Flength": " ",
      // Adtek规格描述(其他)，string
      "FAdtek": " ",
      // 客户描述，string
      "FCD": " ",
      // 客户P/N，string
      "FPN": " ",
      // 附件项次，string。统一返回的 entries[].fterm 取这里
      "FTERM": "0002",
      // 结算币别内码，number。未填写时为 0
      "F_OFSettleCurr_Id": 0,
      // 结算币别，基础资料 BD_Currency，未填写时为 null
      "F_OFSettleCurr": null,
      // 结算价格，number
      "F_ora_Price_zj": 0.0,
      // MES关闭状态，string
      "F_ADT_CloseMES": " ",
      // MPO头数，number
      "F_ora_MPO": 0.0,
      // LC头数，number
      "F_ora_CG": 0.0,
      // MMC头数，number
      "F_ora_MMC": 0.0,
      // 序列号子单据体，array。本样例为空，元素字段见文末
      "SerialSubEntity": [],
      // 上游关联分录，array。本样例为空，元素字段见文末
      "FTREEENTITY_Link": []
    }
  ],
  // 排产明细，array。本样例为空，元素字段见文末
  "ScheduledEntity": [],
  // 单据转换携带信息。当前为 null
  "BOS_ConvertTakeDataInfo": null
}
```

## 序列号子单据体

`TreeEntity[].SerialSubEntity` 的元素。`VT2026090001` 中该数组为空，下面的值是空形状，不是实测数据。

```jsonc
{
  // 内码，number。View 会返回，表单字段列表里不单列
  "Id": 0,
  // 行号，number
  "Seq": 0,
  // 数量，number
  "SNQty": 0,
  // 序列号，string
  "SerialNo": "",
  // 序列号内码，number。未填写时为 0
  "SerialId_Id": 0,
  // 序列号，基础资料 BD_OverASerialView。未填写时为 null
  "SerialId": null,
  // 入库选单数量，number
  "SNStockInSelQty": 0,
  // 备注，string
  "SerialNote": "",
  // 基本单位数量，number
  "BaseSNQty": 0,
  // 基本单位入库选单数量，number
  "BaseSNStockInSelQty": 0,
  // 汇报选单数量，number
  "SNRptSelQty": 0,
  // 基本单位汇报选单数量，number
  "BaseSNRptSelQty": 0
}
```

## 排产明细

`ScheduledEntity` 的元素。`VT2026090001` 中该数组为空，下面的值是空形状，不是实测数据。

```jsonc
{
  // 内码，number。View 会返回，表单字段列表里不单列
  "Id": 0,
  // 行号，number
  "Seq": 0,
  // 生产订单分录内码，number
  "MOENTRYID": 0,
  // 生产订单行号，number
  "MOSEQ": 0,
  // 物料编码内码，number。未填写时为 0
  "DetailMaterialId_Id": 0,
  // 物料编码，基础资料 BD_MATERIAL。未填写时为 null
  "DetailMaterialId": null,
  // 日产量，number
  "DAILYOUTPUTQTY": 0,
  // 基本单位日产量，number
  "BASEDAILYOUTPUTQTY": 0,
  // 单位内码，number。未填写时为 0
  "DetailUnitID_Id": 0,
  // 单位，基础资料 BD_UNIT。未填写时为 null
  "DetailUnitID": null,
  // 基本单位内码，number。未填写时为 0
  "BaseUnitIdDetail_Id": 0,
  // 基本单位，基础资料 BD_UNIT。未填写时为 null
  "BaseUnitIdDetail": null,
  // 计划数量，number
  "PLANQTY": 0,
  // 基本单位计划数量，number
  "BASEPLANQTY": 0,
  // 基本单位排产数量，number
  "BASESCHEDULEDQTY": 0,
  // 排产数量，number
  "SCHEDULEDQTY": 0,
  // 产线内码，number。未填写时为 0
  "PRODUCTIONLINEID_Id": 0,
  // 产线，基础资料 PRD_LINE。未填写时为 null
  "PRODUCTIONLINEID": null,
  // 排产日期，日期时间，未填写时为 null
  "PlanStartDateD": null,
  // 排产结束，日期时间，未填写时为 null
  "PlanFinishDateD": null,
  // 锁定，boolean
  "ISLOCKED": false,
  // 排产标识，string
  "SchDtlRowId": "",
  // 基本单位差异数，number
  "BASEDIFFERQTY": 0,
  // 差异数，number
  "DIFFERQTY": 0,
  // 基本单位上线数量，number
  "BASEFINISHEDQTY": 0,
  // 上线数量，number
  "FINISHEDQTY": 0,
  // 发料数量，number
  "DIssueQty": 0,
  // 基本单位发料数量，number
  "BaseDIssueQty": 0,
  // 齐套数量1，number
  "MatchQty1": 0,
  // 齐套数量2，number
  "MatchQty2": 0
}
```

## 上游关联

`TreeEntity[].FTREEENTITY_Link` 的元素。`VT2026090001` 中该数组为空。下面是 `ADP201904008` 第一条明细的实际关联，来源是计划订单。

```jsonc
{
  // 关联行内码，number
  "Id": 100005,
  // 业务流程内码，string
  "FlowId": " ",
  // 流程路线内码，number。未填写时为 0
  "FlowLineId": 0,
  // 转换规则标识，string。PlanOrder_MO 表示计划订单下推生产订单
  "RuleId": "PlanOrder_MO",
  // 源单表格内码，number
  "STableId": 0,
  // 源单表名，string。T_PLN_PLANORDER 是计划订单
  "STableName": "T_PLN_PLANORDER",
  // 源单内码，string
  "SBillId": "100009",
  // 源单分录内码，string
  "SId": "100009",
  // 携带的原始基本单位数量，number
  "BaseUnitQtyOld": 20.0,
  // 携带的基本单位数量，number
  "BaseUnitQty": 20.0,
  // 关联迁移图，string
  "FLnkTrackerId": " ",
  // 上游状态，string
  "FLnkSState": " ",
  // 关联数量，number
  "FLnkAmount": 0.0,
  // 计划订单下推生产订单的迁移图，string
  "FLnkSeToMoTrackerId": " ",
  // 计划订单下推生产订单的上游状态，string
  "FLnkSeToMoSState": " ",
  // 计划订单下推生产订单的关联数量，number
  "FLnkSeToMoAmount": 0.0
}
```
