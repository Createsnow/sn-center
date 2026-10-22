# 交付批次共用组件（人工确认 / 作废，5.2.5 第 7 条）：分配页、SN 转移页、SN 查询页共用。
M = {
"batch.confirmTitle": ("人工确认批次 {no}", "Confirm batch {no} manually", "Xác nhận thủ công lô {no}"),
"batch.voidTitle": ("作废批次 {no}", "Void batch {no}", "Hủy lô {no}"),
"batch.confirmTip": ("仅在 MES 方书面确认已全部处理、但回执交不上来时使用：批次记为成功（确认方式「人工」），不再重试；交付批次的号记为已推送。",
  "Use only when MES confirms in writing that everything was processed but cannot send a receipt: the batch becomes succeeded (confirmed manually) and is not retried; SNs of a delivery batch become pushed.",
  "Chỉ dùng khi MES xác nhận bằng văn bản đã xử lý hết nhưng không gửi được biên nhận: lô được ghi là thành công (xác nhận thủ công) và không thử lại; SN của lô giao chuyển thành đã đẩy."),
"batch.voidTip": ("仅用于 MES 没有处理的批次：交付批次的号回到待推送（未入批），重新组批推送；转出批次按原明细重建新的转出批次并自动推送。",
  "Only for batches MES has not processed: SNs of a delivery batch go back to to-push (unbatched) and are re-batched; a transfer-out batch is rebuilt from its items and pushed automatically.",
  "Chỉ cho lô MES chưa xử lý: SN của lô giao trở về chờ đẩy (chưa vào lô) và được gom lô lại; lô chuyển ra được tạo lại từ chi tiết cũ và tự động đẩy."),
"batch.basis": ("依据", "Basis", "Căn cứ"),
"batch.basisPlaceholder": ("如 MES 方书面确认，不超过 500 字", "e.g. written confirmation from MES, at most 500 characters", "Ví dụ xác nhận bằng văn bản của MES, tối đa 500 ký tự"),
}
