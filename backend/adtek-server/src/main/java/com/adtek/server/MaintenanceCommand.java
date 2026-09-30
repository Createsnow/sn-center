package com.adtek.server;

import com.adtek.sn.config.SnMaintenanceFactory;
import com.adtek.sn.dto.MaintenanceCommandDTO;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 运维命令装配：不启动Web、Redis、工作器或Flyway，不从页面运行账号回退取凭据。 */
final class MaintenanceCommand {
    private MaintenanceCommand() {}

    static int run(String[] args) {
        try {
            var options = new HashMap<String, String>();
            boolean apply = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--apply")) {
                    if (apply) throw new IllegalArgumentException();
                    apply = true;
                    continue;
                }
                if (!Set.of(
                                        "--action",
                                        "--run-id",
                                        "--month",
                                        "--months-ahead",
                                        "--row-limit",
                                        "--proof-directory")
                                .contains(args[i])
                        || i + 1 >= args.length
                        || options.put(args[i], args[++i]) != null)
                    throw new IllegalArgumentException();
            }
            if (apply && !options.containsKey("--run-id")) throw new IllegalArgumentException();
            var command =
                    new MaintenanceCommandDTO(
                            MaintenanceCommandDTO.Action.valueOf(
                                    options.getOrDefault("--action", "PARTITIONS")),
                            options.getOrDefault("--run-id", UUID.randomUUID().toString()),
                            apply,
                            YearMonth.parse(
                                    options.getOrDefault(
                                            "--month", YearMonth.now(ZoneOffset.UTC).toString())),
                            Integer.parseInt(options.getOrDefault("--months-ahead", "3")),
                            Integer.parseInt(options.getOrDefault("--row-limit", "100000")),
                            options.get("--proof-directory"));
            var source =
                    new DriverManagerDataSource(
                            required("ADTEK_MAINT_DB_URL"),
                            required("ADTEK_MAINT_DB_USER"),
                            required("ADTEK_MAINT_DB_PASSWORD"));
            String root = System.getenv("ADTEK_MAINT_PROOF_ROOT");
            var result =
                    SnMaintenanceFactory.create(
                                    source,
                                    required("ADTEK_MAINT_ALLOWED_SCHEMA"),
                                    root == null ? null : Path.of(root))
                            .execute(command);
            System.out.println(new ObjectMapper().writeValueAsString(result));
            return 0;
        } catch (Exception failure) {
            // 不打印异常堆栈/原始SQL/JDBC地址，调度器依据非零退出和稳定分类告警。
            String code = failure.getMessage();
            if (code == null || !code.matches("(?:MAINTENANCE|PARTITION|ARCHIVE)_[A-Z0-9_]+"))
                code = "MAINTENANCE_INVALID_OR_FAILED";
            System.err.println(code);
            return 2;
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("MAINTENANCE_ENVIRONMENT_MISSING");
        return value;
    }
}
