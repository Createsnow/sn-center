package com.adtek.framework.task;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 任务保护边界，绑定adtek.tasks；默认值由application.yml统一提供，非法范围启动失败。
 *
 * @param enabled 是否启用后台自动领取；关闭不删除已登记任务
 * @param maxAttempts 一个执行周期允许的领取次数，包含首次领取
 * @param leaseSeconds 每次领取租约秒数，不取代执行事务的行锁
 * @param budgetSeconds 一个执行周期的整体秒数预算，文件恢复可开启新周期
 * @param retrySeconds 可恢复失败距下次可领取时刻的等待秒数
 * @param perUserLimit 每用户活动任务上限
 * @param queueLimit 全局活动任务上限，由提交协调行串行检查
 * @param exportRows 固定导出快照最大行数，超限拒绝而不截断
 * @param fileBytes 单个产物最大字节数，写入和下载都受限
 * @param fileRoot 持久文件根目录，多实例须指向同一受控共享目录
 */
@Validated
@ConfigurationProperties("adtek.tasks")
public record TaskSettings(
        boolean enabled,
        @Min(1) @Max(10) int maxAttempts,
        @Min(5) @Max(300) int leaseSeconds,
        @Min(30) @Max(3600) int budgetSeconds,
        @Min(1) @Max(30) int retrySeconds,
        @Min(1) @Max(10) int perUserLimit,
        @Min(1) @Max(10000) int queueLimit,
        @Min(1) @Max(10000) int exportRows,
        @Min(1024) @Max(16777216) long fileBytes,
        @NotBlank String fileRoot) {}
