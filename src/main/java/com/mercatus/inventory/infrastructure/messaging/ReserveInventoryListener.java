package com.mercatus.inventory.infrastructure.messaging;

import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase.Outcome;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase.ReserveForOrderCommand;
import com.mercatus.inventory.infrastructure.messaging.OutboxWriter.OutgoingMessage;
import com.mercatus.inventory.infrastructure.messaging.payload.InventoryReservationFailedPayload;
import com.mercatus.inventory.infrastructure.messaging.payload.InventoryReservedPayload;
import com.mercatus.inventory.infrastructure.messaging.payload.ReserveInventoryPayload;
import com.mercatus.inventory.infrastructure.persistence.inbox.InboxStore;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// O consumidor do comando ReserveInventory, enviado pelo Order.
//
// Passo a passo de cada mensagem:
//  1. lê e valida o envelope (fora do contrato → ContractViolationException → o
//     DefaultErrorHandler loga e segue, sem tentar de novo);
//  2. abre UMA transação no banco e, dentro dela:
//     a. registra o messageId na inbox (já existia? mensagem repetida → não faz nada);
//     b. tenta reservar o estoque;
//     c. grava a resposta (reservado / sem estoque) na outbox;
//  3. só depois do commit chama ack(), que avisa o Kafka para avançar o offset.
//
// Se o serviço cair entre o commit e o ack, o Kafka entrega a mesma mensagem de novo;
// a inbox reconhece o messageId e nada é repetido.
@Component
class ReserveInventoryListener {

    private static final Logger log = LoggerFactory.getLogger(ReserveInventoryListener.class);

    private final ContractReader reader;
    private final InboxStore inbox;
    private final OutboxWriter outbox;
    private final ReserveForOrderUseCase reserveForOrder;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MessagingProperties properties;

    ReserveInventoryListener(ContractReader reader, InboxStore inbox, OutboxWriter outbox,
            ReserveForOrderUseCase reserveForOrder, TransactionTemplate transactions, Clock clock,
            MessagingProperties properties) {
        this.reader = reader;
        this.inbox = inbox;
        this.outbox = outbox;
        this.reserveForOrder = reserveForOrder;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    // @KafkaListener: o Spring cria um consumidor que fica lendo este tópico e chama
    // este método para cada mensagem. O grupo (inventory-service) vem do application.yml.
    // Acknowledgment: o "botão" para avançar o offset; só apertamos depois do commit.
    @KafkaListener(topics = "${inventory.messaging.topics.commands}")
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        MessageEnvelope envelope = reader.readEnvelope(record.value());

        // O MDC coloca o correlationId em todas as linhas de log deste processamento,
        // para seguir o mesmo pedido nos logs do Order e do Inventory.
        MDC.put("correlationId", envelope.correlationId());
        try {
            if (!ReserveInventoryPayload.TYPE.equals(envelope.type())) {
                log.warn("Ignoring message {} of unexpected type {}", envelope.messageId(), envelope.type());
                ack.acknowledge();
                return;
            }

            ReserveInventoryPayload payload = reader.readReserveInventory(envelope.payload());

            Outcome outcome = transactions.execute(status -> handle(envelope, payload));

            if (outcome == null) {
                log.info("Duplicate message {} ignored", envelope.messageId());
            } else {
                log.info("Order {} reservation outcome {}", payload.orderId(),
                        outcome.getClass().getSimpleName());
            }

            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private Outcome handle(MessageEnvelope envelope, ReserveInventoryPayload payload) {
        if (!inbox.register(envelope.messageId(), envelope.type(), clock.instant())) {
            return null;
        }

        Outcome outcome = reserveForOrder.reserve(new ReserveForOrderCommand(payload.orderId(),
                payload.lines().stream()
                        .map(line -> new ReserveForOrderCommand.Line(line.skuCode(), line.quantity()))
                        .toList()));

        outbox.enqueue(properties.topics().events(), reply(envelope, payload, outcome));

        return outcome;
    }

    // A resposta carrega o mesmo correlationId (mesmo fluxo de negócio) e, como
    // causationId, o messageId do comando que a provocou.
    private OutgoingMessage reply(MessageEnvelope command, ReserveInventoryPayload payload, Outcome outcome) {
        String orderId = payload.orderId().toString();

        return switch (outcome) {
            case Outcome.Reserved reserved -> new OutgoingMessage(InventoryReservedPayload.TYPE, 1, orderId,
                    command.correlationId(), command.messageId().toString(),
                    new InventoryReservedPayload(payload.orderId(), reserved.reservationId(), reserved.expiresAt()));
            case Outcome.OutOfStock outOfStock -> new OutgoingMessage(InventoryReservationFailedPayload.TYPE, 1,
                    orderId, command.correlationId(), command.messageId().toString(),
                    new InventoryReservationFailedPayload(payload.orderId(),
                            InventoryReservationFailedPayload.OUT_OF_STOCK));
        };
    }
}
