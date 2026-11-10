package com.adtek.sn.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class CamelTest {
    @Test
    void convertsKeysRecursivelyButNotValues() {
        Object out =
                Camel.convert(Map.of("bill_no", "A_B", "items", List.of(Map.of("seq_pi_no", "x"))));
        assertThat(out)
                .isEqualTo(Map.of("billNo", "A_B", "items", List.of(Map.of("seqPiNo", "x"))));
        assertThat(Camel.key("_x")).isEqualTo("_x");
    }
}
