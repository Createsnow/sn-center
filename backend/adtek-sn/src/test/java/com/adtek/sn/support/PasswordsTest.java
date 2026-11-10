package com.adtek.sn.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 口令散列与原系统兼容（PBKDF2-SHA256，120000 轮，salt hex 32 + dk hex 64）。 */
class PasswordsTest {
    /** 原系统 hash_password 在 salt = 00..0f 时的结果（Python hashlib 计算）。 */
    static final String PYTHON_HASH =
            "000102030405060708090a0b0c0d0e0f3c1470bf41c711666787c0c986bede36f2821fa14cb7199d6fdfd5aea5dd97c8";

    @Test
    void verifiesHashesWrittenByTheOriginalSystem() {
        assertThat(Passwords.verify("Admin@123", PYTHON_HASH)).isTrue();
        assertThat(Passwords.verify("Admin@124", PYTHON_HASH)).isFalse();
        assertThat(Passwords.verify(null, PYTHON_HASH)).isFalse();
        assertThat(Passwords.verify("Admin@123", "short")).isFalse();
    }

    @Test
    void newHashesRoundTrip() {
        String h = Passwords.hash("Abc123");
        assertThat(h).hasSize(96);
        assertThat(Passwords.verify("Abc123", h)).isTrue();
    }
}
