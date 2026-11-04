package com.adtek.framework.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordEncodingTest {
    // 使用Python标准库PBKDF2生成的公开合成向量，覆盖UTF-8口令；不是任何环境的账号秘密。
    private static final String VECTOR =
            "000102030405060708090a0b0c0d0e0f94bce3685d06306a31519b6aeca32ca5d2200f846369f29f551563c979f4638f";

    @Test
    void matchesExplicitlyTaggedPythonVectorAndRequiresUpgrade() {
        var encoder = new SecurityBeansConfiguration().passwordEncoder();
        assertThat(encoder.matches("Fixture9!中文", "{pbkdf2-sha256-120k}" + VECTOR)).isTrue();
        assertThat(encoder.matches("Fixture9!错误", "{pbkdf2-sha256-120k}" + VECTOR)).isFalse();
        assertThat(encoder.upgradeEncoding("{pbkdf2-sha256-120k}" + VECTOR)).isTrue();
    }

    @Test
    void newPasswordsAlwaysUseCurrentAlgorithm() {
        var encoder = new SecurityBeansConfiguration().passwordEncoder();
        String current = encoder.encode("Fixture9!中文");
        assertThat(current).startsWith("{pbkdf2-sha256-600k}");
        assertThat(encoder.matches("Fixture9!中文", current)).isTrue();
        assertThat(encoder.upgradeEncoding(current)).isFalse();
    }

    @Test
    void neverGuessesUnmarkedOrUnknownHash() {
        var encoder = new SecurityBeansConfiguration().passwordEncoder();
        assertThatThrownBy(() -> encoder.matches("Fixture9!中文", VECTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.matches("Fixture9!中文", "{unknown}" + VECTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
