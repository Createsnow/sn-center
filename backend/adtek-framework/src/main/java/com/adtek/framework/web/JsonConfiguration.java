package com.adtek.framework.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 约定（所有模块一致）：
 *
 * <ul>
 *   <li>{@code long/Long}（业务 ID、数据库计数）写成十进制字符串，前端不从 number 恢复精确值；{@code int} 仍为 number（有界值）；
 *   <li>{@code BigDecimal} 写成去掉多余 0 的十进制字符串；
 *   <li>{@code LocalDateTime} 一律表示 UTC，写成 {@code yyyy-MM-ddTHH:mm:ssZ}（与原 Python 版接口一致），{@code
 *       LocalDate} 写成 {@code yyyy-MM-dd}。
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    /** 接口时间格式：UTC，精确到秒。 */
    public static final DateTimeFormatter UTC_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Bean
    JsonMapperBuilderCustomizer adtekJsonConventions() {
        SimpleModule module = new SimpleModule("adtek-json-conventions");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(BigDecimal.class, new DecimalSerializer());
        module.addSerializer(LocalDateTime.class, new UtcDateTimeSerializer());
        module.addSerializer(LocalDate.class, new DateSerializer());
        return builder -> builder.addModule(module);
    }

    static final class DecimalSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(plain(value));
        }
    }

    static final class UtcDateTimeSerializer extends ValueSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(UTC_TS.format(value.withNano(0)));
        }
    }

    static final class DateSerializer extends ValueSerializer<LocalDate> {
        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.toString());
        }
    }

    /** 十进制字符串：整数不带小数点，小数去掉末尾 0。 */
    public static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
