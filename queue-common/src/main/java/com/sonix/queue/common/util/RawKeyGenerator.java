package com.sonix.queue.common.util;

import java.security.SecureRandom;
import java.util.HexFormat;

public class RawKeyGenerator {
    public static String generate(){
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);

        return "sk_live_" + HexFormat.of().formatHex(bytes);
    }
}
