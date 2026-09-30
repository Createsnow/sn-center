package com.adtek.framework.task;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
public final class TaskDigests {
    private TaskDigests() {}
    public static String sha256(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
