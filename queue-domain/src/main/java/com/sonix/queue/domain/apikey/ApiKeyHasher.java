package com.sonix.queue.domain.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ApiKeyHasher {
    public static String hash(String rawApiKey){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] byteRawApiKey = digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(byteRawApiKey);
        } catch(NoSuchAlgorithmException  e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean matches(String rawApiKey, String storedHash) {
        return hash(rawApiKey).equals(storedHash);
    }
}
