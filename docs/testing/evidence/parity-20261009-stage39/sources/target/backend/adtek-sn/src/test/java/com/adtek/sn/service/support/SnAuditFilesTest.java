package com.adtek.sn.service.support;

import static org.assertj.core.api.Assertions.*;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

class SnAuditFilesTest {
    @Test
    void versionFiveDistinguishesPlainTextFromJsonWithoutMutatingFixedRows() {
        var fixed = new ArrayList<String>(Collections.nCopies(25, null));
        fixed.set(3, "[\"SN_HQ\"]");
        fixed.set(5, "RULE_RENAME");
        fixed.set(6, "SUCCESS");
        fixed.set(23, "R 名 称 -> 新 名称");
        fixed.set(24, "TEXT");
        var cells = SnAuditFiles.prototypeCellsV5(fixed, Set.of("SN_HQ"));
        assertThat(cells).hasSize(24);
        assertThat(cells.get(23)).isEqualTo("R 名 称 -> 新 名称");
        assertThat(cells.get(14)).isNull();
        assertThat(fixed).hasSize(25);
        fixed.set(23, "{ \"v\": 9007199254740993 }");
        fixed.set(24, "JSON");
        assertThat(SnAuditFiles.prototypeCellsV5(fixed, Set.of()).get(23))
                .isEqualTo("{\"v\":9007199254740993}");
    }

    @Test
    void authenticationMappingStartsAtVersionSixAndDoesNotRewriteVersionFive() {
        var fixed = new ArrayList<String>(Collections.nCopies(25, null));
        fixed.set(3, "[]");
        fixed.set(5, "auth:PASSWORD_CHANGE");
        fixed.set(6, "FAILED");
        fixed.set(21, "原密码不正确");
        fixed.set(23, "POST /api/auth/password");
        fixed.set(24, "TEXT");
        assertThat(SnAuditFiles.prototypeCellsV5(fixed, Set.of()).get(5))
                .isEqualTo("auth:PASSWORD_CHANGE");
        assertThat(SnAuditFiles.prototypeCellsV5(fixed, Set.of()).get(3)).isEqualTo("[]");
        var current = SnAuditFiles.prototypeCellsV6(fixed, Set.of());
        assertThat(current.get(5)).isEqualTo("PASSWORD_CHANGE");
        assertThat(current.get(3)).isEmpty();
        assertThat(current.get(21)).isEqualTo("原密码不正确");
        assertThat(current.get(14)).isNull();
        assertThat(fixed.get(5)).isEqualTo("auth:PASSWORD_CHANGE");
    }

