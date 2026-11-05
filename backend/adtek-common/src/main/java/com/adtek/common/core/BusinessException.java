package com.adtek.common.core;

import java.util.Map;
import java.util.Objects;

/**
 * 可预期的业务失败：由服务用例抛出，framework 统一转换为 {@link Result} 与错误码对应的 HTTP 状态。
 *
 * <p>属于正常控制流，不采集堆栈；参数只放可公开给当前用户的值。非预期异常不要包装成本类型，交给全局处理返回内部错误。
 */
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final Map<String, String> args;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public BusinessException(ErrorCode errorCode, Map<String, String> args) {
        super(Objects.requireNonNull(errorCode, "errorCode").key(), null, false, false);
        this.errorCode = errorCode;
        this.args = Map.copyOf(args);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, String> args() {
        return args;
    }
}
