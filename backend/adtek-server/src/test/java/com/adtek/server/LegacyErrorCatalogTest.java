package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.ErrorCode;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 历史映射只能引用已发布且语义一致的真实常量；无映射/未知历史不能被映射成成功。 */
class LegacyErrorCatalogTest {
    @Test
    void allExplicitTargetsMatchPublishedConstants() throws Exception {
        try (var input = getClass().getResourceAsStream("/legacy/error-map-v1.json")) {
            assertThat(input).isNotNull();
            var catalog = new ObjectMapper().readTree(input);
            var names = new HashSet<String>();
            int enums = 0;
            for (var entry : catalog.path("entries")) {
                assertThat(names.add(entry.path("legacyCode").asString())).isTrue();
                if (entry.path("sourceKind").asString().equals("ENUM")) enums++;
                if (entry.path("targets").isEmpty())
                    assertThat(entry.path("disposition").asString())
                            .isEqualTo("NO_AUTOMATIC_ERROR_MAPPING");
                for (var target : entry.path("targets")) {
                    String field = target.path("constant").asString();
                    int dot = field.lastIndexOf('.');
                    var error =
                            (ErrorCode)
                                    Class.forName(field.substring(0, dot))
                                            .getField(field.substring(dot + 1))
                                            .get(null);
                    assertThat(target.path("code").asInt()).isEqualTo(error.code());
                    assertThat(target.path("http").asInt()).isEqualTo(error.httpStatus());
                    assertThat(target.path("message").asString()).isEqualTo(error.message());
                    assertThat(target.path("code").asInt()).isPositive();
                    assertThat(target.path("translations").size()).isEqualTo(3);
                }
            }
            assertThat(enums).isEqualTo(97);
            assertThat(names)
                    .hasSize(102)
                    .contains(
                            "app_error",
                            "biz_error",
                            "METHOD_NOT_ALLOWED",
                            "INTERNAL",
                            "<HTTP_404_WITHOUT_CODE>");
            assertThat(catalog.path("unknown").asString()).contains("never map to success");
        }
    }
}
