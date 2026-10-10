package com.adtek.sn.service.support;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** 公开失败参数不泄漏附加字段，并且容量在序列化前后保持 long 的精确字符串。 */
class InitializationErrorsTest {
    @Test
    void filtersFieldsAndRequiresMatchingCodeWithCompleteParameters() {
        assertThat(
                        InitializationErrors.safe(
                                1004000013,
                                Map.of(
                                        "reason",
                                        "PI_INIT_LOCKED",
                                        "pi",
                                        "PI-1",
                                        "secret",
                                        "not-public")))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("reason", "PI_INIT_LOCKED", "pi", "PI-1"));
        assertThat(
                        InitializationErrors.safe(
                                1004000002, Map.of("reason", "PI_INIT_LOCKED", "pi", "PI-1")))
                .isEmpty();
        assertThat(InitializationErrors.safe(1004000013, Map.of("reason", "PI_INIT_LOCKED")))
                .isEmpty();
        assertThat(
                        InitializationErrors.safe(
                                1000000001, Map.of("reason", "PI_IMPORT_SN_INVALID", "line", "0")))
                .isEmpty();
        assertThat(
                        InitializationErrors.safe(
                                1004000013, Map.of("reason", "PI_INIT_LOCKED", "pi", "PI\nSECRET")))
                .isEmpty();
    }

    @Test
    void persistedCapacityRemainsExactAndNumericJsonIsRejected() {
        var json = new ObjectMapper();
        var params = InitializationErrors.start(Long.MAX_VALUE).getParams();
        assertThat(params.get("max")).isEqualTo("9223372036854775807");
        assertThat(InitializationErrors.read(json, 1004000002, json.writeValueAsString(params)))
                .isEqualTo(params);
        assertThat(
                        InitializationErrors.read(
                                json,
                                1004000002,
                                "{\"reason\":\"GEN_START_INVALID\",\"max\":9007199254740993}"))
                .isEmpty();
    }

    @Test
    void sequenceConflictAllowsZeroAndPreservesLongPrecisionButRejectsMissingPi() {
        for (String seq : java.util.List.of("0", "9223372036854775807")) {
            var error =
                    InitializationErrors.error("PI_IMPORT_SEQ_DUP", Map.of("seq", seq, "pi", "P"));
            assertThat(error.getParams()).containsEntry("seq", seq).containsEntry("pi", "P");
        }
        for (String seq : java.util.List.of("-1", "00", "1e3", ""))
            assertThat(
                            InitializationErrors.safe(
                                    1004000015,
                                    Map.of("reason", "PI_IMPORT_SEQ_DUP", "seq", seq, "pi", "P")))
                    .isEmpty();
        assertThat(
                        InitializationErrors.safe(
                                1004000015, Map.of("reason", "PI_IMPORT_DUP_EXISTING", "sn", "S")))
                .isEmpty();
    }
}
