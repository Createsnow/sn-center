package com.adtek.sn.service.support;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 按块计算原 v1 完整 JSON 数组摘要，不创建大字符串；块边界不改变幂等指纹。 */
public final class SnImportDigest {
    public static final int CHUNK_SIZE = 1000;
    private final ObjectMapper json;
    private final MessageDigest digest;
    private boolean empty = true;

    public SnImportDigest(ObjectMapper json) {
        this.json = json;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        digest.update((byte) '[');
    }

    public void add(List<String> sns) {
        if (sns.isEmpty()) return;
        if (sns.size() > CHUNK_SIZE)
            throw new IllegalArgumentException("Import digest chunk too large");
        if (!empty) digest.update((byte) ',');
        String encoded = json.writeValueAsString(sns);
        digest.update(encoded.substring(1, encoded.length() - 1).getBytes(StandardCharsets.UTF_8));
        empty = false;
    }

    public String finish() {
        digest.update((byte) ']');
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String of(ObjectMapper json, List<String> sns) {
        var value = new SnImportDigest(json);
        for (int i = 0; i < sns.size(); i += CHUNK_SIZE)
            value.add(sns.subList(i, Math.min(sns.size(), i + CHUNK_SIZE)));
        return value.finish();
    }
}
