package com.adtek.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;

/** 模块装配入口；业务用例由各模块 Service 承担。 */
@SpringBootApplication(scanBasePackages = "com.adtek", exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan({"com.adtek.system.mapper", "com.adtek.sn.mapper", "com.adtek.framework.task.mapper"})
public class AdtekApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--legacy-inventory".equals(args[0])) {
            System.exit(LegacyInventoryCommand.run(args));
            return;
        }
        if (args.length > 0 && "--legacy-master".equals(args[0])) {
            System.exit(LegacyMasterCommand.run(args));
            return;
        }
        if (args.length > 0 && "--legacy-stage".equals(args[0])) {
            System.exit(LegacyStageCommand.run(args));
            return;
        }
        if (args.length > 0 && "--legacy-snapshot".equals(args[0])) {
            System.exit(LegacySnapshotCommand.run(args));
            return;
        }
        if (args.length > 0 && "--maintenance-scheduler".equals(args[0])) {
            System.exit(MaintenanceSchedulerCommand.run(args));
            return;
        }
        if (args.length > 0 && "--migrate".equals(args[0])) {
            System.exit(MigrationCommand.run(args));
            return;
        }
        if (args.length == 1 && "--healthcheck".equals(args[0])) {
            System.exit(HealthCommand.run());
            return;
        }
        if (args.length > 0 && "--maintenance".equals(args[0])) {
            System.exit(MaintenanceCommand.run(args));
            return;
        }
        SpringApplication.run(AdtekApplication.class, args);
    }
}
