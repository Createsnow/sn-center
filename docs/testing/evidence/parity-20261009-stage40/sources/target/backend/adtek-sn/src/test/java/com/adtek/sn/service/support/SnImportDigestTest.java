package com.adtek.sn.service.support;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 新分块摘要必须兼容已接受的v1操作，特殊字符与块边界都不能改变请求身份。 */
class SnImportDigestTest {
    @Test
    void chunkBoundariesPreserveLegacyJsonDigest() throws Exception {
        var json = new ObjectMapper();
        for (int size : List.of(0, 1, 999, 1000, 1001, 3001)) {
            var sns =
                    java.util.stream.IntStream.range(0, size).mapToObj(i -> "中😀\\\"" + i).toList();
            var expected =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    json.writeValueAsString(sns)
                                                            .getBytes(StandardCharsets.UTF_8)));
            assertThat(SnImportDigest.of(json, sns)).isEqualTo(expected);
        }
    }
}
