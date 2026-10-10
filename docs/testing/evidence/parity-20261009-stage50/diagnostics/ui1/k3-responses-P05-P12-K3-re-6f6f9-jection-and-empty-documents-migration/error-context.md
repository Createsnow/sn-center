# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: k3-responses.spec.ts >> P05/P12 K3 response shapes, messages, later-page rejection and empty documents
- Location: e2e\parity\k3-responses.spec.ts:99:1

# Error details

```
Error: zh-CN-login-missing

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶登录失败：LoginResultType != 1"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-login-boolean

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶登录失败：LoginResultType != 1"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-login-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶登录失败：LoginResultType != 1"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-query-object

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶查询失败：unexpected response"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-query-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶查询失败：unexpected response"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-query-message-values

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶查询失败：组织权限不足; 123; false"
Received: "金蝶查询失败：组织权限不足"
```

```
Error: zh-CN-query-empty-message

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶查询失败："
Received: "金蝶查询失败：查询失败"
```

```
Error: zh-CN-view-missing-status

expect(received).toBe(expected) // Object.is equality

Expected: "金蝶查询失败：View failed"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: zh-CN-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: zh-CN-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: "单据 CMP-MO-001 已同步，2 行"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 25, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:39:21.581201", "total_qty": "10.0000"}]
```

```
Error: zh-CN-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: zh-CN-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: "单据 CMP-MO-001 已不在计划 / 计划确认，已从快照移除"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 27, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:39:30.319003", "total_qty": "10.0000"}]
```

```
Error: zh-CN-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: zh-CN-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: "单据 CMP-MO-001 已不在计划 / 计划确认，已从快照移除"
Received: "金蝶返回的数据格式不符合约定，原订单快照保持不变。"
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 29, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:39:35.257725", "total_qty": "10.0000"}]
```

```
Error: en-login-missing

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee login failed: LoginResultType != 1"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-login-boolean

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee login failed: LoginResultType != 1"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-login-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee login failed: LoginResultType != 1"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-query-object

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: unexpected response"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-query-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: unexpected response"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-query-message-values

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: 组织权限不足; 123; false"
Received: "Kingdee query failed: 组织权限不足"
```

```
Error: en-query-empty-message

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: "
Received: "Kingdee query failed: 查询失败"
```

```
Error: en-view-missing-status

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: View failed"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: en-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: en-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: "Order CMP-MO-001 synced, 2 lines"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 33, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:00.611839", "total_qty": "10.0000"}]
```

```
Error: en-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: en-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: "Order CMP-MO-001 is no longer planned; removed from the snapshot"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 35, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:09.410580", "total_qty": "10.0000"}]
```

```
Error: en-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: en-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: "Order CMP-MO-001 is no longer planned; removed from the snapshot"
Received: "K3 returned an unexpected data format. The previous order snapshot is unchanged."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 37, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:14.853974", "total_qty": "10.0000"}]
```

```
Error: vi-login-missing

expect(received).toBe(expected) // Object.is equality

Expected: "Đăng nhập Kingdee thất bại: LoginResultType != 1"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-login-boolean

expect(received).toBe(expected) // Object.is equality

Expected: "Đăng nhập Kingdee thất bại: LoginResultType != 1"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-login-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "Đăng nhập Kingdee thất bại: LoginResultType != 1"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-query-object

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: unexpected response"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-query-scalar

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: unexpected response"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-query-message-values

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: 组织权限不足; 123; false"
Received: "Truy vấn Kingdee thất bại: 组织权限不足"
```

```
Error: vi-query-empty-message

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: "
Received: "Truy vấn Kingdee thất bại: 查询失败"
```

```
Error: vi-view-missing-status

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: View failed"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: vi-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: vi-login-empty-session

expect(received).toBe(expected) // Object.is equality

Expected: "Lệnh CMP-MO-001 đã đồng bộ, 2 dòng"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 41, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:39.915540", "total_qty": "10.0000"}]
```

```
Error: vi-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: vi-view-empty-document

expect(received).toBe(expected) // Object.is equality

Expected: "Lệnh CMP-MO-001 không còn ở trạng thái kế hoạch; đã xóa khỏi ảnh chụp"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 43, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:48.595383", "total_qty": "10.0000"}]
```

```
Error: vi-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 502
```

