package com.mercatus.inventory.infrastructure.config;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentifierConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> inventoryIdentifiers(Clock clock) {
        return new UuidV7Generator(clock);
    }
}
