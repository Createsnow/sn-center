package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.domain.PrintEntity;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** 固定快照的两种产物；旧版本保留摘要语义，新打印使用原型的列和安全单元格类型。 */
public final class PrintFiles {
    private PrintFiles() {}

    public static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final List<String> PROTOTYPE_HEADER =
            List.of(
                    "序号", "完整SN", "进制流水", "十进制流水", "PI", "物料编码", "客户编码", "工厂", "状态", "领取批次",
                    "来源订单");

    public static List<String> prototypeCells(long ordinal, AcquireSnapshot s, String batchNo) {
        return prototypeCells(ordinal, s, batchNo, "PRINTED");
    }

    /** 当前打印文件可包含申请中状态；固定产物继续使用原打印状态。 */
    public static List<String> prototypeCells(
            long ordinal, AcquireSnapshot s, String batchNo, String status) {
        return Arrays.asList(
                Long.toString(ordinal),
                s.sn(),
                s.seqText(),
                s.seqDec(),
                s.pi(),
                s.material(),
                s.customer(),
                s.factoryCode(),
                status,
                batchNo,
                s.billNo());
    }

    /** 与原型 CSV 一致：仅必要时引用；文本公式加单引号，数值列不改写。 */
    public static void prototypeCsvRow(OutputStream output, List<String> cells) throws IOException {
        var line = new StringBuilder();
        for (int col = 0; col < cells.size(); col++) {
            if (col > 0) line.append(',');
            String value = cells.get(col) == null ? "" : cells.get(col);
            if (!numberColumn(col, value)
                    && !value.isEmpty()
                    && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            if (value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0)
                line.append('"').append(value.replace("\"", "\"\"")).append('"');
            else line.append(value);
        }
        output.write(line.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean numberColumn(int col, String value) {
        return (col == 0 || col == 3) && value != null && value.matches("[0-9]+");
    }

    public static final List<String> HEADER =
            List.of(
                    "ordinal",
                    "sn",
                    "seq_text",
                    "seq_dec",
                    "seq_pi",
                    "pi",
                    "factory_code",
                    "customer",
                    "material",
                    "source_bill_no",
                    "source_system",
                    "source_account",
                    "source_bill_id",
                    "source_entry_id");

    public static List<String> cells(long ordinal, AcquireSnapshot s) {
        return Arrays.asList(
                Long.toString(ordinal),
                s.sn(),
                s.seqText(),
                s.seqDec(),
                s.seqPi(),
                s.pi(),
                s.factoryCode(),
                s.customer(),
                s.material(),
                s.billNo(),
                s.sourceSystem(),
                s.sourceAccount(),
                s.sourceBillId(),
                s.sourceEntryId());
    }

    public static void csvRow(OutputStream output, List<String> cells) throws IOException {
        var line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) line.append(',');
            String value = cells.get(index);
            line.append('"').append(value == null ? "" : value.replace("\"", "\"\"")).append('"');
        }
        line.append("\r\n");
        output.write(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    public interface Rows {
        void each(Consumer<List<String>> row);
    }

    public static void xlsx(OutputStream output, PrintEntity print, long byteLimit, Rows rows)
            throws Exception {
        xlsx(output, print.createdAt(), HEADER, byteLimit, rows);
    }

    public static void xlsx(
            OutputStream output,
            java.time.Instant createdAt,
            List<String> header,
            long byteLimit,
            Rows rows)
            throws Exception {
        workbook(output, createdAt, header, byteLimit, rows, "SN", false);
    }

    public static void prototypeXlsx(
            OutputStream output, PrintEntity print, long byteLimit, Rows rows) throws Exception {
        workbook(
                output,
                print.createdAt(),
                PROTOTYPE_HEADER,
                byteLimit,
                rows,
                print.printNo() == null ? "SN" : print.printNo(),
                true);
    }

    /** 查询文件沿用相同十一列和单元格类型，但工作表标题固定为 SN。 */
    public static void prototypeXlsx(
            OutputStream output, java.time.Instant createdAt, long byteLimit, Rows rows)
            throws Exception {
        workbook(output, createdAt, PROTOTYPE_HEADER, byteLimit, rows, "SN", true);
    }

    /** 审计文件有独立列与数值类型，不能套用SN文件的第0/3列规则。 */
    public static void typedXlsx(
            OutputStream output,
            java.time.Instant createdAt,
            List<String> header,
            String title,
            Set<Integer> numericColumns,
            long byteLimit,
            Rows rows)
            throws Exception {
        workbook(output, createdAt, header, byteLimit, rows, title, true, numericColumns);
    }

    /** 审计格式3区分缺失值与原型空文本；格式1/2继续走既有写法以保持文件摘要。 */
    public static void auditXlsx(
            OutputStream output, java.time.Instant createdAt, long byteLimit, Rows rows)
            throws Exception {
        workbook(
                output,
                createdAt,
                SnAuditFiles.PROTOTYPE_HEADER,
                byteLimit,
                rows,
                "audit",
                true,
                Set.of(14),
                true);
    }

    /** 格式4对应本机原型XML读回的换行：CR成为LF；旧格式3的原始CR及摘要保持。 */
    public static void auditXlsx(
            OutputStream output,
            java.time.Instant createdAt,
            long byteLimit,
            int formatVersion,
            Rows rows)
            throws Exception {
        if (formatVersion < 3 || formatVersion > 6)
            throw new IllegalArgumentException("Unsupported typed audit format");
        Rows projected =
                formatVersion == 3
                        ? rows
                        : consumer ->
                                rows.each(
                                        cells ->
                                                consumer.accept(
                                                        cells.stream()
                                                                .map(
                                                                        v ->
                                                                                v == null
                                                                                        ? null
                                                                                        : v.replace(
                                                                                                '\r',
                                                                                                '\n'))
                                                                .toList()));
        auditXlsx(output, createdAt, byteLimit, projected);
    }

    private static void workbook(
            OutputStream output,
            java.time.Instant createdAt,
            List<String> header,
            long byteLimit,
            Rows rows,
            String title,
            boolean prototype)
            throws Exception {
        workbook(output, createdAt, header, byteLimit, rows, title, prototype, null);
    }

    private static void workbook(
            OutputStream output,
            java.time.Instant createdAt,
            List<String> header,
            long byteLimit,
            Rows rows,
            String title,
            boolean prototype,
            Set<Integer> numericColumns)
            throws Exception {
        workbook(
                output,
                createdAt,
                header,
                byteLimit,
                rows,
                title,
                prototype,
                numericColumns,
                false);
    }

    private static void workbook(
            OutputStream output,
            java.time.Instant createdAt,
            List<String> header,
            long byteLimit,
            Rows rows,
            String title,
            boolean prototype,
            Set<Integer> numericColumns,
            boolean prototypeEmptyText)
            throws Exception {
        var bytes = new ByteArrayOutputStream();
        // POI只保留200行，压缩临时sheet，close在失败/成功都释放临时资源；最终ZIP也有独立字节准入。
        try (var workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            workbook.setZip64Mode(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);
            var sheet = workbook.createSheet(title);
            if (prototype) sheet.createFreezePane(0, 1);
            var text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            int[] ordinal = {0};
            Consumer<List<String>> write =
                    values -> {
                        if (ordinal[0] >= 1048576)
                            throw new BusinessException(
                                    com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);
                        var row = sheet.createRow(ordinal[0]++);
                        for (int col = 0; col < values.size(); col++) {
                            String value = values.get(col);
                            if (prototype && numericColumns != null) {
                                if (value == null) continue;
                                if (row.getRowNum() > 0
                                        && numericColumns.contains(col)
                                        && value.matches("-?[0-9]+")
                                        && new java.math.BigInteger(value)
                                                        .abs()
                                                        .compareTo(
                                                                java.math.BigInteger.valueOf(
                                                                        999999999999999L))
                                                <= 0)
                                    row.createCell(col).setCellValue(Long.parseLong(value));
                                else row.createCell(col).setCellValue(value);
                                continue;
                            }
                            if (prototype) {
                                if (value == null || value.isEmpty()) continue;
                                // 原型只将不超过 15 位的整数写为数值；SN/进制流水一直是文本。
                                if (row.getRowNum() > 0
                                        && numberColumn(col, value)
                                        && Long.parseLong(value) <= 999999999999999L) {
                                    row.createCell(col).setCellValue(Long.parseLong(value));
                                } else row.createCell(col).setCellValue(value);
                                continue;
                            }
                            var cell =
                                    row.createCell(
                                            col, org.apache.poi.ss.usermodel.CellType.STRING);
                            cell.setCellStyle(text);
                            cell.setCellValue(values.get(col) == null ? "" : values.get(col));
                        }
                    };
            write.accept(header);
            rows.each(write);
            workbook.write(
                    new FilterOutputStream(bytes) {
                        long written;

                        @Override
                        public void write(int b) throws IOException {
                            check(1);
                            out.write(b);
                        }

                        @Override
                        public void write(byte[] b, int o, int n) throws IOException {
                            check(n);
                            out.write(b, o, n);
                        }

                        private void check(int n) {
                            if ((written += n) > byteLimit)
                                throw new BusinessException(
                                        com.adtek.framework.constant.ErrorCodeConstants
                                                .RESOURCE_LIMIT);
                        }
                    });
        }
        // ZIP时间和核心属性标准化，恢复同一快照必须产生相同摘要，不能让打包时间改变历史文件。
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
                var zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = input.getNextEntry()) != null) {
                var fixed = new ZipEntry(entry.getName());
                fixed.setTime(0);
                zip.putNextEntry(fixed);
                if ("docProps/core.xml".equals(entry.getName())) {
                    String time = createdAt.toString();
                    String xml =
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><cp:coreProperties"
                                    + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                                    + " xmlns:dcterms=\"http://purl.org/dc/terms/\""
                                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:creator>Adtek</dc:creator><dcterms:created"
                                    + " xsi:type=\"dcterms:W3CDTF\">"
                                    + time
                                    + "</dcterms:created><dcterms:modified"
                                    + " xsi:type=\"dcterms:W3CDTF\">"
                                    + time
                                    + "</dcterms:modified></cp:coreProperties>";
                    zip.write(xml.getBytes(StandardCharsets.UTF_8));
                } else if (prototypeEmptyText
                        && entry.getName().startsWith("xl/worksheets/sheet")) {
                    copyEmptyTextCells(input, zip);
                } else {
                    int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
                input.closeEntry();
            }
            zip.finish();
        }
    }

    /** SXSSF空文本带空is节点，原型只写inlineStr单元格；流式移除该固定片段，不解析或改写文本值。 */
    private static void copyEmptyTextCells(InputStream input, OutputStream output)
            throws IOException {
        byte[] tail = "is><t></t></is>".getBytes(StandardCharsets.US_ASCII);
        byte[] lookahead = new byte[tail.length];
        var source = new PushbackInputStream(new BufferedInputStream(input, 65536), tail.length);
        var target = new BufferedOutputStream(output, 65536);
        int value;
        while ((value = source.read()) != -1) {
            if (value == '<') {
                int count = source.readNBytes(lookahead, 0, tail.length);
                if (count == tail.length && Arrays.equals(lookahead, tail)) continue;
                source.unread(lookahead, 0, count);
            }
            target.write(value);
        }
        target.flush();
    }
}
