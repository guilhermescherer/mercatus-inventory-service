package com.mercatus.inventory.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

    @Bean
    ContractReader contractReader(JsonMapper jsonMapper) {
        return new ContractReader(jsonMapper);
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer producerCloseTimeout() {
        return factory -> factory.setPhysicalCloseTimeout(5);
    }

    @Bean
    ProducerListener<Object, Object> silentProducerListener() {
        return new ProducerListener<>() {
        };
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        var backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);

        var handler = new DefaultErrorHandler((record, error) -> log.error(
                "Rejected message at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(),
                rootMessage(error)), backOff);
        handler.addNotRetryableExceptions(ContractViolationException.class);

        return handler;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage();
    }
}
