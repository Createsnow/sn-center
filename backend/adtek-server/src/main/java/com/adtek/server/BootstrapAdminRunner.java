package com.adtek.server;

import com.adtek.system.dto.BootstrapAdminDTO;
import com.adtek.system.service.BootstrapService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 单次受控命令；参数从进程环境注入，拒绝在 Web 服务中启用并且不输出秘密。 */
@Component
@Profile("bootstrap-admin")
public class BootstrapAdminRunner implements ApplicationRunner {
    private final BootstrapService service;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public BootstrapAdminRunner(
            BootstrapService service,
            Environment environment,
            ConfigurableApplicationContext context) {
        this.service = service;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!"none".equals(environment.getProperty("spring.main.web-application-type"))) {
            throw new IllegalArgumentException("bootstrap-admin must run as a non-web command");
        }
        service.initialize(
                new BootstrapAdminDTO(
                        environment.getProperty("ADTEK_BOOTSTRAP_USERNAME", "admin"),
                        environment.getRequiredProperty("ADTEK_BOOTSTRAP_PASSWORD"),
                        "初始管理员"));
        context.close();
    }
}
