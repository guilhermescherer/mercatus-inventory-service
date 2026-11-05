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

    @KafkaListener(topics = "${inventory.messaging.topics.commands}")
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        MessageEnvelope envelope = reader.readEnvelope(record.value());

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
