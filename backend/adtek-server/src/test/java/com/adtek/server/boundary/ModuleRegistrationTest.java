package com.adtek.server.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模块登记与测试门禁：每个 {@code backend/adtek-*} 模块必须登记到父 POM 并出现在 server 测试类路径上（否则不会被打包，也不会被边界门禁检查）；
 * 有生产代码的模块必须有单元测试 {@code *Test.java}；有 Mapper XML 或迁移的模块必须有集成测试 {@code *IT.java}（SQL 只在真实 MySQL
 * 上得到验证）。
 *
 * <p>CI 每次运行全部模块的测试，因此修改一个模块时，其他模块的测试即为回归保护。
 */
class ModuleRegistrationTest {
    private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern MODULE_DIRECTORY =
            Pattern.compile("adtek-[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern DECLARED_MODULE = Pattern.compile("<module>([^<]+)</module>");

    @Test
    void everyModuleIsRegisteredCheckedAndTested() throws IOException {
        Set<String> declared = declaredModules();
        Map<String, String> onClasspath = ModuleBoundaryTest.moduleRoots();
        List<String> violations = new ArrayList<>();
        List<Path> modules;
        try (Stream<Path> children = Files.list(BACKEND)) {
            modules =
                    children.filter(path -> Files.isRegularFile(path.resolve("pom.xml")))
                            .sorted()
                            .toList();
        }
        assertThat(modules).as("backend modules").isNotEmpty();
        for (Path module : modules) {
            String directory = module.getFileName().toString();
            if (!MODULE_DIRECTORY.matcher(directory).matches()) {
                violations.add(directory + ": module directory must match adtek-{name}");
                continue;
            }
            String name = directory.substring("adtek-".length());
            if (!declared.contains(directory)) {
                violations.add(directory + ": not listed in backend/pom.xml <modules>");
            }
            if (!onClasspath.containsKey(name)) {
                violations.add(
                        directory
                                + ": not on the adtek-server test classpath; add it as a dependency"
                                + " of adtek-server");
            }
            List<Path> production =
                    files(module.resolve("src/main/java"), "\\.java", "package-info.java");
            List<Path> unitTests = files(module.resolve("src/test/java"), "Test\\.java", null);
            List<Path> integrationTests = files(module.resolve("src/test/java"), "IT\\.java", null);
            List<Path> sql = new ArrayList<>();
            sql.addAll(files(module.resolve("src/main/resources/mapper"), "\\.xml", null));
            sql.addAll(files(module.resolve("src/main/resources/db/migration"), "", null));
            if (!production.isEmpty() && unitTests.isEmpty()) {
                violations.add(
                        directory
                                + ": has "
                                + production.size()
                                + " production classes but no *Test.java");
            }
            if (!sql.isEmpty() && integrationTests.isEmpty()) {
                violations.add(
                        directory
                                + ": has Mapper XML or migrations but no *IT.java against real MySQL");
            }
        }
        for (String module : declared) {
            if (!Files.isRegularFile(BACKEND.resolve(module).resolve("pom.xml"))) {
                violations.add(module + ": listed in <modules> but missing");
            }
        }
        assertThat(violations).isEmpty();
    }

    /**
     * 每个 framework 端口实现都有自己的契约测试（{@code {实现类}Test} 或 {@code {实现类}IT}），验证端口 Javadoc 约定的权限、范围与失败语义。
     */
    @Test
    void everyPortImplementationHasAContractTest() throws Exception {
        List<String> violations = new ArrayList<>();
        JavaClasses production =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.adtek");
        for (JavaClass type : production) {
            if (!type.isTopLevelClass() || !ModuleBoundaryTest.implementsPort().test(type)) {
                continue;
            }
            String module =
                    ModuleOrigin.of(Class.forName(type.getName()))
                            .orElseThrow(() -> new IllegalStateException(type.getName()));
            Path tests =
                    BACKEND.resolve("adtek-" + module)
                            .resolve("src/test/java")
                            .resolve(type.getPackageName().replace('.', '/'));
            String name = type.getSimpleName();
            if (!Files.isRegularFile(tests.resolve(name + "Test.java"))
                    && !Files.isRegularFile(tests.resolve(name + "IT.java"))) {
                violations.add(
                        type.getName() + " implements a port but has no " + name + "Test/IT");
            }
        }
        assertThat(violations).isEmpty();
    }

    private static Set<String> declaredModules() throws IOException {
        Matcher matcher = DECLARED_MODULE.matcher(Files.readString(BACKEND.resolve("pom.xml")));
        return matcher.results().map(result -> result.group(1).trim()).collect(Collectors.toSet());
    }

    private static List<Path> files(Path root, String suffixPattern, String excludedName)
            throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Pattern suffix = Pattern.compile(".*" + suffixPattern);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> suffix.matcher(path.getFileName().toString()).matches())
                    .filter(
                            path ->
                                    excludedName == null
                                            || !path.getFileName().toString().equals(excludedName))
                    .toList();
        }
    }
}
