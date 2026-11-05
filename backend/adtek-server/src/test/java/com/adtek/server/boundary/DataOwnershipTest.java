package com.adtek.server.boundary;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表归属门禁：每个模块只能创建、修改和访问以本模块名为前缀的表（{@code system_*}、{@code order_*}），跨模块数据只能经 framework 端口取得。
 *
 * <ul>
 *   <li>实体 {@code @TableName} 必须显式声明且属于本模块前缀；
 *   <li>迁移放在本模块 jar 的 {@code db/migration/{module}/}，版本为 12 位时间戳 {@code V{yyyyMMddHHmm}__说明.sql}，
 *       全局唯一，只触及本模块的表；server 只放与表无关的平台级迁移（如 V1 基线）；
 *   <li>Mapper XML 放在 {@code mapper/{module}/}，namespace 是本模块的 Mapper 接口，只触及本模块的表，禁止 {@code ${}}
 *       拼接；
 *   <li>业务模块（含 system）不得绕过 Mapper：禁止 JDBC、SqlRunner、字符串条件构造器与带原始 SQL 的条件方法。
 * </ul>
 *
 * <p>归属按类与资源的物理来源判定（{@link ModuleOrigin}），规则对新增模块自动生效。
 */
class DataOwnershipTest {
    private static final Pattern SERVER_MIGRATION = Pattern.compile("V(\\d+)__[^/]+\\.sql");
    private static final Pattern MODULE_MIGRATION = Pattern.compile("V(\\d{12})__[^/]+\\.sql");
    private static final Pattern NAMESPACE =
            Pattern.compile("<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> FOUNDATION = Set.of("common", "framework", "server");
    private static final Set<String> RAW_SQL_METHODS =
            Set.of(
                    "apply",
                    "last",
                    "first",
                    "comment",
                    "exists",
                    "notExists",
                    "inSql",
                    "notInSql",
                    "eqSql",
                    "gtSql",
                    "geSql",
                    "ltSql",
                    "leSql",
                    "having",
                    "setSql");

    private static final PathMatchingResourcePatternResolver resources =
            new PathMatchingResourcePatternResolver();
    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.adtek");
    }

