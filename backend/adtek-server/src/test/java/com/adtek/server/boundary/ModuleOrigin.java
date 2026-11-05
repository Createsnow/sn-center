package com.adtek.server.boundary;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按物理来源（类或资源所在的 classes 目录 / jar）判定所属模块，而不是按包名或目录名，防止把文件放进别的模块名下冒充归属。
 *
 * <p>模块名为 artifactId 去掉 {@code adtek-} 前缀（如 {@code system}、{@code order}、{@code integration-erp}）；
 * 表前缀为模块名把 {@code -} 换成 {@code _} 后加 {@code _}，Java 包为 {@code com.adtek.} 加模块名把 {@code -} 换成 {@code
 * .}。
 */
final class ModuleOrigin {
    private static final Pattern REACTOR =
            Pattern.compile("/adtek-([a-z][a-z0-9-]*)/target/(?:classes/|[^/!]+\\.jar!)");
    private static final Pattern REPOSITORY =
            Pattern.compile("/adtek-([a-z][a-z0-9-]*?)-\\d[^/!]*\\.jar!");

    private ModuleOrigin() {}

    static Optional<String> of(URL location) {
        String path =
                URLDecoder.decode(location.toString().replace("+", "%2B"), StandardCharsets.UTF_8);
        if (!path.contains("!") && path.endsWith(".jar")) {
            path = path + "!";
        }
        for (Pattern pattern : new Pattern[] {REACTOR, REPOSITORY}) {
            Matcher matcher = pattern.matcher(path);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    static Optional<String> of(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? Optional.empty() : of(source.getLocation());
    }

    static String tablePrefix(String module) {
        return module.replace('-', '_') + "_";
    }

    static String javaPackage(String module) {
        return "com.adtek." + module.replace('-', '.');
    }
}
