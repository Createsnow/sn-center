# MES 领取与打印契约

S5 接口挂在 `/api/v1/integrations`。人员页面和 MES 共享 SN Service、事务、持久操作及工厂授权。独立服务身份不能登录人员界面，也不能调用生成、分配、转厂或系统管理接口。尚未接入真实 MES；本机验证使用隔离 HTTP 客户端及 MySQL/Redis。

## 服务凭据

由同时具有全局 `system:security:administer`、`system:service-client:manage` 及可委派 `sn:factory:assign` 范围的人员管理员调用：

| 方法/路径（前缀 `/api/v1/system`） | 载荷与结果 |
| --- | --- |
| POST `/service-clients` | `{displayName,factoryIds,permissions}` → `{client,clientSecret}` |
| GET `/service-clients/{id}` | 非秘密元数据：id/clientId/displayName/enabled/version/factoryIds/permissions |
| POST `/service-clients/{id}/update` | `{version,enabled,grant:{displayName,factoryIds,permissions}}`，完整替换范围并推进版本 |
| POST `/service-clients/{id}/rotate` | `{version}` → 同一稳定主体及新秘密；旧秘密与旧令牌失效 |

这些管理调用仍使用人员 Bearer + CSRF。秘密由服务器随机生成，仅创建/轮换响应返回；只存口令散列，不存在默认共享 MES 密钥。凭据及令牌响应 `Cache-Control: no-store`。将秘密交给指定调用方的受保护配置，不提交 Git、日志或浏览器本地存储。服务主体与人员共用全局稳定身份 ID，授权来源为独立服务权限表，不从人员角色获得额外权限。

允许权限仅为 `sn:acquire:view`、`sn:acquire:take`、`sn:acquire:callback`、`sn:items:read`，工厂必须显式绑定。轮换不改变主体 ID，因此重新认证后仍能按原操作号查询/重放原操作。停用或撤销工厂/动作权限会阻止当前请求、排队执行和原结果重放。

`POST /api/v1/integrations/auth/token`：

```json
{"clientId":"<创建时返回的clientId>","clientSecret":"<受保护秘密>"}
```

响应统一 Result，data 为 `{accessToken,tokenType:"Bearer",expiresAt}`。使用 `Authorization: Bearer <accessToken>`；不发 Cookie，不返回刷新凭据，也不接受人员 JWT。令牌沿用本机 15 分钟配置，Redis 会话 30 分钟；没有离线静态白名单放行。到期重新换取令牌，写操作号不变。当前令牌接口按 clientId 12 次/分钟、IP 100 次/分钟；已认证 MES 主体总计 120 次/分钟，包含任务轮询。429 应退避；Redis 不可用明确失败，不能当作空业务结果。

## 业务接口

下表前缀 `/api/v1/integrations/sn`。业务 ID、流水、数量均用精确字符串；工厂使用新平台稳定 `factoryId`，由组织映射核对取得，禁止按工厂名称猜测或自行换算旧 ID。

| 方法/路径 | 权限与行为 |
| --- | --- |
| POST `/acquisitions` | take；`{operationId,factoryId,pi}`，领取整工厂 + PI 的全部合格 TO_ACQUIRE |
| GET `/operations/{kind}/{operationId}` | 本主体对应动作当前权限；kind 仅 TAKE/CALLBACK |
| GET `/results/{taskId}` | 本主体领取/回调的数量、状态、分类计数及 batchId |
| GET `/results/{taskId}/items?cursor=&size=1000` | 本主体完整逐项结果，固定 ordinal 游标 |
| GET `/acquire-batches/{batchId}/items?cursor=&size=1000` | view，原所属工厂；不可变 snapshot 与另列当前状态 |
| POST `/print-confirmations` | callback，固定批次确认 PRINTED，返回持久任务 |
| GET `/items?factoryId=&pi=&customer=&materialMode=ALL&material=&cursor=&size=1000` | items:read，当前工厂实时只读；customer 可选精确匹配；物料 ALL/EMPTY/VALUE |

任务本身使用 `GET /api/v1/tasks/{taskId}`；仅本人可查，不开放 MES 取消、文件下载或文件恢复。提交返回 HTTP 202 + TaskVO。先看 `operationState`，执行完成后查询业务结果，不把 `processed` 当作已提交数量。页面打印走人员接口 `/api/v1/sn/prints`，MES 回调不生成页面打印文件。

领取逐枚核对生成时稳定来源：来源分录仍存在且在有效快照、PI 与生产组织相符。不能用相同 PI 的其他有效订单代替原来源。转入例外仅由确定的成员、目标工厂/PI和转厂证明匹配；S6 完整转厂用例尚未交付时不会自动产生此证明。结果 `eligible`、`blocked` 完整持久化，零可领成功且 `batchId=null`。原操作号重放返回原结果，不领取后来新增或变为合格的号。

