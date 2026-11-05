package com.adtek.server.boundary;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模块边界门禁：一个业务模块的改动不能改变其他模块的运行时行为。
 *
 * <p>全局扩展点（过滤器、拦截器、异常处理、切面、安全链、MyBatis 插件、JSON 配置、Bean 后处理器、全局开关与全局配置资源）只允许 framework 与 server
 * 声明；业务模块（含 system）的类只能放在本模块包内，资源只能放在本模块命名空间下。规则对新增模块自动生效。
 */
class ModuleBoundaryTest {
    /** 只允许 framework 与 server 声明或注册为 Bean 的全局扩展类型。 */
    private static final List<String> GLOBAL_TYPES =
            List.of(
                    "jakarta.servlet.Filter",
                    "jakarta.servlet.ServletContextListener",
                    "org.springframework.web.servlet.HandlerInterceptor",
                    "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
                    "org.springframework.web.servlet.HandlerExceptionResolver",
                    "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice",
                    "org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice",
                    "org.springframework.web.method.support.HandlerMethodArgumentResolver",
                    "org.springframework.web.method.support.HandlerMethodReturnValueHandler",
                    "org.springframework.http.converter.HttpMessageConverter",
                    "org.springframework.core.convert.converter.Converter",
                    "org.springframework.core.convert.converter.GenericConverter",
                    "org.springframework.format.Formatter",
                    "org.springframework.beans.factory.config.BeanPostProcessor",
                    "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
                    "org.springframework.context.ApplicationContextInitializer",
                    "org.springframework.boot.EnvironmentPostProcessor",
                    "org.springframework.security.web.SecurityFilterChain",
                    "org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer",
                    "org.apache.ibatis.plugin.Interceptor",
                    "com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor",
                    "com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor",
                    "com.baomidou.mybatisplus.core.handlers.MetaObjectHandler",
                    "tools.jackson.databind.JacksonModule",
                    "tools.jackson.databind.ObjectMapper",
                    "com.fasterxml.jackson.databind.Module",
                    "com.fasterxml.jackson.databind.ObjectMapper",
                    "org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer",
                    "javax.sql.DataSource",
                    "org.springframework.transaction.TransactionManager",
                    "org.springframework.cache.CacheManager",
                    "org.springframework.data.redis.connection.RedisConnectionFactory",
                    "org.springframework.data.redis.core.RedisTemplate",
                    "java.util.concurrent.Executor");

    /** 只允许 framework 与 server 使用的全局注解（直接或元注解）。 */
    private static final List<String> GLOBAL_ANNOTATIONS =
            List.of(
                    "org.springframework.web.bind.annotation.ControllerAdvice",
                    "org.aspectj.lang.annotation.Aspect",
                    "org.springframework.context.annotation.ComponentScan",
                    "org.mybatis.spring.annotation.MapperScan",
                    "org.springframework.boot.autoconfigure.AutoConfiguration",
                    "org.springframework.boot.jackson.JacksonComponent",
                    "org.springframework.context.annotation.Primary",
                    "org.springframework.scheduling.annotation.EnableAsync",
                    "org.springframework.scheduling.annotation.EnableScheduling",
                    "org.springframework.cache.annotation.EnableCaching",
                    "org.springframework.transaction.annotation.EnableTransactionManagement",
                    "org.springframework.context.annotation.EnableAspectJAutoProxy",
                    "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
                    "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
                    "org.springframework.web.servlet.config.annotation.EnableWebMvc");

    private static final Set<String> FOUNDATION = Set.of("common", "framework", "server");
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
    void businessModulesDoNotDeclareGlobalExtensionPoints() {
        noClasses()
                .that(DataOwnershipTest.businessModule())
                .should(
                        ArchCondition.from(
                                DescribedPredicate.describe(
                                        "be or be annotated as a global extension point",
                                        ModuleBoundaryTest::isGlobalExtension)))
                .because("global infrastructure belongs to framework or server")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void businessModulesDoNotRegisterGlobalBeans() {
        noMethods()
                .that()
                .areDeclaredInClassesThat(DataOwnershipTest.businessModule())
                .should(
                        ArchCondition.from(
                                DescribedPredicate.describe(
                                        "be @Primary or a @Bean of a global extension type",
                                        ModuleBoundaryTest::registersGlobalBean)))
                .because("global infrastructure belongs to framework or server")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void everyModuleKeepsClassesInItsOwnPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        moduleRoots()
                .forEach(
                        (module, root) -> {
                            String own = ModuleOrigin.javaPackage(module).replace('.', '/') + "/";
                            for (String path : files(root)) {
                                if (path.endsWith(".class") && !path.startsWith(own)) {
                                    violations.add("adtek-" + module + " contains " + path);
                                }
                            }
                        });
        assertThat(violations).isEmpty();
    }

    @Test
    void businessModulesKeepResourcesInTheirOwnNamespace() throws IOException {
        List<String> violations = new ArrayList<>();
        moduleRoots()
                .forEach(
                        (module, root) -> {
                            if (FOUNDATION.contains(module)) {
                                return;
                            }
                            String own = ModuleOrigin.javaPackage(module).replace('.', '/') + "/";
                            for (String path : files(root)) {
                                boolean allowed =
                                        path.startsWith(own)
                                                || path.startsWith("mapper/" + module + "/")
                                                || path.startsWith("db/migration/" + module + "/")
                                                || path.equals("META-INF/MANIFEST.MF")
                                                || path.startsWith("META-INF/maven/");
                                if (!allowed) {
                                    violations.add(
                                            "adtek-"
                                                    + module
                                                    + " contains global resource "
                                                    + path
                                                    + " (allowed: "
                                                    + own
                                                    + "**, mapper/"
                                                    + module
                                                    + "/, db/migration/"
                                                    + module
                                                    + "/)");
                                }
                            }
                        });
        assertThat(violations).isEmpty();
    }

    private static boolean isGlobalExtension(JavaClass type) {
        return GLOBAL_TYPES.stream().anyMatch(type::isAssignableTo)
                || GLOBAL_ANNOTATIONS.stream().anyMatch(type::isMetaAnnotatedWith);
    }

    private static boolean registersGlobalBean(JavaMethod method) {
        if (method.isMetaAnnotatedWith("org.springframework.context.annotation.Primary")) {
            return true;
        }
        return method.isAnnotatedWith("org.springframework.context.annotation.Bean")
                && GLOBAL_TYPES.stream().anyMatch(method.getRawReturnType()::isAssignableTo);
    }

    /** 每个 adtek 模块在测试类路径上的根（classes 目录或 jar），按物理来源归属。 */
    static Map<String, String> moduleRoots() throws IOException {
        Map<String, String> roots = new LinkedHashMap<>();
        for (URL url :
                Collections.list(
                        ModuleBoundaryTest.class.getClassLoader().getResources("com/adtek/"))) {
            Optional<String> module = ModuleOrigin.of(url);
            String text = url.toString();
            if (module.isPresent() && !text.contains("/test-classes/")) {
                roots.put(module.get(), text.substring(0, text.length() - "com/adtek/".length()));
            }
        }
        return roots;
    }

    private static List<String> files(String root) {
        List<String> paths = new ArrayList<>();
        try {
            for (Resource resource : resources.getResources(root + "**/*")) {
                String url = resource.getURL().toString();
                if (!url.endsWith("/") && resource.isReadable()) {
                    paths.add(url.substring(root.length()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot list " + root, exception);
        }
        return paths;
    }

    @Test
    void foundationModulesAreOnTheClasspath() throws IOException {
        assertThat(moduleRoots()).containsKeys("common", "framework", "system", "sample", "server");
    }
}
