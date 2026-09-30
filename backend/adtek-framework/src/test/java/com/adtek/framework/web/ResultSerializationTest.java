package com.adtek.framework.web;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.PageResult;
import com.adtek.common.core.Result;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

class ResultSerializationTest {
    record SerialVO(String id, String serial, String sequence) {}

    @Test
    void errorCodeIsNumericWhileIdsSequencesAndCountsStayExactText() {
        var mapper = JsonMapper.builder().build();
        var value =
                Result.success(
                        new PageResult<>(
                                List.of(
                                        new SerialVO(
                                                "9223372036854775807",
                                                "0000123",
                                                "9007199254740993")),
                                "9007199254740993",
                                1,
                                20),
                        "trace");
        var tree = mapper.readTree(mapper.writeValueAsString(value));
        assertThat(tree.get("code").isInt()).isTrue();
        assertThat(tree.at("/data/total").isString()).isTrue();
        assertThat(tree.at("/data/items/0/id").asString()).isEqualTo("9223372036854775807");
        assertThat(tree.at("/data/items/0/serial").asString()).isEqualTo("0000123");
        assertThat(tree.at("/data/items/0/sequence").asString()).isEqualTo("9007199254740993");
    }
}