逐项领取分类：ACQUIRED、SOURCE_UNKNOWN、SOURCE_MISSING、SOURCE_INACTIVE、PI_CHANGED、ORG_CHANGED。固定批次每项有 ordinal、完整 SN、seqText/seqDec/seqPi、当时工厂/PI/客户/物料/来源字段。无法确定的流水为 null，不填 0。转厂、改名或重领不改历史成员；`currentMember=false` 提示现在已不属于原批次。

## 打印回调

清单与范围必须二选一，唯一目标是 PRINTED。下面的 UUID 由调用方对一次业务意图生成并持续保存；例子使用占位文本，实际请求必须是合法 UUID。

```json
{
  "operationId":"<稳定UUID>",
  "batchId":"<领取结果的batchId>",
  "targetStatus":"PRINTED",
  "sns":["G0001","G0002"]
}
```

或用 `ranges:[{startSn:"G0001",endSn:"G0005"}]`。端点必须在该固定批次中，拥有相同且已知的 seqPi/seqDec；按数字流水读取全部连续固定成员，禁止用完整 SN 字符串做 BETWEEN。区间内缺号、跨流水归属或未知流水拒绝整个请求；反向端点规范成同一区间，重叠范围和重复 SN 去重。

结果 `updatedQty`、`alreadyQty`、`blocked` 和每一项 outcome 保存后可完整分页。分类为 UPDATED、ALREADY_PRINTED、NOT_IN_BATCH、MISSING、TRANSFERRED、APPLYING、BATCH_CHANGED、STATE_CHANGED。必须匹配当前工厂、PI 和有效批次；即使已转回同一工厂，旧批次也不能确认新领取。已打印但现批次不同，返回 BATCH_CHANGED。请求成功只表示分类处理完成，不表示每个 SN 都更新。

## 幂等、分页与故障

主体类型 + 稳定主体 ID + 动作 + operationId 数据库唯一。规范化载荷相同重放原任务，不同返回 409；网络超时必须先查原号或原号原载荷重试。不能为同一意图自动换新号。新的意图需要新的 operationId。领取与回调跟随 PI 占用，竞争操作 409。业务、固定结果、成功审计和操作终态同事务。

游标是不透明签名文本，绑定主体、授权版本、集合或筛选，24 小时有效；不能改动或跨接口复用。每页最多 1000 项，`nextCursor=null` 才表示结束。固定批次和结果按 ordinal 遍历；实时 `/items` 按不可变月份 + ID 遍历，各页反映各自读取时的当前状态，不是固定导出快照。权限变化后重新认证并从首页读取，不能沿用旧版本游标。

当前单批资源准入 100000 枚。超限整批拒绝，不能私自切成多个业务领取意图掩盖整 PI 语义。默认执行事务 30 秒、总预算 300 秒；一万枚样本不是生产吞吐验收。错误 1004000020 为领取/批次变化（409）、21 为非法固定范围（400）、22 为无待打印成员（409）；统一错误信封保留 traceId，不泄露底层堆栈或秘密。

## 本机调用工具

工具不预置凭据，也不会自动创建服务身份。由管理员创建后，将 `{clientId,clientSecret}` 写入仅本机账号可读的秘密文件（例如 `deploy/.secrets/mes-client.json`），使用：

```powershell
./tools/invoke-mes.ps1 -CredentialFile ./deploy/.secrets/mes-client.json -Items -FactoryId '<工厂ID>' -Pi '<PI>'
./tools/invoke-mes.ps1 -CredentialFile ./deploy/.secrets/mes-client.json -Acquire -FactoryId '<工厂ID>' -Pi '<PI>' -OperationId '<保存的UUID>'
./tools/invoke-mes.ps1 -CredentialFile ./deploy/.secrets/mes-client.json -TaskId '<taskId>'
./tools/invoke-mes.ps1 -CredentialFile ./deploy/.secrets/mes-client.json -BatchId '<batchId>' -Size 1000
./tools/invoke-mes.ps1 -CredentialFile ./deploy/.secrets/mes-client.json -CallbackFile '<回调JSON路径>'
```

工具只连接 `127.0.0.1` 指定端口，认证后不输出 token/secret；写请求不自动重试或换号。返回业务 Result。真实 MES 环境的 HTTPS、秘密分发和轮换周期须在实际联调与上线验收落实。本工具的解析和拒绝配置检查不代替真实客户端联调。
