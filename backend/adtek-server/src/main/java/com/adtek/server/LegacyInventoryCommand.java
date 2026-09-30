package com.adtek.server;

import com.adtek.sn.config.LegacyInventoryFactory;
import com.adtek.sn.dto.LegacyMasterCommandDTO;
import com.adtek.system.config.LegacyIdentityFactory;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.*;

/** 有限IMPORT库存阶段入口，参数与主数据阶段同源，默认只读且不会启动Web或启用账号。 */
final class LegacyInventoryCommand {
    private LegacyInventoryCommand() {}

    static int run(String[] args) {
        try {
            var options = new HashMap<String, String>();
            boolean apply = false;
            for (int i = 1; i < args.length; i++) {
                if ("--apply".equals(args[i]) && !apply) {
                    apply = true;
                    continue;
                }
                if (!Set.of("--run-id", "--snapshot-id").contains(args[i])
                        || i + 1 >= args.length
                        || options.put(args[i], args[++i]) != null)
                    throw new IllegalArgumentException();
            }
            var command =
                    new LegacyMasterCommandDTO(
                            options.get("--run-id"), options.get("--snapshot-id"), apply);
            var target =
                    new DriverManagerDataSource(
                            required("ADTEK_LEGACY_TARGET_URL"),
                            required("ADTEK_LEGACY_TARGET_USER"),
                            required("ADTEK_LEGACY_TARGET_PASSWORD"));
            var result =
                    LegacyInventoryFactory.create(
                                    target,
                                    required("ADTEK_LEGACY_TARGET_SCHEMA"),
                                    Path.of(required("ADTEK_LEGACY_SNAPSHOT_ROOT")),
                                    LegacyIdentityFactory.create())
                            .promote(command);
            System.out.println(new ObjectMapper().writeValueAsString(result));
            return 0;
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("LEGACY_[A-Z0-9_]+"))
                code = "LEGACY_INVENTORY_FAILED";
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