    @Test
    void previousRuntimeWorkbooksKeepTheirActualSha256() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        for (int version : List.of(1, 2, 3, 4, 5)) {
            try (var input =
                    getClass().getResourceAsStream("/audit-file-goldens/v" + version + ".json")) {
                var golden = json.readTree(input);
                PrintFiles.Rows rows =
                        consumer -> {
                            for (var row : golden.path("cells")) {
                                var cells = Arrays.asList(json.treeToValue(row, String[].class));
                                if (version == 5) {
                                    cells =
                                            SnAuditFiles.prototypeCellsV5(
                                                    cells,
                                                    new HashSet<>(
                                                            Arrays.asList(
                                                                    json.readValue(
                                                                            cells.get(3),
                                                                            String[].class))));
                                } else if (version >= 2)
                                    cells =
                                            SnAuditFiles.prototypeCells(
                                                    cells,
                                                    new HashSet<>(
                                                            Arrays.asList(
                                                                    json.readValue(
                                                                            cells.get(3),
                                                                            String[].class))));
                                consumer.accept(cells);
                            }
                        };
                var output = new ByteArrayOutputStream();
                var createdAt = Instant.parse(golden.path("createdAt").asString());
                if (version == 1)
                    PrintFiles.xlsx(output, createdAt, SnAuditFiles.HEADER, 1000000, rows);
                else if (version >= 3)
                    PrintFiles.auditXlsx(output, createdAt, 1000000, version, rows);
                else
                    PrintFiles.typedXlsx(
                            output,
                            createdAt,
                            SnAuditFiles.PROTOTYPE_HEADER,
                            "audit",
                            Set.of(14),
                            1000000,
                            rows);
                assertThat(
                                HexFormat.of()
                                        .formatHex(
                                                java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest(output.toByteArray())))
                        .isEqualTo(golden.path("sha256").asString());
            }
        }
    }

    @Test
    void versionFourMatchesSourceLineBreaksWithoutChangingOldFilesOrCsv() throws Exception {
        var cells = new ArrayList<String>(Collections.nCopies(24, null));
        cells.set(2, "=1+2");
        cells.set(20, "a\r\nb\rc\nd\tend");
        for (int version : List.of(3, 4)) {
            var output = new ByteArrayOutputStream();
            PrintFiles.auditXlsx(
                    output,
                    Instant.parse("2026-10-08T00:00:00Z"),
                    1000000,
                    version,
                    row -> row.accept(cells));
            try (var wb = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
                var row = wb.getSheetAt(0).getRow(1);
                assertThat(row.getCell(20).getStringCellValue())
                        .isEqualTo(version == 3 ? cells.get(20) : "a\n\nb\nc\nd\tend");
                assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(2).getStringCellValue()).isEqualTo("=1+2");
            }
        }
        var csv = new ByteArrayOutputStream();
        SnAuditFiles.prototypeCsvRow(csv, cells);
        assertThat(csv.toString(StandardCharsets.UTF_8)).contains("a\r\nb\rc\nd\tend");
        assertThat(cells.get(20)).isEqualTo("a\r\nb\rc\nd\tend");
    }

    @Test
    void versionThreeKeepsEmptyInlineCellAndDoesNotRewriteLiteralMarkup() throws Exception {
        var cells = new ArrayList<String>(Collections.nCopies(24, null));
        cells.set(20, "");
        cells.set(23, "<is><t></t></is>");
        var output = new ByteArrayOutputStream();
        PrintFiles.auditXlsx(
                output, Instant.parse("2026-10-08T00:00:00Z"), 1000000, row -> row.accept(cells));
        try (var zip =
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            String sheet = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml"))
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(sheet)
                    .contains(
                            "<c r=\"U2\" t=\"inlineStr\"></c>",
                            "&lt;is&gt;&lt;t&gt;&lt;/t&gt;&lt;/is&gt;");
        }
    }

    @Test
    void jsonWhitespaceNeverChangesQuotedTextEscapesOrExactNumbers() {
        String value =
                "{ \"n\": 900719925474099312345, \"x\": 0.1234567890123456789,"
                        + " \"s\": \"a b\\\" c\\\\ d\" }";
        assertThat(SnAuditFiles.compactJson(value))
                .isEqualTo(
                        "{\"n\":900719925474099312345,\"x\":0.1234567890123456789,\"s\":\"a b\\\" c\\\\ d\"}");
    }

    @Test
    void typedAuditWorkbookRetainsQuantityBoundaryTextFormulaNullAndEmpty() throws Exception {
        var values = new ArrayList<String>(Collections.nCopies(24, null));
        values.set(2, "=2+2");
        values.set(14, "999999999999999");
        values.set(20, "");
        values.set(23, "{\"n\":9007199254740993}");
        PrintFiles.Rows rows =
                consumer -> {
                    consumer.accept(values);
                    var large = new ArrayList<>(values);
                    large.set(14, "9999999999999999");
                    consumer.accept(large);
                };
        byte[] first = workbook(rows), second = workbook(rows);
        assertThat(first).isEqualTo(second);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(first))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("audit");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 24);
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("=2+2");
            assertThat(sheet.getRow(1).getCell(14).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(2).getCell(14).getStringCellValue())
                    .isEqualTo("9999999999999999");
            assertThat(sheet.getRow(1).getCell(19)).isNull();
            assertThat(sheet.getRow(1).getCell(20).getStringCellValue()).isEmpty();
        }
    }

    private byte[] workbook(PrintFiles.Rows rows) throws Exception {
        var out = new ByteArrayOutputStream();
        PrintFiles.typedXlsx(
                out,
                Instant.parse("2026-10-08T00:00:00Z"),
                SnAuditFiles.PROTOTYPE_HEADER,
                "audit",
                Set.of(14),
                1000000,
                rows);
        return out.toByteArray();
    }

    @Test
    void csvUsesPrototypeQuotingAndProtectsAllTextButNotNumericQuantity() throws Exception {
        var values = new ArrayList<String>(Collections.nCopies(24, null));
        values.set(0, "2026-10-08 08:00:00");
        values.set(7, "@factory");
        values.set(14, "-1");
        values.set(20, "a,\"b\"\r\nc");
        var output = new ByteArrayOutputStream();
        SnAuditFiles.prototypeCsvRow(output, values);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        "2026-10-08 08:00:00,,,,,,,'@factory,,,,,,,-1,,,,,,\"a,\"\"b\"\"\r\nc\",,,\r\n");
    }

    @Test
    void formatTwoMapsHistoricalRoleActionAndResultWithoutMutatingSnapshot() {
        var values = new ArrayList<String>(Collections.nCopies(24, null));
        values.set(3, "[\"SN_HQ\"]");
        values.set(4, "MES");
        values.set(5, "sn:acquire:take:failed");
        values.set(6, "FAILED");
        values.set(23, "{ \"p\": 1 }");
        var projected = SnAuditFiles.prototypeCells(values, Set.of("SN_HQ"));
        assertThat(projected.subList(3, 7)).containsExactly("admin", "API", "SN_ACQUIRE", "FAIL");
        assertThat(projected.get(23)).isEqualTo("{\"p\":1}");
        assertThat(values.get(5)).isEqualTo("sn:acquire:take:failed");
        assertThat(SnAuditFiles.prototypeAction("unknown:action")).isEqualTo("unknown:action");
    }
}
