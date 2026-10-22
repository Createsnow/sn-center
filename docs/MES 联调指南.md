# MES 联调指南

> **相关**：接口契约见 [MES 对接说明（精简版）](<SN 防重管控 · MES 对接说明（精简版）.md>)　上线准入场景见 [需求文档（PRD）9.2](<SN 防重管控产品需求文档PRD.md>)　上线步骤见 [运维手册第 7 节](运维手册.md#7-上线步骤建议)　[文档导航](README.md)

MES 方还没接入时，用仓库里的三样工具分头验证。**真实 MES 接入后，仍要按 PRD 9.2 和 MES 方一起联调，19 个场景全部通过才能上线**；这里的工具让联调前就把能验证的都验证掉。

| 验证什么 | 工具 | 需要 MES 方吗 |
| --- | --- | --- |
| 本系统按契约推送、取批、回执，异常时的重试、续推、告警、人工处理 | 自动化测试 `uv run pytest`（内置模拟 MES） | 不需要 |
| 在测试环境用页面和接口走一遍真实流程，注入 MES 故障看本系统怎么处理 | **模拟 MES** `devtools/mes_mock.py` | 不需要 |
| MES 方实现的接收推送接口（①）是否符合契约 | **契约测试** `devtools/mes_conformance.py` | 只要 MES 测试环境的接口地址 |
| MES 方的取批客户端（② → ③ → ④）、MES 本侧数据 | 本系统测试环境 + MES 测试环境 | 需要 |

三样工具共用同一份 `process(batch)` 参考实现（`devtools/mes_mock.py` 的 `MesSim`）：自动化测试里的「假 MES」就是它，契约测试先对它跑一遍全部通过，再对 MES 方的实现跑。

---

## 1. 自动化测试

```bash
uv run pytest -q                                  # 全部（集成测试需要 MySQL，见 README）
uv run pytest -q tests/test_delivery.py tests/test_transfers.py tests/test_mes_tools.py
```

测试启动完整应用，连接真实 MySQL、假金蝶和模拟 MES，按组织注入 MES 故障（503、断连、处理后应答丢失、整批拒收、回执少一枚、413 等）。覆盖到哪些 9.2 场景见[第 5 节](#5-prd-92-场景对照表)。

---

## 2. 模拟 MES：本系统单独联调

### 2.1 启动并接入

```bash
# 组织 110 用令牌 tok-110（TOKEN 方式时是 Bearer 令牌，HMAC 方式时是签名密钥）；可重复 --secret 配多个组织
uv run python -m devtools.mes_mock serve --port 9001 --secret 110=tok-110
# 本系统在别的机器上：--host 0.0.0.0；要 HTTPS：--tls-cert cert.pem --tls-key key.pem
```

1. 「生产组织」页，组织 110 的 MES 对接配置：接口地址填 `http://<模拟 MES 所在机器>:9001/mes/110`，认证方式 TOKEN（或 HMAC），凭证填 `tok-110`。
2. 点 **连接测试**，模拟 MES 的窗口打印 `PING … → 200 PROCESSED` 即通过；再开启「对接 MES」。
3. 正常走 生成 → 分配：每次推送在模拟 MES 窗口打印一行（批次号、第几次发送、结果汇总），在「SN 查询 → 交付批次」核对本系统一侧。

模拟 MES 的数据只在内存里，重启即清空。各组织的 SN 表、批次表随时可查：`curl http://127.0.0.1:9001/_mock/state`。

### 2.2 注入故障

```bash
# 组织 110 的推送一律返回 503（times = 1 表示只生效一次，之后自动恢复；不填一直生效）
curl -X POST http://127.0.0.1:9001/_mock/fault -H 'Content-Type: application/json' \
  -d '{"org": "110", "mode": "http", "status": 503, "times": 1}'
curl -X DELETE 'http://127.0.0.1:9001/_mock/fault?org=110'     # 清除故障
curl 'http://127.0.0.1:9001/_mock/received?limit=5'             # 最近收到的推送（请求头、首枚号）
curl -X POST http://127.0.0.1:9001/_mock/reset                   # 清空 MES 本侧数据和记录
```

| mode | 模拟 MES 的行为 | 本系统应该 |
| --- | --- | --- |
| `http` | 返回 `status`（默认 503）+ 错误结构，不处理；可带 `code`、`message` | 503 / 429 / 5xx：按 1、5、15 分钟重试，用尽后列入首页「推送中断」；401 / 403 / 404：不重试，配置状态标为异常；413：不重试，可作废后按调小的枚数重新组批 |
| `drop` | 不处理，直接断开连接 | 按断连重试 |
| `drop_after` | **先处理并落库**，再断开连接 | 重试或 MES 取批时拿到同一份回执（`replayed = true`），批次成功 |
| `slow` | 先处理并落库，延迟 `delay` 秒（默认 70）再应答 | 超过推送超时按超时重试，结果同上 |
| `in_progress` | 返回 409 `BATCH_IN_PROGRESS` | 重试 |
| `reject` | HTTP 200 + 整批拒收（`rejectCode` 默认 `FIELD_TOO_LONG`，可用 `code` 改），不登记批次号 | 批次失败、号冻结、首页告警；清除故障后「继续推送」原批次成功，或作废后重新组批 |
| `drop_result` | 正常处理，但回执少一枚 | 批次失败（`RECEIPT_MISMATCH`），不能作废 |

连接测试（`PING`）默认不受故障影响；要连 PING 一起故障，加 `"ping": true`。

测试环境要让重试快一点，可以设 `SN_PUSH_RETRY_INTERVALS_MINUTES=0,0,0`（见运维手册第 2 节，正式环境不要改）。

**冲突、过期**：先预置 MES 本侧「已有」的一枚号，再让本系统推送它。同 rev、别的批次 → 回执冲突，首页告警「疑似重复交付」；rev 更大 → 回执过期，告警「版本过期」。之后可在告警里「以本系统为准重推」，验证 MES 收下更大的 rev 并解除挂起。

```bash
curl -X POST http://127.0.0.1:9001/_mock/seed -H 'Content-Type: application/json' \
  -d '{"org": "110", "sn": "HX100000001A", "rev": 1, "lastBatchNo": "PS-OTHER"}'   # rev 填 5 即造过期
```

### 2.3 扮演 MES 取批（接口 ②～④）

先在「账户」页给组织 110 建 MES 接口账户，记下只显示一次的密钥。

```bash
# 取一轮：② 换令牌 → ③ 取批 → process → ④ 提交回执，remaining > 0 时继续取，没有可给的批次就停
uv run python -m devtools.mes_mock pull --base http://127.0.0.1:8000 --org 110 --emp MES110 --key <密钥>
uv run python -m devtools.mes_mock pull ... --pi 000001 --bill VT2026090001      # 按 PI / 单据 / 两者组合取
uv run python -m devtools.mes_mock pull ... --batch-no PS-110-20261019-000123    # 按批次号取回

# 推送和取批同时开（与真实 MES 一样共用一套本侧数据，每 5 分钟不带范围取一次）
uv run python -m devtools.mes_mock serve --port 9001 --secret 110=tok-110 \
  --pull-base http://127.0.0.1:8000 --pull-org 110 --pull-emp MES110 --pull-key <密钥>
```

令牌失效（401 `TOKEN_EXPIRED` / `TOKEN_INVALID`）时自动重新换令牌，429 按 `Retry-After` 等待，409 `BATCH_VOIDED` 丢弃该批。

要逐个接口手工试（例如构造不符的回执看 422），用 curl 或服务的 `/docs`（`open` 分组）：

```bash
BASE=http://127.0.0.1:8000/api/mes/v1
TOKEN=$(curl -s $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"empNo":"MES110","secret":"<密钥>"}' | jq -r .accessToken)
curl -s $BASE/batches/next -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"1.0","pi":null,"billNo":null,"batchNo":null}' > next.json
# 按全部收下生成回执；删掉一条 results 或改一枚 rev 就能看到 422 RECEIPT_MISMATCH
jq '{schemaVersion:"1.0", batchNo:.batch.batchNo, outcome:"PROCESSED", receivedAt:"2026-10-21T00:00:00Z",
     summary:{total:.batch.count, accepted:.batch.count, duplicate:0, conflict:0, stale:0},
     results:[.batch.items[] | {sn, rev, result:"ACCEPTED"}]}' next.json > receipt.json
curl -s $BASE/batches/$(jq -r .batch.batchNo next.json)/receipt -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data @receipt.json
curl -s "$BASE/sn/delivered?pi=000001&pageSize=1000" -H "Authorization: Bearer $TOKEN"     # ⑤
curl -s "$BASE/batches/<batchNo>/items" -H "Authorization: Bearer $TOKEN"                   # ⑥
```

开启「对接 MES」要先通过连接测试，所以只测取批也得先起模拟 MES（或 MES 方的接收接口）。

---

## 3. 契约测试：验证 MES 方的接收接口

MES 方的接收推送接口（①）在测试环境上线后就能测，不需要本系统。脚本自己造批次，按对接说明的方式发送（gzip、Bearer 或 HMAC 签名、`X-Sn-Batch-No` 等请求头），逐项核对应答：

```bash
uv run python -m devtools.mes_conformance --url https://mes-test.example.com/sn/receive --org 110 \
  --auth TOKEN --secret <令牌> --report mes-110-report.md
# HMAC：--auth HMAC --secret <签名密钥>；再加 --perf 测 5000 枚一批的处理时间
```

脚本只依赖 Python 3.11+ 和 httpx，可以把 `devtools/mes_conformance.py` 单独交给 MES 方自测，失败项和原因写在报告里。

- **只能对 MES 测试环境运行**：会写入一批测试 SN（前缀 `CT` + 本次运行号），其中一枚会因「冲突」一直挂起（另一枚挂起后会被用例解除）。每次运行用新的运行号，不会与真实数据或上次运行重复。
- 检查项（共 26 项）：连接测试与 `orgCode` 校验；凭证错误、HMAC 时间戳偏差；首次送达逐枚回执、条数 / rev / summary 一致；同一批再次送达返回存下的回执；同批次号内容不同报 409；rev 更大覆盖、相同不同批冲突、更小过期、以本系统为准重推解除挂起；转出、转出头 `pi = null`、转回；不认识的字段忽略；七种整批拒收（并确认拒收后不登记批次号）；无法解析报 400；同一批同时送达两次只处理一次；5000 枚的处理时间。
- 接口应答看不到 MES 的本侧数据，报告末尾列出要在 MES 库或页面上**人工核对**的几项（挂起、已转出、清除已转出）。

契约测试只覆盖接口 ①。MES 的取批客户端（自动换令牌、`remaining` 续取、`BATCH_VOIDED` 丢弃、回执被拒 422 后修正重交）要让 MES 测试环境连本系统测试环境来验证，见第 4 节。

---

## 4. 联调顺序建议

1. **MES 开发期间**：本系统侧跑通自动化测试；在测试环境用模拟 MES 走通页面流程和第 2.2 节的故障（运维、实施熟悉首页告警和人工处理）。
2. **MES 接收接口上线到测试环境**：跑契约测试，失败项交 MES 方修正，直到全部通过；MES 方按对接说明第 7 节书面确认实现清单。
3. **端到端联调**：本系统测试环境的组织配上 MES 测试环境地址，连接测试通过后开启对接；给 MES 方建 MES 接口账户。按第 5 节逐项走 PRD 9.2 的 19 个场景，每项在本系统（交付批次、告警、操作痕迹）和 MES 本侧数据两边核对。
4. **出联调报告**（PRD 9.2、Q57）：19 个场景逐项记录结果和依据（批次号、截图），连同契约测试报告存档，全部通过后在正式环境开启对接。

---

## 5. PRD 9.2 场景对照表

「自动化测试」列为本系统一侧已有的用例（`tests/` 下，文件名 :: 用例名）；「契约测试」列为 `mes_conformance` 的检查项；「联调时重点」列为只有接上真实 MES 才能确认的部分。

| # | 场景（PRD 9.2） | 自动化测试 | 契约测试 | 联调时重点 |
| --- | --- | --- | --- | --- |
| 1 | 推送超时后自动重试并续推；期间 MES 取到同一批并回执，系统停止重试 | `test_delivery::test_retry_then_exhaust_then_resume_with_same_batch`、`test_mes_tools::test_push_response_lost_then_pull_gets_same_receipt` | C06 | MES 实际处理时间接近超时（60 秒）时的表现 |
| 2 | 推送建批、MES 拉取建批并发，同一枚号不进两个批次 | `test_mes_tools::test_push_and_pull_concurrently_never_overlap` | — | MES 按真实频率取批时同时推送 |
| 3 | 推送与拉取同时送达同一批次，MES 只处理一次，两边回执相同 | `test_mes_tools::test_push_response_lost_then_pull_gets_same_receipt` | C06、C25 | MES 的批次号锁在多台服务器上是否生效 |
| 4 | MES 取批后宕机未回执，再取仍是同一批；按 PI、单据、PI + 单据取；无批次时返回空并给出剩余、暂停枚数 | `test_delivery::test_pull_returns_same_unconfirmed_batch_until_receipt`、`test_orders::test_reversal_suspends_undelivered_and_resumes`、`test_delivery::test_pull_scope_checks` | — | PI + 单据组合取批（用 `mes_mock pull --pi --bill` 或 MES 客户端）；MES 重启后续取 |
| 5 | MES 整批拒绝（含组织编码不符）：不登记批次号、取批跳过、修正后续推或取回补交 | `test_delivery::test_reject_marks_failed_and_resume_after_fix`、`test_delivery::test_rejected_batch_voided_rebatches_and_refuses_voided` | C02、C17～C23 | MES 修好后按批次号取回补交回执 |
| 6 | 转单、转组织后改挂重推、转出推送；转回来源组织后清除「已转出」 | `test_transfers::test_org_transfer_pushes_reassign_to_target_and_transfer_out_to_source`、`test_transfers::test_same_org_bill_transfer_reassigns_pushed` | C08、C13～C15 | MES 本侧挂靠、「已转出」标记、`toOrgCode` |
| 7 | 转出后来源 MES 按批次号、按 PI 只读查不到已转出的号；转出批次推送中断时可取回并回执 | `test_transfers::test_org_transfer_pushes_reassign_to_target_and_transfer_out_to_source`、`test_transfers::test_transfer_out_pending_blocks_retransfer` | — | MES 用 ⑤⑥ 核对 |
| 8 | 转出批次未确认或失败时，这些号不能再转单、转组织 | `test_transfers::test_transfer_out_pending_blocks_retransfer` | — | 只涉及本系统，页面确认提示 |
| 9 | 按批次号取回：未确认、失败的可取回，不建批不改状态；已确认、已作废的被拒绝 | `test_delivery::test_pull_receipt_mismatch_rejected_then_fixed`、`test_delivery::test_void_returns_items_to_pool_and_rebatches`、`test_delivery::test_rejected_batch_voided_rebatches_and_refuses_voided` | — | MES 收到 409 `BATCH_CONFIRMED` / `BATCH_VOIDED` 后的处理 |
| 10 | 同一 SN 再次推送、拉取后，MES 中只保留一条 | `test_delivery::test_pull_returns_same_unconfirmed_batch_until_receipt` | C06 | MES 库里按 SN 查只有一条 |
| 11 | 单据暂停交付期间三通道都取不到未交付的号，已入批的可续推、可拉取；解除后自动继续推送 | `test_orders::test_reversal_suspends_undelivered_and_resumes`、`test_orders::test_pi_change_freezes_until_confirmed` | — | MES 取批时 `paused` 的显示与处理 |
| 12 | 按 rev 接收并逐枚回执：更大覆盖、相同不同批冲突并告警、更小过期；回执枚数不符批次失败 | `test_delivery::test_conflict_receipt_alerts_and_resend`、`test_delivery::test_stale_receipt_alerts_version_expired`、`test_delivery::test_receipt_missing_one_fails_batch` | C05、C09、C10、C12 | MES 冲突挂起、禁止打标 |
| 13 | 连接测试批次：认证、组织编码校验通过即成功，MES 不写数据 | `test_accounts::test_mes_enable_requires_test_and_disable_blocked` | C01～C04 | MES 库里没有 PING 记录 |
| 14 | 回执与批次不符：推送应答、拉取回执不符都记失败，取批跳过；修正后提交正确回执成功 | `test_delivery::test_receipt_missing_one_fails_batch`、`test_delivery::test_pull_receipt_mismatch_rejected_then_fixed` | C05（校验规则相同） | MES 收到 422 后修正重交 |
| 15 | 人工处理：拒收批次作废后重新组批；报文过大作废后按调小的枚数组批；转出批次作废后重建送达；作废批次取回、回执被拒；人工确认后停止重试 | `test_delivery::test_rejected_batch_voided_rebatches_and_refuses_voided`、`test_delivery::test_void_returns_items_to_pool_and_rebatches`、`test_transfers::test_void_transfer_out_rebuilds_and_delivers`、`test_delivery::test_manual_confirm_stops_retry` | — | MES 丢弃作废批次、不用本地缓存处理；书面确认流程 |
| 16 | 冲突、过期告警「以本系统为准重推」后，MES 接收更大的 rev 并解除挂起 | `test_delivery::test_conflict_receipt_alerts_and_resend` | C11 | MES 解除挂起 |
| 17 | 同组织改挂待推送的号再转走，来源 MES 收到转出；关闭对接后转走曾推送的号，转出照常送达 | `test_transfers::test_same_org_reassign_then_other_org_still_sends_transfer_out`、`test_transfers::test_transfer_out_after_source_disabled_mes` | — | 关闭对接后 MES 仍保留接收接口 |
| 18 | 跨组织转移后，转入方交付与来源转出不论谁先确认，MES 持有组织都正确；再次转移时转出推给正确的组织 | `test_transfers::test_holder_correct_when_target_confirms_first` | — | 两个组织的 MES 同时在线时的顺序 |
| 19 | 转出批次推送中断后，来源 MES 按转出前的 PI 或单据取批能取到；号已全部转走的 PI 照常可取 | `test_transfers::test_transfer_out_pending_blocks_retransfer` | — | 按单据取转出批次（`mes_mock pull --bill`） |

另有 `test_delivery::test_mes_token_lock_after_failures`、`test_delivery::test_mes_token_invalid_after_secret_reset`、`test_query_audit::test_rate_limited_pull_leaves_failure_audit` 覆盖换令牌锁定、重置密钥后令牌失效、取批限流，联调时确认 MES 客户端能自动重新换令牌、按 `Retry-After` 等待。
