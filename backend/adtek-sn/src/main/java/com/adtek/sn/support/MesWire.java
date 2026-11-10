package com.adtek.sn.support;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * MES 报文（原系统 mes_client.canonical / gzip_bytes）：紧凑 JSON（UTF-8，不转义非 ASCII，字段按插入顺序），gzip 固定 mtime =
 * 0、 压缩级别 6，同一内容压缩结果逐字节相同。
 */
public final class MesWire {
    private MesWire() {}

    public static final String TOKEN = "TOKEN";
    public static final String HMAC = "HMAC";
    public static final String NONE = "NONE";
    public static final List<String> AUTH_TYPES = List.of(TOKEN, HMAC, NONE);
    public static final String CODE_OK = "OK";

    /** 不重试的失败中，MES 确实没有处理的（可作废）。 */
    public static final List<String> VOIDABLE_CODES =
            List.of("AUTH", "ADDRESS", "TOO_LARGE", "BAD_REQUEST", "REJECTED");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static boolean needsCredential(String authType) {
        return !NONE.equals(authType);
    }

    public static byte[] canonical(Object obj) {
        return JSON.writeValueAsString(obj).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(byte[] json) {
        return JSON.readValue(json, LinkedHashMap.class);
    }

    public static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        // 头：ID1 ID2 CM=8 FLG=0 MTIME=0 XFL=0 OS=255（与原系统 gzip.compress(mtime=0) 相同）
        out.writeBytes(new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff});
        Deflater deflater = new Deflater(6, true);
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, deflater, 8192)) {
            d.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(raw);
        long c = crc.getValue();
        int n = raw.length;
        out.writeBytes(
                new byte[] {
                    (byte) c, (byte) (c >> 8), (byte) (c >> 16), (byte) (c >> 24),
                    (byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)
                });
        return out.toByteArray();
    }

    public static byte[] gunzip(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 连接测试批次：批次类型 PING、不含号。 */
    public static byte[] pingBody(String org, String batchNo) {
        List<Object> items = new ArrayList<>();
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("schemaVersion", "1.0");
        obj.put("batchNo", batchNo);
        obj.put("batchType", "PING");
        obj.put("orgCode", org);
        obj.put("toOrgCode", null);
        obj.put("count", 0);
        obj.put("createdAt", Util.mesNow());
        obj.put("itemsDigest", "sha256:" + sha256Hex(canonical(items)));
        obj.put("items", items);
        return gzip(canonical(obj));
    }
}
