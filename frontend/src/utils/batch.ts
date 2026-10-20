import type { DeliveryBatch } from "@/types/api";

type BatchState = Pick<DeliveryBatch, "result" | "voidable">;

/** 未确认或失败的批次可人工确认（5.2.5 第 7 条）。 */
export const canConfirm = (b: BatchState) => b.result === "UNCONFIRMED" || b.result === "FAILED";

/** 可作废：未确认的批次，或失败原因允许作废（整批拒收、报文过大、认证或地址错误）的失败批次；与后端 delivery.void 一致。 */
export const canVoid = (b: BatchState) => b.result === "UNCONFIRMED" || (b.result === "FAILED" && !!b.voidable);