    @Test
    void entitiesMapToTablesOwnedByTheirModule() throws ClassNotFoundException {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!type.getPackageName().matches("com\\.adtek\\..+\\.domain(\\..+)?")
                    || type.getSimpleName().equals("package-info")
                    || !type.isTopLevelClass()) {
                continue;
            }
            Class<?> entity = Class.forName(type.getName());
            String module = ModuleOrigin.of(entity).orElse("?");
            TableName table = entity.getAnnotation(TableName.class);
            if (table == null || table.value().isBlank()) {
                violations.add(type.getName() + " has no @TableName");
            } else if (!table.schema().isEmpty() || !owned(module, table.value())) {
                violations.add(
                        type.getName()
                                + " maps to a table not owned by adtek-"
                                + module
                                + ": "
                                + table.value());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void migrationsLiveInTheirModuleAndTouchOnlyOwnedTables() throws IOException {
        List<String> violations = new ArrayList<>();
        Map<String, String> versions = new HashMap<>();
        for (Resource resource : resources.getResources("classpath*:db/migration/**/*")) {
            if (!resource.isReadable()) {
                continue;
            }
            Optional<String> origin = ModuleOrigin.of(resource.getURL());
            if (origin.isEmpty()) {
                continue;
            }
            String module = origin.get();
            String path = relativePath(resource, "db/migration/");
            boolean server = module.equals("server");
            Matcher name =
                    (server ? SERVER_MIGRATION : MODULE_MIGRATION)
                            .matcher(server ? path : path.replaceFirst("^" + module + "/", ""));
            String label = "adtek-" + module + ": db/migration/" + path;
            if (!(server ? !path.contains("/") : path.startsWith(module + "/"))) {
                violations.add(
                        label
                                + " is misplaced; expected db/migration/"
                                + (server ? "" : module + "/")
                                + "V...__description.sql");
                continue;
            }
            if (!name.matches()) {
                violations.add(
                        label
                                + (server
                                        ? " must be named V{n}__description.sql"
                                        : " must be named V{yyyyMMddHHmm}__description.sql"));
                continue;
            }
            String previous = versions.putIfAbsent(name.group(1), label);
            if (previous != null) {
                violations.add(label + " duplicates the version of " + previous);
            }
            for (String table : SqlTableScanner.tables(read(resource))) {
                if (server || !owned(module, table)) {
                    violations.add(label + " touches a table not owned by this module: " + table);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void mapperXmlLivesInItsModuleAndTouchesOnlyOwnedTables() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Resource resource : resources.getResources("classpath*:mapper/**/*.xml")) {
            Optional<String> origin = ModuleOrigin.of(resource.getURL());
            if (origin.isEmpty()) {
                continue;
            }
            String module = origin.get();
            String path = relativePath(resource, "mapper/");
            String label = "adtek-" + module + ": mapper/" + path;
            String xml = read(resource);
            String file = path.substring(path.lastIndexOf('/') + 1);
            String expectedNamespace =
                    ModuleOrigin.javaPackage(module)
                            + ".mapper."
                            + file.substring(0, file.length() - ".xml".length());
            Matcher namespace = NAMESPACE.matcher(xml);
            if (!path.equals(module + "/" + file)) {
                violations.add(
                        label + " is misplaced; expected mapper/" + module + "/XxxMapper.xml");
            }
            if (!namespace.find() || !namespace.group(1).equals(expectedNamespace)) {
                violations.add(label + " must have namespace " + expectedNamespace);
            }
            if (xml.contains("${")) {
                violations.add(
                        label
                                + " uses ${} string substitution; use #{} parameters or a whitelisted <choose>");
            }
            for (String table : SqlTableScanner.tablesInMapperXml(xml)) {
                if (!owned(module, table)) {
                    violations.add(label + " touches a table not owned by this module: " + table);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void businessModulesAccessTheDatabaseOnlyThroughTheirMappers() {
        noClasses()
                .that(businessModule())
                .should()
                .dependOnClassesThat()
                .haveNameMatching(
                        "java\\.sql\\.(Connection|Statement|PreparedStatement|CallableStatement)"
                                + "|javax\\.sql\\.DataSource"
                                + "|org\\.springframework\\.jdbc\\..*"
                                + "|org\\.apache\\.ibatis\\.session\\.SqlSession.*"
                                + "|com\\.baomidou\\.mybatisplus\\..*SqlRunner"
                                + "|com\\.baomidou\\.mybatisplus\\.core\\.conditions\\.query\\.QueryWrapper"
                                + "|com\\.baomidou\\.mybatisplus\\.core\\.conditions\\.update\\.UpdateWrapper")
                .because(
                        "business modules reach their own tables only through their mappers; use Lambda wrappers")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void businessModulesDoNotPassRawSqlToConditionWrappers() {
        noClasses()
                .that(businessModule())
                .should()
                .callMethodWhere(
                        DescribedPredicate.describe(
                                "a MyBatis-Plus condition method taking raw SQL",
                                DataOwnershipTest::isRawSqlCondition))
                .because("raw SQL fragments can reference any table and bypass table ownership")
                .allowEmptyShould(true)
                .check(classes);
    }

    static DescribedPredicate<JavaClass> businessModule() {
        return DescribedPredicate.describe(
                "reside in a business module (including system)",
                type -> !FOUNDATION.contains(moduleOfPackage(type.getPackageName())));
    }

    private static boolean isRawSqlCondition(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        return RAW_SQL_METHODS.contains(call.getName())
                && (owner.isAssignableTo("com.baomidou.mybatisplus.core.conditions.Wrapper")
                        || owner.isAssignableTo(
                                "com.baomidou.mybatisplus.core.conditions.interfaces.Join")
                        || owner.isAssignableTo(
                                "com.baomidou.mybatisplus.core.conditions.interfaces.Func")
                        || owner.isAssignableTo(
                                "com.baomidou.mybatisplus.core.conditions.update.Update"));
    }

    static boolean owned(String module, String table) {
        String prefix = ModuleOrigin.tablePrefix(module);
        return TABLE_NAME.matcher(table).matches()
                && table.startsWith(prefix)
                && table.length() > prefix.length();
    }

    static String moduleOfPackage(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length > 2 ? parts[2] : "";
    }

    private static String relativePath(Resource resource, String root) throws IOException {
        String url = resource.getURL().toString();
        return url.substring(url.lastIndexOf(root) + root.length());
    }

    private static String read(Resource resource) throws IOException {
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
