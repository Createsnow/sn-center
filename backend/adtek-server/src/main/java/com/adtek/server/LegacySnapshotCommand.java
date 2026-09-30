package com.adtek.server;

import com.adtek.integration.legacy.LegacySnapshotFactory;
import com.adtek.sn.dto.LegacySnapshotCommandDTO;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;

/** 显式旧库只读导出CLI，不启动应用、不自动探测旧.env、不向业务目标库写入。 */
final class LegacySnapshotCommand {
    private LegacySnapshotCommand() {}

    static int run(String[] args) {
        try {
            var options = new HashMap<String, String>();
            for (int i = 1; i < args.length; i++)
                if (!Set.of(
                                        "--run-id",
                                        "--source-schema",
                                        "--source-zone",
                                        "--schema-version",
                                        "--row-limit",
                                        "--byte-limit")
                                .contains(args[i])
                        || i + 1 >= args.length
                        || options.put(args[i], args[++i]) != null)
                    throw new IllegalArgumentException();
            for (String key :
                    List.of("--run-id", "--source-schema", "--source-zone", "--schema-version"))
                if (!options.containsKey(key)) throw new IllegalArgumentException();
            String run = options.get("--run-id");
            if (!UUID.fromString(run).toString().equals(run)) throw new IllegalArgumentException();
            Path root =
                    Path.of(required("ADTEK_LEGACY_SNAPSHOT_ROOT")).toAbsolutePath().normalize();
            for (Path p = root; p != null; p = p.getParent())
                if (Files.isSymbolicLink(p)) throw new IllegalArgumentException();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException();
            var command =
                    new LegacySnapshotCommandDTO(
                            run,
                            options.get("--source-schema"),
                            options.get("--schema-version"),
                            options.get("--source-zone"),
                            Long.parseLong(options.getOrDefault("--row-limit", "100000")),
                            Long.parseLong(options.getOrDefault("--byte-limit", "1073741824")),
                            root.resolve(run));
            var source =
                    new DriverManagerDataSource(
                            required("ADTEK_LEGACY_SOURCE_URL"),
                            required("ADTEK_LEGACY_SOURCE_USER"),
                            required("ADTEK_LEGACY_SOURCE_PASSWORD"));
            var manifest = LegacySnapshotFactory.create(source).capture(command);
            // 完整manifest保存受控目录；控制台仅有摘要/计数/问题类型，没有源行、凭据或完整JDBC地址。
            var summary =
                    Map.of(
                            "runId",
                            run,
                            "state",
                            "SNAPSHOT_CAPTURED",
                            "sourceFingerprint",
                            manifest.sourceFingerprint(),
                            "tableCount",
                            manifest.tables().size(),
                            "rows",
                            manifest.tables().stream()
                                            .mapToLong(x -> Long.parseLong(x.rows()))
                                            .sum()
                                    + "",
                            "issues",
                            manifest.issues(),
                            "readyForPromotion",
                            false);
            System.out.println(new ObjectMapper().writeValueAsString(summary));
            return 0;
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("LEGACY_[A-Z0-9_]+")) code = "LEGACY_SNAPSHOT_FAILED";
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
