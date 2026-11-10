package com.adtek.sn.support;

import com.adtek.sn.constant.SnErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 口令哈希（与原系统 app/core/security.py 兼容，已有账户无需重置）：PBKDF2-HMAC-SHA256、120000 轮、32 字节，存 salt(hex 32) +
 * dk(hex 64)。
 */
public final class Passwords {
    private Passwords() {}

    private static final int ROUNDS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern POLICY = Pattern.compile("^[A-Za-z0-9!@#$&*-]{4,64}$");

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt) + HexFormat.of().formatHex(pbkdf2(password, salt));
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || stored.length() < 34 || password == null) {
            return false;
        }
        byte[] salt;
        try {
            salt = HexFormat.of().parseHex(stored.substring(0, 32));
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected = stored.substring(32).getBytes(StandardCharsets.US_ASCII);
        byte[] actual =
                HexFormat.of()
                        .formatHex(pbkdf2(password, salt))
                        .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** 4–64 位，须同时含字母和数字；只能用字母、数字和 ! @ # $ & * -。 */
    public static void checkPolicy(String password) {
        if (password == null
                || !POLICY.matcher(password).matches()
                || password.chars().noneMatch(ch -> ch < 128 && Character.isLetter(ch))
                || password.chars().noneMatch(Character::isDigit)) {
            throw Biz.err(SnErrorCode.USER_PASSWORD_WEAK);
        }
    }

    /** 口令版本 = 改密时间按 UTC 解释的秒数。 */
    public static long version(LocalDateTime pwdChangedAt) {
        return pwdChangedAt == null ? 0 : pwdChangedAt.toEpochSecond(ZoneOffset.UTC);
    }
}
