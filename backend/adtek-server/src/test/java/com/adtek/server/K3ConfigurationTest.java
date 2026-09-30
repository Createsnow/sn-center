package com.adtek.server;

import com.adtek.common.core.BusinessException;
import com.adtek.integration.k3.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 用Boot配置加载器和实际回环K3分页读取验证秘密目录接入。
 * 仅临时目录/合成秘密；Windows配置脚本另由verify-k3-configuration.ps1执行，不给跨平台Java门禁增加Shell依赖。
 */
class K3ConfigurationTest {
    private static final String SECRET="synthetic:$#'\"\\=!";
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper();
    private void property(String key,String value)throws Exception {
        Path directory=Files.createDirectories(temporary.resolve("project/deploy/.secrets/k3"));
        Files.writeString(directory.resolve("adtek.k3."+key),value);
    }
    private K3Settings settings() {
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("isolated",Map.of(
            "spring.config.import","optional:configtree:"+temporary.resolve("project/deploy/.secrets/k3").toString().replace('\\','/')+"/",
            "spring.config.location","classpath:/application.yml","adtek.k3.page-size","2","ADTEK_K3_ENABLED","false")));
        ConfigDataEnvironmentPostProcessor.applyTo(env);
        return Binder.get(env).bind("adtek.k3",K3Settings.class).orElseThrow(()->new IllegalStateException("K3 configuration was not bound"));
    }
    @Test void installedConfigurationFeedsActualFullSyncAndCanBeDisabled()throws Exception {
        assertThat(settings().enabled()).isFalse();
        try(var fixture=new K3ProtocolFixture()) {
            fixture.orders=List.of(
                K3ProtocolFixture.row("1","1","A","C","P","101","F","厂","M","1"),
                K3ProtocolFixture.row("1","2","A","C","P","101","F","厂","M","2"),
                K3ProtocolFixture.row("1","3","A","C","P","101","F","厂","","3"),
                K3ProtocolFixture.row("2","4","B","C","P","101","F","厂","M","4"),
                K3ProtocolFixture.row("2","5","B","C","P","101","F","厂","M","5"));
            for(var entry:Map.of("url",fixture.url(),"account","fixture-account","app-id","fixture-app","app-secret",SECRET,"username","合成用户","enabled","true").entrySet())property(entry.getKey(),entry.getValue());
            var config=settings();assertThat(config.enabled()).isTrue();assertThat(config.appSecret()).isEqualTo(SECRET);assertThat(config.username()).isEqualTo("合成用户");
            var rows=new K3ProductionOrderAdapter(config,json).orders(null);
            assertThat(rows).hasSize(5);assertThat(rows.getLast().entryId()).isEqualTo("5");assertThat(fixture.orderOffsets).containsExactly(0,2,4);
            property("enabled","false");assertThat(settings().enabled()).isFalse();
            assertThatThrownBy(()->new K3ProductionOrderAdapter(settings(),json).orders(null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getError().code()).isEqualTo(1005000001));
            assertThat(fixture.orderOffsets).containsExactly(0,2,4);
        }
    }
    @Test void incompleteTreeCannotContactErp()throws Exception {
        property("enabled","true");property("app-secret",SECRET);
        assertThatThrownBy(()->new K3ProductionOrderAdapter(settings(),json).orders(null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getError().code()).isEqualTo(1005000001));
    }
}
