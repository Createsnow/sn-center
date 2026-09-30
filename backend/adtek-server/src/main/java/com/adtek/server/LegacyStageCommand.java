package com.adtek.server;

import com.adtek.sn.config.LegacyStageFactory;
import com.adtek.sn.dto.LegacyStageCommandDTO;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.*;

/** 迁移staging入口，默认只读计划；与来源导出分开注入目标账号，不启动业务服务。 */
final class LegacyStageCommand {
    private LegacyStageCommand() {}

    static int run(String[] args) {
        try {
            var options = new HashMap<String, String>();
            boolean apply = false;
            for (int i = 1; i < args.length; i++) {
                if ("--apply".equals(args[i]) && !apply) {
                    apply = true;
                    continue;
                }
                if (!Set.of("--run-id", "--snapshot-id", "--chunk-size", "--mapping-version")
                                .contains(args[i])
                        || i + 1 >= args.length
                        || options.put(args[i], args[++i]) != null)
                    throw new IllegalArgumentException();
            }
            if (!options.containsKey("--snapshot-id")
                    || (apply && !options.containsKey("--run-id")))
                throw new IllegalArgumentException();
            var command =
                    new LegacyStageCommandDTO(
                            options.getOrDefault("--run-id", UUID.randomUUID().toString()),
                            options.get("--snapshot-id"),
                            options.getOrDefault("--mapping-version", "legacy-v1-staging-v1"),
                            apply,
                            Integer.parseInt(options.getOrDefault("--chunk-size", "500")));
            var target =
                    new DriverManagerDataSource(
                            required("ADTEK_LEGACY_TARGET_URL"),
                            required("ADTEK_LEGACY_TARGET_USER"),
                            required("ADTEK_LEGACY_TARGET_PASSWORD"));
            var result =
                    LegacyStageFactory.create(
                                    target,
                                    required("ADTEK_LEGACY_TARGET_SCHEMA"),
                                    Path.of(required("ADTEK_LEGACY_SNAPSHOT_ROOT")))
                            .stage(command);
            System.out.println(new ObjectMapper().writeValueAsString(result));
            return 0;
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("LEGACY_[A-Z0-9_]+")) code = "LEGACY_STAGE_FAILED";
            System.err.println(code);
            return 2;
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("LEGACY_ENVIRONMENT_MISSING");
        return value;
    }
}