```
Error: vi-view-empty-lines

expect(received).toBe(expected) // Object.is equality

Expected: "Lệnh CMP-MO-001 không còn ở trạng thái kế hoạch; đã xóa khỏi ảnh chụp"
Received: "K3 trả về dữ liệu sai định dạng. Ảnh chụp đơn hàng trước đó được giữ nguyên."
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 1, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 45, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:40:53.513498", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 39
Received length: 48
Received array:  [{"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": "CMP-MO-001", "created_at": "2026-10-09 09:39:05.991000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "b79d0110-10cf-48c1-aadd-47480b650996", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "f955d567-99e3-4677-a1ec-0978e3eb34a1", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": "CMP-MO-001", "created_at": "2026-10-09 09:39:07.327000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "d607ec8f-41a5-4ed8-a4f8-90012803d777", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "ef7ec11e-e553-4453-803b-9628d6945286", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": "CMP-MO-001", "created_at": "2026-10-09 09:39:08.502000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000002", "error_message": "金蝶登录失败：123", "factory_code": null, "factory_id": null, "factory_name": null, "id": "7ef356b1-a4e4-4d0d-a791-61cc1a1e6cde", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "59c6f890-0c09-4c88-8225-c988ada1ead5", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": "CMP-MO-001", "created_at": "2026-10-09 09:39:09.700000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "0e3716b1-4ecb-4561-b2bc-1c731590a925", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "d5506e0e-10f2-40da-bd3e-be121c6a5b57", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": null, "created_at": "2026-10-09 09:39:10.871000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "09b36fa3-c7e9-4b45-81b3-e17df39ec4bd", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "e762d18c-360c-409f-8b36-cdad34b1c54f", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": null, "created_at": "2026-10-09 09:39:12.026000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "aaeaf253-129d-493d-8548-d4d989cf96af", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "cde53ebb-cadd-4793-90ce-0ce3c587679a", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": null, "created_at": "2026-10-09 09:39:13.192000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000006", "error_message": "金蝶查询失败：组织权限不足", "factory_code": null, "factory_id": null, "factory_name": null, "id": "d5b8f6f0-8adc-43db-8cea-92d6bfeb0005", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "2ef9dbaf-1f69-40e7-81c2-42b5d1a65d8d", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": null, "created_at": "2026-10-09 09:39:14.389000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000006", "error_message": "金蝶查询失败：查询失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "5b1a0cb0-fa63-4eb2-adbe-494b4afb226c", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "ccbd3fe2-d8a2-4dd4-b017-461ca09fbcc0", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": null, "created_at": "2026-10-09 09:39:15.649000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000006", "error_message": "金蝶查询失败：查询权限不足", "factory_code": null, "factory_id": null, "factory_name": null, "id": "892e1458-ff7d-4371-9df8-afb038a9a249", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "7b4cac51-05cb-4981-88c2-93d9a936c1de", "transfer_id": null}, {"action": "sn:order:sync", "actor_id": 90020261009500000, "after_status": null, "batch_id": null, "before_status": null, "bill_no": "CMP-MO-001", "created_at": "2026-10-09 09:39:16.807000", "customer": null, "detail": "{}", "detail_text": "POST /api/orders/sync", "end_sn": null, "error_code": "1005000004", "error_message": "订单同步失败", "factory_code": null, "factory_id": null, "factory_name": null, "id": "6f144739-7389-4d40-80bf-cb41431bae6f", "material": null, "operation_id": null, "operator": "audit20261009500001", "operator_name": "同步痕迹操作员", "pi": null, "quantity": null, "reason": null, "result": "FAILED", "roles": "[\"PLATFORM_ADMIN\",\"SN_HQ\"]", "source": "PAGE", "start_sn": null, "to_factory_code": null, "to_factory_id": null, "to_pi": null, "trace_id": "8c2c5e63-1548-4508-82c2-6b74fae70cbd", "transfer_id": null}, …]
```

# Test source

