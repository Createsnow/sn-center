package com.adtek.sn.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对称加密（与原系统 app/core/secretbox.py 逐字节兼容）：MES 凭证、MES 接口账户密钥、金蝶应用密钥加密保存。
 *
 * <p>AES-256-GCM，密钥 = SHA-256("sn-mes-credential:" + SN_SECRET)，密文 = "v1:" + base64url(nonce(12) +
 * ct + tag)。更换 SN_SECRET 后旧密文无法解密。
 */
@Component
public class SecretBox {
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SnSettings settings;

    public SecretBox(SnSettings settings) {
        this.settings = settings;
    }

    private SecretKeySpec key() {
        try {
            byte[] k =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("sn-mes-credential:" + settings.secretKey())
                                            .getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, all, 0, nonce.length);
            System.arraycopy(ct, 0, all, nonce.length, ct.length);
            return PREFIX + Base64.getUrlEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 解密；为空返回空串，解不开（密钥换过）返回 null。 */
    public String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) {
            return "";
        }
        if (!sealed.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(sealed.substring(PREFIX.length()));
            if (raw.length < 13) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 0, 12));
            return new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
