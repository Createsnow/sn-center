package com.adtek.sn.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MES 报文：紧凑 JSON、固定 mtime 的 gzip 与原系统（Python gzip.compress(level=6, mtime=0)）逐字节相同。 */
class MesWireTest {
    @Test
    void canonicalJsonAndGzipMatchTheOriginalSystem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sn", "HX000001100A");
        item.put("rev", 1);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        obj.put("b", "中");
        obj.put("items", List.of(item, item, item));
        byte[] raw = MesWire.canonical(obj);
        assertThat(new String(raw, StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"a\":1,\"b\":\"中\",\"items\":[{\"sn\":\"HX000001100A\",\"rev\":1},"
                                + "{\"sn\":\"HX000001100A\",\"rev\":1},{\"sn\":\"HX000001100A\",\"rev\":1}]}");
        assertThat(HexFormat.of().formatHex(MesWire.gzip(raw)))
                .isEqualTo(
                        "1f8b08000000000000ffab564a54b232d4514a52b2527ab263ad928e5266496a6eb1925574b552711e50d0"
                                + "23c200040c0d0d0c1c81b245a96540f5b53a14c8c6d60200f2554da875000000");
        assertThat(MesWire.gunzip(MesWire.gzip(raw))).isEqualTo(raw);
    }

    @Test
    void pingBatchHasNoItems() {
        Map<String, Object> body = MesWire.parse(MesWire.gunzip(MesWire.pingBody("110", "PING-1")));
        assertThat(body.keySet())
                .containsExactly(
                        "schemaVersion",
                        "batchNo",
                        "batchType",
                        "orgCode",
                        "toOrgCode",
                        "count",
                        "createdAt",
                        "itemsDigest",
                        "items");
        assertThat(body.get("batchType")).isEqualTo("PING");
        assertThat(body.get("items")).isEqualTo(List.of());
        assertThat((String) body.get("itemsDigest")).startsWith("sha256:").hasSize(71);
    }
}
