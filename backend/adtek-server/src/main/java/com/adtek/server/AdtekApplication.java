package com.adtek.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** 模块装配入口；业务用例由各模块 Service 承担。 */
@SpringBootApplication(
        scanBasePackages = "com.adtek",
        exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan({
    "com.adtek.system.mapper",
    "com.adtek.equipment.mapper",
    "com.adtek.framework.task.mapper"
})
public class AdtekApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--migrate".equals(args[0])) {
            System.exit(MigrationCommand.run(args));
            return;
        }
        if (args.length == 1 && "--healthcheck".equals(args[0])) {
            System.exit(HealthCommand.run());
            return;
        }
        SpringApplication.run(AdtekApplication.class, args);
    }
}