```ts
  110 |   const helper = (action: string) =>
  111 |     JSON.parse(
  112 |       execFileSync(
  113 |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  114 |         ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, action, tag],
  115 |         { windowsHide: true, encoding: 'utf8' },
  116 |       ),
  117 |     );
  118 |   helper('prepare');
  119 |   const password = readFileSync(
  120 |     resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
  121 |     'utf8',
  122 |   ).trim();
  123 |   protectTestSecrets(password);
  124 |   const controlFile = resolve('../tools/.runtime/comparison-20261008/order-audits/k3-control.json');
  125 |   const control = (value: unknown) => {
  126 |     writeFileSync(controlFile + '.next', JSON.stringify(value));
  127 |     renameSync(controlFile + '.next', controlFile);
  128 |   };
  129 |   const observations: unknown[] = [];
  130 |   const pageErrors: string[] = [];
  131 |   page.on('pageerror', (error) => pageErrors.push(error.message));
  132 |   const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  133 |   const sync = async (label: string, text: typeof zh, expected: string, one: boolean, status: number) => {
  134 |     await expect(messages).toHaveCount(0);
  135 |     if (one) await page.getByPlaceholder(text.orders.billToSync, { exact: true }).fill('CMP-MO-001');
  136 |     const snapshot = helper('snapshot');
  137 |     const before = (await (await request.get('http://127.0.0.1:19091')).json()).wireRequests.length;
  138 |     const received = page.waitForResponse(
  139 |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  140 |     );
  141 |     await page.getByRole('button', { name: one ? text.orders.syncOne : text.orders.syncAll, exact: true }).click();
  142 |     const response = await received;
  143 |     await expect(messages).toHaveCount(1);
  144 |     const actual = await messages.innerText();
  145 |     expect.soft(response.status(), label).toBe(status);
  146 |     expect.soft(actual, label).toBe(expected);
  147 |     await expect(page.locator('.sn-page-actions [aria-busy="true"]')).toHaveCount(0);
  148 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  149 |     const after = helper('snapshot');
  150 |     if (status === 502) {
  151 |       expect.soft(after.orders, label + ' orders').toEqual(snapshot.orders);
  152 |       expect.soft(after.entries, label + ' entries').toEqual(snapshot.entries);
  153 |     }
  154 |     const traffic = (await (await request.get('http://127.0.0.1:19091')).json()).wireRequests.slice(before);
  155 |     const body = await response.json();
  156 |     observations.push({ label, status: response.status(), message: actual, body, traffic, orders: after.orders, entries: after.entries });
  157 |     await page.mouse.move(0, 0);
  158 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  159 |     if (response.status() !== 200)
  160 |       await messages.locator(prototype ? '.el-message__closeBtn' : 'button').click();
  161 |     await expect(messages).toHaveCount(0);
  162 |     return { after, traffic };
  163 |   };
  164 |   try {
  165 |     control({});
  166 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  167 |     await page.locator('input[autocomplete="username"]').fill(username);
  168 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  169 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  170 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  171 |     await sync('initial-snapshot', zh, zh.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'), false, 200);
  172 |     for (const [locale, language, text, errors] of [
  173 |       ['zh-CN', '简体中文', zh, errorsZh],
  174 |       ['en', 'English', en, errorsEn],
  175 |       ['vi', 'Tiếng Việt', vi, errorsVi],
  176 |     ] as const) {
  177 |       const languageControl = page.locator(prototype ? '.header .lang' : '.prototype-language .ant-select');
  178 |       if (!(await languageControl.innerText()).includes(language)) {
  179 |         const changed = page.waitForResponse(
  180 |           (r) => r.request().method() !== 'GET' && new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  181 |         );
  182 |         await languageControl.click();
  183 |         await page.getByRole('option', { name: language, exact: true }).click();
  184 |         await changed;
  185 |       }
  186 |       for (const example of cases) {
  187 |         control({
  188 |           ...(example.laterPage ? { orderCount: 2000 } : {}),
  189 |           wire: { [example.operation]: { body: example.body, status: example.httpStatus ?? 200, encodeTwice: example.encodeTwice, fromStart: example.laterPage ? 2000 : 0 } },
  190 |         });
  191 |         const rejected = example.error !== undefined;
  192 |         const expected = rejected
  193 |           ? (example.operation === 'login' ? errors.K3_LOGIN_FAILED : errors.K3_QUERY_FAILED).replace('{{message}}', example.error!)
  194 |           : example.removes
  195 |             ? text.orders.syncedRemoved.replace('{{bill}}', 'CMP-MO-001')
  196 |             : text.orders.syncedOne.replace('{{bill}}', 'CMP-MO-001').replace('{{rows}}', '2');
  197 |         const observed = await sync(locale + '-' + example.label, text, expected, example.operation !== 'orders', rejected ? 502 : 200);
  198 |         if (example.laterPage)
  199 |           expect.soft(observed.traffic.filter((r: { operation: string }) => r.operation === 'orders').map((r: { start: number }) => r.start)).toEqual([0, 2000]);
  200 |         if (example.removes) {
  201 |           expect.soft(observed.after.orders.filter((r: { bill_no: string }) => r.bill_no === 'CMP-MO-001')).toHaveLength(0);
  202 |           control({});
  203 |           await sync(locale + '-' + example.label + '-restore', text, text.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'), false, 200);
  204 |         }
  205 |       }
  206 |     }
  207 |     control({});
  208 |     const facts = helper('snapshot').audits.filter((r: Record<string, unknown>) => r.operator === username && ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)) && ['FAIL', 'FAILED'].includes(String(r.result)));
  209 |     writeFileSync(info.outputPath('failure-audits.json'), JSON.stringify(facts, null, 2));
> 210 |     expect.soft(facts).toHaveLength(39);
      |                        ^ Error: expect(received).toHaveLength(expected)
  211 |     await page.getByRole('menuitem', { name: vi.nav.audit, exact: true }).click();
  212 |     await page.getByPlaceholder(vi.audit.operator, { exact: true }).fill(username);
  213 |     await page.getByRole('button', { name: vi.common.search, exact: true }).click();
  214 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  215 |     await page.screenshot({ path: info.outputPath('audit-actual-actions.png'), animations: 'disabled' });
  216 |     expect(pageErrors).toEqual([]);
  217 |   } finally {
  218 |     control({});
  219 |     writeFileSync(info.outputPath('observations.json'), JSON.stringify({ observations, pageErrors }, null, 2));
  220 |   }
  221 | });
  222 | 
```