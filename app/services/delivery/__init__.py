"""交付批次（5.2.5 第 2–8 条、F-11 推送、F-12 拉取、F-19 只读）。

统一批次：交付批次（含转出批次）只由本系统建立——推送任务建批，或 MES 取批时范围内没有未确认批次而建批，
或转移、确认物料对应、告警重推、作废重建时当场建批；一批只有一种批次类型（batchType）；
批次号、所含 SN 及其挂靠和 rev 建立后都不再改变（请求体 body_gz 写定，推送与拉取送达的内容相同）。
批次以 MES 回执确认（推送时在应答中，拉取时另行提交）；回执须与批次逐枚对应，不符时批次记为失败；
先到的回执生效，后到的只存档。

认领：推送建批、MES 拉取建批，都从「待推送、未入批、未暂停」的号池用（SN 导出只读，不认领）
SELECT … FOR UPDATE SKIP LOCKED 认领，短事务内完成，互不阻塞、互不重复。

推送任务：同组织 + 同一 PI 一个任务（转出批次用 #XO）；批次顺序执行，一批成功再发下一批；
没有可用回执时用原批次号、原内容自动重试（默认 1、5、15 分钟），期间后续批次等待；重试用尽或被拒绝即停，
「继续推送」先重推未确认或失败的批次（含拉取建立的），再认领后续的号。同组织同时运行的任务数不超过推送并发上限，
同一 PI 同一时刻只运行一个任务；租约过期（实例宕机）的任务由其他实例接手。

按职责分为：
- batches   建批与认领、批次报文
- receipts  回执核对、确认与失败
- push      推送任务与调度线程
- manual    批次人工确认、作废，交付告警处理
- pull      MES 拉取与只读核对接口
- views     页面查询（批次列表、详情、明细、导出）
- self_check 每日交付自检
本模块只汇总各部分对外使用的函数与常量。
"""

from app.services.delivery.batches import (
    TRANSFER_OUT,
    XO_KEY,
    batch_json,
    build_delivery,
    build_delivery_now,
    build_transfer_out,
    claim,
    must_batch,
)
from app.services.delivery.manual import frozen_by_transfer_out, handle_alert, list_alerts, manual_confirm, void
from app.services.delivery.pull import next_batch, read_batch_items, read_delivered, submit_receipt
from app.services.delivery.push import (
    kick_bill,
    remaining_pushes,
    resume,
    run_pending,
    start_scheduler,
    start_task,
    task_state,
)
from app.services.delivery.receipts import apply_receipt
from app.services.delivery.self_check import run as self_check
from app.services.delivery.views import EXPORT_HEADERS, batch_detail, batch_items_page, export_rows, list_batches

__all__ = [
    "EXPORT_HEADERS",
    "TRANSFER_OUT",
    "XO_KEY",
    "apply_receipt",
    "batch_detail",
    "batch_items_page",
    "batch_json",
    "build_delivery",
    "build_delivery_now",
    "build_transfer_out",
    "claim",
    "export_rows",
    "frozen_by_transfer_out",
    "handle_alert",
    "kick_bill",
    "list_alerts",
    "list_batches",
    "manual_confirm",
    "must_batch",
    "next_batch",
    "read_batch_items",
    "read_delivered",
    "remaining_pushes",
    "resume",
    "self_check",
    "run_pending",
    "start_scheduler",
    "start_task",
    "submit_receipt",
    "task_state",
    "void",
]
