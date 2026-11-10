package com.adtek.sn.vo;

/**
 * MES 接口应答：HTTP 状态、JSON 应答体（UTF-8，与原系统逐字段一致）、Retry-After 秒数（限流时）。
 *
 * @param status HTTP 状态
 * @param body JSON 应答体
 * @param retryAfter Retry-After 秒数（没有为 null）
 */
public record MesReplyVO(int status, byte[] body, Integer retryAfter) {}
