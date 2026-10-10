package com.adtek.sn.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.adtek.sn.domain.PrintEntity;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

class PrintFilesTest {
    @Test
    void prototypeCsvMatchesMinimalQuotingAndFormulaProtection() throws Exception {
        var output = new ByteArrayOutputStream();
        PrintFiles.prototypeCsvRow(
                output,
                Arrays.asList(
                        "1",
                        "=SN",
                        "001",
                        "9999999999999999",
                        "@PI",
                        "a,b",
                        "\"客户\"",
                        null,
                        "PRINTED",
                        "B1",
                        "line\r\n2"));
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        "1,'=SN,001,9999999999999999,'@PI,\"a,b\",\"\"\"客户\"\"\",,PRINTED,B1,\"line\r\n2\"\r\n");
    }

    @Test
    void prototypeWorkbookKeepsNumericBoundaryAndDeterministicRecovery() throws Exception {
        var print =
                new PrintEntity(
                        "id",
                        1,
                        "F",
                        "工厂",
                        "PI",
                        2,
                        1,
                        "operation",
                        "csv",
                        Instant.parse("2026-10-08T00:00:00Z"),
                        "P202610080800000001",
                        "admin",
                        2);
        PrintFiles.Rows rows =
                write -> {
                    write.accept(
                            List.of(
                                    "1",
                                    "=SN1",
                                    "0001",
                                    "999999999999999",
                                    "PI",
                                    "M",
                                    "C",
                                    "F",
                                    "PRINTED",
                                    "B1",
                                    "MO1"));
                    write.accept(
                            List.of(
                                    "2",
                                    "=SN2",
                                    "0002",
                                    "1000000000000000",
                                    "PI",
                                    "M",
                                    "C",
                                    "F",
                                    "PRINTED",
                                    "B1",
                                    "MO1"));
                };
        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        PrintFiles.prototypeXlsx(first, print, 1000000, rows);
        PrintFiles.prototypeXlsx(second, print, 1000000, rows);
        assertThat(second.toByteArray()).isEqualTo(first.toByteArray());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(first.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo(print.printNo());
            assertThat(sheet.getRow(1).getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue())
                    .isEqualTo(999999999999999D);
            assertThat(sheet.getRow(2).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue())
                    .isEqualTo("1000000000000000");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("0001");
        }
    }
}
