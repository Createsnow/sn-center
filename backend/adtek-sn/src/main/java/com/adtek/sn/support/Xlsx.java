package com.adtek.sn.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 导出文件一律为 XLSX（原系统 files.py）：流式写出（SXSSF），冻结首行；数字写数值，超长整数写文本；文本单元格按字符串原样写出，不加防公式前缀。 */
public final class Xlsx {
    public static final String TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long BIG = 999_999_999_999_999L;

    private Xlsx() {}

    /** 逐行写出的工作表。 */
    public static final class Writer implements AutoCloseable {
        private final SXSSFWorkbook wb = new SXSSFWorkbook(500);
        private final SXSSFSheet sheet;
        private int next;

        public Writer(String title, List<String> headers) {
            wb.setCompressTempFiles(true);
            String t = title == null || title.isEmpty() ? "Sheet1" : title;
            sheet = wb.createSheet(sanitize(t.length() > 31 ? t.substring(0, 31) : t));
            sheet.createFreezePane(0, 1);
            add(List.copyOf(headers.stream().map(h -> (Object) h).toList()), true);
        }

        public void add(List<?> values) {
            add(values, false);
        }

        private void add(List<?> values, boolean header) {
            Row row = sheet.createRow(next++);
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                if (v == null) {
                    continue;
                }
                Cell c = row.createCell(i);
                if (!header && isNum(v)) {
                    Number n = (Number) v;
                    if (!(v instanceof Double || v instanceof Float)
                            && Math.abs(n.longValue()) > BIG) {
                        c.setCellValue(v.toString());
                    } else {
                        c.setCellValue(n.doubleValue());
                    }
                } else {
                    c.setCellValue(v.toString());
                }
            }
        }

        public void write(OutputStream out) {
            try {
                wb.write(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                wb.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 与原系统一致：整数、浮点写数值；DECIMAL 等其余类型写文本。 */
    private static boolean isNum(Object v) {
        return v instanceof Long
                || v instanceof Integer
                || v instanceof Short
                || v instanceof Byte
                || v instanceof Double
                || v instanceof Float;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/?*\\[\\]:]", "_");
    }

    /** 下载应答：流式写出 xlsx。 */
    public static org.springframework.http.ResponseEntity<
                    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            response(com.adtek.sn.vo.ExportFileVO file) {
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        disposition(file.baseName()))
                .contentType(org.springframework.http.MediaType.parseMediaType(TYPE))
                .body(out -> file.writer().accept(out));
    }

    /** Content-Disposition（RFC 5987 编码文件名，中文不乱码）。 */
    public static String disposition(String baseName) {
        String name = baseName + ".xlsx";
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
