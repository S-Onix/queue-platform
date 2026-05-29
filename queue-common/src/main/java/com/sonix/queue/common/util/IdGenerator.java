package com.sonix.queue.common.util;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

public class IdGenerator {
    private IdGenerator() {}

    public static String generate(String prefix) {
        UUID uuid = UuidCreator.getTimeOrderedEpoch();
        return prefix + uuid;
    }
}
