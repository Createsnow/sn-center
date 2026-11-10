package com.adtek.sn.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adtek.sn.support.MesException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/** MES gzip 请求体解压：正常报文照常解压；超过上限、不是合法 gzip、被截断的都拒绝（与原系统 test_mes_request_gunzip_is_capped 相同）。 */
class MesServiceImplTest {
    private static byte[] gz(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    void gunzipIsCapped() throws IOException {
        byte[] body = "{\"pi\":\"PI-1\",\"billNo\":\"MO-1\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(MesServiceImpl.gunzip(gz(body))).isEqualTo(body);
        byte[] full = new byte[MesServiceImpl.MAX_INFLATED];
        Arrays.fill(full, (byte) ' ');
        assertThat(MesServiceImpl.gunzip(gz(full))).hasSize(MesServiceImpl.MAX_INFLATED);
        byte[] bomb = new byte[MesServiceImpl.MAX_INFLATED * 64];
        Arrays.fill(bomb, (byte) '0');
        byte[] bombGz = gz(bomb);
        assertThat(bombGz.length).isLessThan(100 * 1024);
        assertThatThrownBy(() -> MesServiceImpl.gunzip(bombGz))
                .isInstanceOf(MesException.class)
                .hasMessageContaining("超过");
        assertThatThrownBy(() -> MesServiceImpl.gunzip("not gzip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MesException.class)
                .hasMessageContaining("gzip");
        byte[] cut = gz(body);
        assertThatThrownBy(() -> MesServiceImpl.gunzip(Arrays.copyOf(cut, cut.length - 6)))
                .isInstanceOf(MesException.class)
                .hasMessageContaining("gzip");
    }

    @Test
    void multipleMembersAndTrailingZerosAreAccepted() throws IOException {
        byte[] a = gz("{\"a\":".getBytes(StandardCharsets.UTF_8));
        byte[] b = gz("1}".getBytes(StandardCharsets.UTF_8));
        byte[] both = new byte[a.length + b.length + 3];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        assertThat(new String(MesServiceImpl.gunzip(both), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":1}");
    }
}
