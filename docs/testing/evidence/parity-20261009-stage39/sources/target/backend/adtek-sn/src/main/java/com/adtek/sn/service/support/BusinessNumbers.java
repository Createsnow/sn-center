package com.adtek.sn.service.support;

import com.adtek.sn.mapper.BusinessNumberMapper;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 与原型一致的 B/P/T + 本地秒时间 + 四位随机尾号；数据库唯一键处理并发碰撞。 */
@Component
public class BusinessNumbers {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final SecureRandom random = new SecureRandom();
    private final BusinessNumberMapper mapper;

    public BusinessNumbers(BusinessNumberMapper mapper) {
        this.mapper = mapper;
    }

    public String next(String prefix) {
        if (!prefix.equals("B") && !prefix.equals("P") && !prefix.equals("T"))
            throw new IllegalArgumentException("Business number prefix");
        for (int attempt = 0; attempt < 100; attempt++) {
            String number =
                    prefix
                            + TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))
                            + String.format(Locale.ROOT, "%04d", random.nextInt(10000));
            if (mapper.reserve(number) == 1) return number;
        }
        throw new IllegalStateException("Business number capacity exhausted");
    }
}
