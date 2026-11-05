package com.mercatus.inventory.infrastructure.messaging;

import com.mercatus.inventory.infrastructure.persistence.outbox.OutboxMessage;
import com.mercatus.inventory.infrastructure.persistence.outbox.OutboxStore;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// O "carteiro" da outbox: de tempos em tempos lê as mensagens pendentes no banco e
// as entrega ao Kafka. Só marca como publicada depois que o Kafka confirma o
// recebimento (ack). Se o Kafka estiver fora, nada é perdido: a linha continua
// pendente e é tentada de novo na próxima rodada.
//
// Consequência: uma mesma mensagem pode ser enviada mais de uma vez (ex.: o Kafka
// recebeu, mas o serviço caiu antes de marcar no banco). Isso se chama entrega
// "at-least-once" (pelo menos uma vez) e é por isso que o consumidor tem a inbox.
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MessagingProperties properties;

    OutboxPublisher(OutboxStore outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
            Clock clock, MessagingProperties properties) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    // fixedDelay: a próxima rodada começa X ms depois que a anterior TERMINOU,
    // então duas rodadas nunca rodam ao mesmo tempo neste processo.
    @Scheduled(fixedDelayString = "${inventory.messaging.outbox.poll-interval-ms:500}")
    public void publishPending() {
        int batchSize = properties.outbox().batchSize();
        Batch batch;

        // Cada lote roda numa transação própria. Enquanto vierem lotes cheios e sem
        // erro, continua; assim um acúmulo de pendências é escoado logo.
        do {
            batch = transactions.execute(status -> publishBatch(batchSize));
        } while (batch != null && batch.complete() && batch.published() == batchSize);
    }

    private Batch publishBatch(int batchSize) {
        int published = 0;

        for (var message : outbox.lockPending(batchSize)) {
            // Parar no primeiro erro preserva a ordem: se a mensagem 1 de um pedido
            // falhou, a 2 não pode passar na frente dela.
            if (!send(message)) {
                return new Batch(published, false);
            }

            outbox.markPublished(message.id(), clock.instant());
            published++;
        }

        return new Batch(published, true);
    }

    private boolean send(OutboxMessage message) {
        long timeout = properties.outbox().sendTimeout().toMillis();

        try {
            // send(tópico, chave, valor) é assíncrono e devolve um "futuro". O get()
            // espera a confirmação do broker; sem ele não saberíamos se chegou.
            // Com acks=all (application.yml) a confirmação só vem depois que a
            // mensagem foi gravada no Kafka de forma segura.
            kafka.send(message.topic(), message.aggregateId(), message.payload())
                    .get(timeout, TimeUnit.MILLISECONDS);
            log.info("Published outbox message {} of type {} to {}", message.id(), message.type(), message.topic());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Outbox message {} not published, will retry: {}", message.id(), e.getMessage());
            return false;
        }
    }

    private record Batch(int published, boolean complete) {
    }
}
