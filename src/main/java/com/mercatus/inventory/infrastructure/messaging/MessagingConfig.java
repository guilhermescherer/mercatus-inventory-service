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

    // Ao desligar o serviço, o produtor tenta entregar o que ainda está em memória.
    // O padrão do Spring espera até 30s; aqui 5s bastam, porque o que não saiu
    // continua pendente na outbox e será reenviado na próxima subida.
    @Bean
    DefaultKafkaProducerFactoryCustomizer producerCloseTimeout() {
        return factory -> factory.setPhysicalCloseTimeout(5);
    }

    // O listener padrão do Spring Kafka escreve a mensagem INTEIRA no log quando um
    // envio falha. Como mensagens podem conter dados de clientes, trocamos por um
    // listener que não faz nada; o OutboxPublisher já loga a falha só com o id.
    @Bean
    ProducerListener<Object, Object> silentProducerListener() {
        return new ProducerListener<>() {
        };
    }

    // O que fazer quando o processamento de uma mensagem recebida dá erro:
    // - erro técnico (banco fora, timeout): tenta de novo a MESMA mensagem, esperando
    //   1s, 2s, 4s... até 30s entre tentativas, sem desistir. O offset não avança,
    //   então a mensagem não é "esquecida".
    // - mensagem fora do contrato (ContractViolationException): tentar de novo não
    //   adianta, ela sempre vai falhar. Loga como ERROR e segue para a próxima, para
    //   não travar a fila inteira por causa de uma mensagem quebrada.
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
