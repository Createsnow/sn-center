package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.ErrorCode;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

/** 所有已装配模块的错误常量必须在各自号段且全局不重号；发现未知模块时明确失败。 */
class ErrorCodeCatalogTest {
    @Test
    void exportedErrorCodesAreUniqueAndWithinTheirModuleRange() throws Exception {
        var ranges =
                Map.of(
                        "common",
                        1_000_000_000,
                        "system",
                        1_002_000_000,
                        "framework",
                        1_003_000_000,
                        "sn",
                        1_004_000_000,
                        "integration",
                        1_005_000_000);
        var codes = new HashSet<Integer>();
        var classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.adtek");
        for (var candidate : classes) {
            if (!candidate.getSimpleName().equals("ErrorCodeConstants")) {
                continue;
            }
            String module = candidate.getPackageName().split("\\.")[2];
            assertThat(ranges).containsKey(module);
            int start = ranges.get(module);
            for (var field : Class.forName(candidate.getName()).getFields()) {
                if (!field.getType().equals(ErrorCode.class)) {
                    continue;
                }
                var error = (ErrorCode) field.get(null);
                assertThat(error.code())
                        .as(candidate.getName() + "." + field.getName())
                        .isBetween(start, start + 999_999);
                assertThat(codes.add(error.code()))
                        .as("Duplicate error code %s", error.code())
                        .isTrue();
                assertThat(error.httpStatus()).isBetween(400, 599);
                assertThat(error.message()).startsWith(module + ".");
            }
        }
        assertThat(codes).hasSizeGreaterThan(0);
    }
}
