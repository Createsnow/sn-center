package com.adtek.sn.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SN 编码（与原系统 tests/test_unit.py 同一组期望值）。 */
class SnCodecTest {
    static final SnCodec.Spec HX =
            new SnCodec.Spec("HX", "A", 10, 6, "0123456789", true, 0, "", "", false);
    static final SnCodec.Spec NOLEN =
            new SnCodec.Spec("AVM", "X", 10, 5, "0123456789", false, 0, "", "", false);
    static final SnCodec.Spec B32 =
            new SnCodec.Spec(
                    "Q", "", 32, 4, SnCodec.DEFAULT_CHARSETS.get(32), false, 0, "", "", false);
    static final SnCodec.Spec FULL =
            new SnCodec.Spec("光-SN", "#尾", 10, 4, "0123456789", true, 5, "-", "M", true);
    static final SnCodec.Spec UNIT_ONLY =
            new SnCodec.Spec("U", "", 10, 3, "0123456789", false, 0, "", "", true);

    @Test
    void snIsPrefixSerialLengthUnitSuffix() {
        assertThat(SnCodec.formatSn(1, HX, "100", null)).isEqualTo("HX000001100A");
        assertThat(SnCodec.formatSn(1, HX, "10.5", null)).isEqualTo("HX00000110.5A");
        assertThat(SnCodec.formatSn(1, NOLEN, "100", null)).isEqualTo("AVM00001X");
        assertThat(SnCodec.formatSn(1, NOLEN, "100", "PCS")).isEqualTo("AVM00001X");
        assertThat(SnCodec.formatSn(7, FULL, "51.5", "条")).isEqualTo("光-SN0007-051.5M条#尾");
        assertThat(SnCodec.formatSn(7, FULL, "123456", "条")).isEqualTo("光-SN0007-123456M条#尾");
        assertThat(SnCodec.formatSn(7, UNIT_ONLY, "100", "PCS")).isEqualTo("U007PCS");
        assertThat(SnCodec.encodeSeq(31, B32)).isEqualTo("000Z");
        assertThat(SnCodec.encodeSeq(32, B32)).isEqualTo("0010");
    }

    @Test
    void default32CharsetDropsConfusableLetters() {
        assertThat(SnCodec.DEFAULT_CHARSETS.get(32)).isEqualTo("0123456789ABCDEFGHJKMNPQRSTVWXYZ");
    }

    @Test
    void rejectsOverflowInsteadOfWidening() {
        assertThatThrownBy(() -> SnCodec.formatSn(100_000, NOLEN, null, null))
                .isInstanceOf(SnCodec.SeqOverflow.class);
        assertThat(SnCodec.maxSeq(NOLEN)).isEqualTo(99_999);
        assertThat(SnCodec.maxSeq(B32)).isEqualTo(32L * 32 * 32 * 32 - 1);
    }

    @Test
    void parseRecoversSerial() {
        assertThat(SnCodec.parse("HX000001100A", HX)).isEqualTo(1L);
        assertThat(SnCodec.parse("HX00004210.5A", HX)).isEqualTo(42L);
        assertThat(SnCodec.parse("HX0000420A", HX)).isEqualTo(42L);
        assertThat(SnCodec.parse("HX000042000A", HX)).isEqualTo(42L);
        assertThat(SnCodec.parse("AVM00042X", NOLEN)).isEqualTo(42L);
        assertThat(SnCodec.parse("AVM0042X", NOLEN)).isNull();
        assertThat(SnCodec.parse("BVM00042X", NOLEN)).isNull();
        assertThat(SnCodec.parse("AVM00042100X", NOLEN)).isNull();
        assertThat(SnCodec.parse("光-SN0007-051.5M条#尾", FULL)).isEqualTo(7L);
        assertThat(SnCodec.parse("光-SN0007051.5M条#尾", FULL)).isNull();
        assertThat(SnCodec.parse("U007PCS", UNIT_ONLY)).isEqualTo(7L);
        for (long s : new long[] {0, 1, 31, 32, 1023, 1_048_575}) {
            assertThat(SnCodec.parse(SnCodec.formatSn(s, B32, null, null), B32)).isEqualTo(s);
        }
    }

    @Test
    void chineseLengthIsDetected() {
        for (String t : new String[] {"五十米", "51米", "５１", "51，5"}) {
            assertThat(SnCodec.hasChinese(t)).as(t).isTrue();
        }
        for (String t : new String[] {"51.5", "000", "L-2", "", null}) {
            assertThat(SnCodec.hasChinese(t)).as(String.valueOf(t)).isFalse();
        }
    }

    @Test
    void lengthIsPlainString() {
        assertThat(SnCodec.cleanLength("0100")).isEqualTo("0100");
        assertThat(SnCodec.cleanLength(" 100 ")).isEqualTo("100");
        assertThat(SnCodec.cleanLength("10.50")).isEqualTo("10.50");
        assertThat(SnCodec.cleanLength("  ")).isNull();
        assertThat(SnCodec.cleanLength(null)).isNull();
    }

    @Test
    void validation() {
        assertThat(
                        SnCodec.validate(
                                new SnCodec.Spec(
                                        "A", "", 8, 4, "01234567", false, 0, "", "", false)))
                .isNotNull();
        assertThat(
                        SnCodec.validate(
                                new SnCodec.Spec(
                                        "", "", 10, 4, "0123456789", false, 0, "", "", false)))
                .isNotNull();
        assertThat(SnCodec.validate(HX)).isNull();
        assertThat(SnCodec.validate(FULL)).isNull();
    }
}
