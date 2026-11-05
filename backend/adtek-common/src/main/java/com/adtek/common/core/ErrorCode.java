package com.adtek.common.core;

/**
 * 错误码契约：整数 {@link #code()}、前端翻译键 {@link #key()} 与对应的 HTTP 状态。
 *
 * <p>code 为十位整数，前四位为模块号段（{@code code / SEGMENT_SIZE}），每个模块独占一个号段：common 为 1000，system 为 1001，业务模块从
 * 1002 起依次分配。key 形如 {@code error.{module}.{name}}，必须在 zh-CN、en、vi 三语字典中存在。号段唯一、 code/key 唯一与三语翻译由
 * server 的 {@code ErrorCodeCatalogTest} 强制。每个模块用一个实现本接口的枚举登记全部错误码。
 */
public interface ErrorCode {
    /** 每个模块号段包含的错误码数量。 */
    int SEGMENT_SIZE = 1_000_000;

    /** 非 0 的十位错误码；0 保留给成功。 */
    int code();

    /** 前端翻译键。 */
    String key();

    /** 返回给客户端的 HTTP 状态（4xx 或 5xx），不用 200 表示失败。 */
    int httpStatus();

    /** 错误码所属的模块号段。 */
    static int segmentOf(int code) {
        return code / SEGMENT_SIZE;
    }
}
