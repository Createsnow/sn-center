package com.adtek.server;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在全部模块的生产字节码上固化架构规范；规则对新增模块自动生效。
 *
 * <p>模板本身没有业务类，因此每条规则都允许空集合；新增代码一旦违反分层、命名或模块边界即失败。
 */
class ArchitectureTest {
    private static final String[] FOUNDATION = {"com.adtek.common..", "com.adtek.framework.."};
    private static final Pattern MODULE_INTERNAL =
            Pattern.compile("^com\\.adtek\\.(\\w+)\\.(mapper|service\\.impl)(\\..*)?$");

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.adtek");
    }

    @Test
    void foundationDoesNotDependOnUpperModules() {
        noClasses()
                .that()
                .resideInAnyPackage(FOUNDATION)
                .should()
                .dependOnClassesThat(
                        resideInAPackage("com.adtek..").and(not(resideInAnyPackage(FOUNDATION))))
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("com.adtek.common..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.adtek.framework..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyServerDependsOnServer() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.adtek.server..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.adtek.server..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void modulesAreAcyclicAndKeepMappersAndImplementationsPrivate() {
        slices().matching("com.adtek.(*)..")
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
        classes()
                .that()
                .resideInAPackage("com.adtek..")
                .should(notReachIntoOtherModuleInternals())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void layersFollowControllerServiceMapperDirection() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..mapper..", "..domain..", "..service.impl..")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..", "..mapper..")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .that()
                .resideInAnyPackage("..dto..", "..query..", "..vo..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..", "..mapper..", "..domain..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void mapperInterfacesKeepSqlAndResultMappingsInXml() {
        noClasses()
                .that()
                .resideInAPackage("..mapper..")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("org\\.apache\\.ibatis\\.annotations\\.(?!Mapper$|Param$).*")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void classNamesMatchTheirLayer() {
        requireSuffix("..controller..", "Controller");
        requireSuffix("..mapper..", "Mapper");
        requireSuffix("..domain..", "Entity");
        requireSuffix("..dto..", "DTO");
        requireSuffix("..query..", "Query");
        requireSuffix("..vo..", "VO");
        requireSuffix("..convert..", "Convert");
    }

    private static void requireSuffix(String layer, String suffix) {
        classes()
                .that()
                .resideInAPackage(layer)
                .and()
                .areTopLevelClasses()
                .and()
                .doNotHaveSimpleName("package-info")
                .should()
                .haveSimpleNameEndingWith(suffix)
                .allowEmptyShould(true)
                .check(classes);
    }

    /** 任一模块的 mapper 与 service.impl 只允许本模块访问；跨模块通过 service 接口或 framework 端口。 */
    private static ArchCondition<JavaClass> notReachIntoOtherModuleInternals() {
        return new ArchCondition<>("not depend on mapper or service.impl of another module") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Matcher target =
                            MODULE_INTERNAL.matcher(dependency.getTargetClass().getPackageName());
                    if (target.matches() && !target.group(1).equals(originModule)) {
                        events.add(
                                SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static String moduleOf(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length > 2 ? parts[2] : "";
    }
}
