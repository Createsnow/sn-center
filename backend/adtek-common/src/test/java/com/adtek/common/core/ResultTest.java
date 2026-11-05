package com.adtek.common.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adtek.common.constant.CommonErrorCode;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ResultTest {
    @Test
    void successUsesCodeZeroAndCarriesData() {
        Result<String> result = Result.ok("payload");

        assertThat(result.code()).isZero();
        assertThat(result.key()).isEqualTo(Result.SUCCESS_KEY);
        assertThat(result.args()).isEmpty();
        assertThat(result.data()).isEqualTo("payload");
        assertThat(result.traceId()).isNull();
        assertThat(result.withTraceId("abc").traceId()).isEqualTo("abc");
    }

    @Test
    void failureCopiesErrorCodeAndFreezesArguments() {
        Map<String, String> args = new HashMap<>(Map.of("field", "name"));
        Result<Void> result = Result.fail(CommonErrorCode.CONFLICT, args);
        args.put("field", "changed");

        assertThat(result.code()).isEqualTo(CommonErrorCode.CONFLICT.code());
        assertThat(result.key()).isEqualTo("error.common.conflict");
        assertThat(result.args()).containsExactly(Map.entry("field", "name"));
        assertThat(result.data()).isNull();
        assertThatThrownBy(() -> result.args().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void businessExceptionKeepsCodeWithoutStackTrace() {
        BusinessException exception =
                new BusinessException(CommonErrorCode.NOT_FOUND, Map.of("id", "9007199254740993"));

        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(exception.args()).containsEntry("id", "9007199254740993");
        assertThat(exception.getStackTrace()).isEmpty();
    }

    @Test
    void pageTotalIsAnExactDecimalString() {
        PageResult<String> page = PageResult.of(List.of("a"), 9_007_199_254_740_993L, 1, 20);

        assertThat(page.total()).isEqualTo("9007199254740993");
        assertThatThrownBy(() -> PageResult.of(List.of(), -1, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageResult<>(List.of(), "01", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
