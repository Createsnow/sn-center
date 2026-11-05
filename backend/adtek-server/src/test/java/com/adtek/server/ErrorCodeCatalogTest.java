package com.adtek.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.adtek.common.core.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 错误码目录门禁：所有模块中实现 {@link ErrorCode} 的枚举共同构成目录，规则对新增模块自动生效。
 *
 * <p>code 与 key 全局唯一；每个枚举只占一个号段且号段不与其他枚举重复；HTTP 状态为 4xx/5xx；每个 key 在 zh-CN、en、vi 三语字典中都有非空翻译。
 */
class ErrorCodeCatalogTest {
    private static final Pattern KEY =
            Pattern.compile("error\\.[a-z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*");
    private static final Path LOCALES = Path.of("..", "..", "frontend", "src", "i18n", "locales");
    private static final List<String> LANGUAGES = List.of("zh-CN", "en", "vi");

    private static final Map<String, List<ErrorCode>> catalog = new HashMap<>();

    @BeforeAll
    static void collectErrorCodeEnums() throws ClassNotFoundException {
        for (JavaClass type :
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.adtek")) {
            if (type.isEnum() && type.isAssignableTo(ErrorCode.class)) {
                Object[] constants = Class.forName(type.getName()).getEnumConstants();
                List<ErrorCode> codes = new ArrayList<>();
                for (Object constant : constants) {
                    codes.add((ErrorCode) constant);
                }
                catalog.put(type.getName(), codes);
            }
        }
    }

    @Test
    void catalogIncludesTheCommonCodes() {
        assertThat(catalog).containsKey("com.adtek.common.constant.CommonErrorCode");
    }

    @Test
    void codesAndKeysAreUniqueAndEachEnumOwnsOneSegment() {
        Set<Integer> codes = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Map<Integer, String> segmentOwners = new HashMap<>();
        catalog.forEach(
                (owner, entries) -> {
                    for (ErrorCode entry : entries) {
                        String label = owner + "." + entry;
                        assertThat(entry.code()).as(label).isBetween(1_000_000_000, 2_099_999_999);
                        assertThat(codes.add(entry.code())).as(label + " code is unique").isTrue();
                        assertThat(entry.key()).as(label).matches(KEY);
                        assertThat(keys.add(entry.key())).as(label + " key is unique").isTrue();
                        assertThat(entry.httpStatus()).as(label).isBetween(400, 599);
                        String previous =
                                segmentOwners.putIfAbsent(ErrorCode.segmentOf(entry.code()), owner);
                        assertThat(previous == null || previous.equals(owner))
                                .as(label + " segment belongs to " + previous)
                                .isTrue();
                    }
                    long segments =
                            entries.stream()
                                    .map(entry -> ErrorCode.segmentOf(entry.code()))
                                    .distinct()
                                    .count();
                    assertThat(segments).as(owner + " uses exactly one segment").isEqualTo(1);
                });
    }

    @Test
    void everyKeyIsTranslatedInAllLanguages() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        for (String language : LANGUAGES) {
            Path file = LOCALES.resolve(language + ".json");
            assertThat(file).as("frontend locale dictionary").exists();
            Map<String, String> dictionary = new HashMap<>();
            flatten("", mapper.readValue(Files.readString(file), Map.class), dictionary);
            catalog.values().stream()
                    .flatMap(List::stream)
                    .forEach(
                            entry ->
                                    assertThat(dictionary.get(entry.key()))
                                            .as(language + " translation of " + entry.key())
                                            .isNotBlank());
        }
    }

    private static void flatten(String prefix, Map<?, ?> node, Map<String, String> into) {
        node.forEach(
                (name, value) -> {
                    String key = prefix.isEmpty() ? name.toString() : prefix + "." + name;
                    if (value instanceof Map<?, ?> child) {
                        flatten(key, child, into);
                    } else {
                        into.put(key, String.valueOf(value));
                    }
                });
    }
}
