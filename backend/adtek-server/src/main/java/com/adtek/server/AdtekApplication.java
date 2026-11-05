package com.adtek.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * 模块装配入口：server 只负责装配各模块与运行配置，业务用例由各模块 Service 承担。
 *
 * <p>{@code com.adtek.**.mapper} 覆盖任意层级的 mapper 包（如 {@code com.adtek.framework.task.mapper}）。
 */
@SpringBootApplication(
        scanBasePackages = "com.adtek",
        exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.adtek.**.mapper")
public class AdtekApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdtekApplication.class, args);
    }
}
