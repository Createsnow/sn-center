package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SequenceCodecTest {
    @ParameterizedTest
    @ValueSource(ints = {10, 16, 32, 36})
    void roundTripsExactValuesIncludingJavaScriptUnsafeIntegers(int base) {
        SequenceSpec spec = SequenceSpec.standard("SN", "-A", base, 12);
        long capacity = SequenceCodec.capacity(spec);
        for (long number : new long[]{0, 1, base - 1, base, capacity - 1, capacity}) {
            assertThat(SequenceCodec.parse(SequenceCodec.format(number, spec), spec)).hasValue(number);
        }
        if (capacity > 9_007_199_254_740_993L) {
            long unsafe = 9_007_199_254_740_993L;
            assertThat(SequenceCodec.parse(SequenceCodec.format(unsafe, spec), spec)).hasValue(unsafe);
        }
    }

    @Test
    void preservesLeadingZeroAndLegacyBase32Alphabet() {
        assertThat(SequenceCodec.format(31, SequenceSpec.standard("P", "", 32, 4))).isEqualTo("P000V");
        assertThat(SequenceCodec.format(36, SequenceSpec.standard("", "X", 36, 4))).isEqualTo("0010X");
    }

    @Test
    void capsBase36BeforeLongOverflowAndRejectsUnrepresentableText() {
        SequenceSpec spec = SequenceSpec.standard("", "", 36, 12);
        assertThat(SequenceCodec.capacity(spec)).isEqualTo(4_738_381_338_321_616_895L);
        assertThatThrownBy(() -> SequenceCodec.lastSequence(Long.MAX_VALUE - 1, 10, spec)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SequenceCodec.format(Long.MAX_VALUE, spec)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMismatchWithoutNormalizingTheSerial() {
        SequenceSpec spec = SequenceSpec.standard("P", "S", 16, 3);
        assertThat(SequenceCodec.parse("P00AS", spec)).hasValue(10);
        for (String invalid : new String[]{"P00aS", " P00AS", "P00AS ", "P0AS", "Q00AS", "P00GS"}) {
            assertThat(SequenceCodec.parse(invalid, spec)).isEmpty();
        }
        assertThat(SequenceCodec.parse(null, spec)).isEmpty();
    }

    @Test
    void usesUnicodeCodePointsForCustomAlphabet() {
        SequenceSpec spec = new SequenceSpec("前", "尾", 10, 3, "😀😁😂😃😄😅😆😇😈😉");
        assertThat(SequenceCodec.format(12, spec)).isEqualTo("前😀😁😂尾");
        assertThat(SequenceCodec.parse("前😀😁😂尾", spec)).hasValue(12);
    }

    @Test
    void rejectsInvalidRulesAndFullBatchOverflow() {
        assertThatThrownBy(() -> new SequenceSpec("", "", 10, 2, "0012345678")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SequenceSpec.standard("", "", 2, 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SequenceSpec.standard("A".repeat(64), "", 10, 1)).isInstanceOf(BusinessException.class);
        SequenceSpec spec = SequenceSpec.standard("", "", 10, 2);
        assertThat(SequenceCodec.lastSequence(90, 9, spec)).isEqualTo(99);
        assertThatThrownBy(() -> SequenceCodec.lastSequence(90, 10, spec)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SequenceCodec.lastSequence(0, 0, spec)).isInstanceOf(BusinessException.class);
    }
}
