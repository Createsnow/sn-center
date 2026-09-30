package com.adtek.server;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** 在所有模块的真实生产字节码上检查边界；空业务层不宣称已完成样板验收。 */
class ArchitectureTest {
    @Test
    void foundationDoesNotDependOnBusinessModules() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.adtek");
        noClasses().that().resideInAnyPackage("com.adtek.common..", "com.adtek.framework..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.adtek.system..", "com.adtek.sn..", "com.adtek.integration..", "com.adtek.server..")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.adtek.sn..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.adtek.system..", "com.adtek.integration..", "com.adtek.server..")
                .check(classes);
    }

    @Test
    void entitiesDoNotUseUpperLayers() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.adtek");
        noClasses().that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..", "..mapper..")
                .allowEmptyShould(true).check(classes);
    }

    @Test
    void modulesAreAcyclicAndDoNotReachIntoOtherBusinessMappers() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.adtek");
        slices().matching("com.adtek.(*)..").should().beFreeOfCycles().check(classes);
        for (String module : new String[]{"system", "sn"}) {
            noClasses().that().resideOutsideOfPackage("com.adtek." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.adtek." + module + ".mapper..", "com.adtek." + module + ".service.impl..")
                    .check(classes);
        }
    }
}
