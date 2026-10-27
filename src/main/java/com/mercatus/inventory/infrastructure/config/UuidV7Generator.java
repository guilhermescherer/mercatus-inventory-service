package com.mercatus.inventory.infrastructure.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

public final class UuidV7Generator implements Supplier<UUID> {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID get() {
        long most = (clock.millis() << 16) | 0x7000L | random.nextInt(4096);
        long least = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;

        return new UUID(most, least);
    }
}
