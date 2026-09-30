package com.adtek.sn.service.support;

import com.adtek.common.core.Actor;
import com.adtek.framework.security.SecuritySettings;
import com.adtek.framework.task.TaskDigests;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 游标不是授权凭据；签名绑定筛选/固定集合、主体和当前授权版本，每页仍复核当前工厂资格。 */
@Component
public class SnCursor {
    public record Position(
            String binding, long ordinal, LocalDate month, String id, Instant expires) {}

    private final byte[] key;
    private final ObjectMapper json;

    public SnCursor(SecuritySettings settings, ObjectMapper json) {
        key = HexFormat.of().parseHex(settings.jwtSecret());
        this.json = json;
    }

    public String binding(Actor actor, String context) {
        return TaskDigests.sha256(
                actor.principalType()
                        + ":"
                        + actor.userId()
                        + ":"
                        + actor.securityVersion()
                        + ":"
                        + context);
    }

    public Position read(String cursor, String binding) {
        if (cursor == null || cursor.isBlank())
            return new Position(binding, 0, null, null, Instant.now().plusSeconds(86400));
        try {
            if (cursor.length() > 2048) throw SnPolicy.invalid();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2
                    || !MessageDigest.isEqual(
                            sign(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw SnPolicy.invalid();
            var value = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Position.class);
            if (!binding.equals(value.binding())
                    || value.ordinal() < 0
                    || !value.expires().isAfter(Instant.now())) throw SnPolicy.invalid();
            return value;
        } catch (RuntimeException error) {
            throw SnPolicy.invalid();
        }
    }

    public String next(String binding, long ordinal, LocalDate month, String id) {
        String body =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                json.writeValueAsBytes(
                                        new Position(
                                                binding,
                                                ordinal,
                                                month,
                                                id,
                                                Instant.now().plusSeconds(86400))));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("sn-cursor-v1:" + body).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
