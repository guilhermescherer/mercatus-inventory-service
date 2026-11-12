package com.mercatus.inventory.infrastructure.messaging.inbound;

import com.mercatus.inventory.application.reservation.port.in.ReleaseForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase.ReserveForOrderCommand;
import com.mercatus.inventory.infrastructure.messaging.config.MessagingProperties;
import com.mercatus.inventory.infrastructure.messaging.contract.ContractReader;
import com.mercatus.inventory.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.inventory.infrastructure.messaging.contract.OutboundMessageType;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.InventoryReservationFailedPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.InventoryReservedPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReleaseReservationPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReservationExpiredPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReservationReleasedPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReserveInventoryPayload;
import com.mercatus.inventory.infrastructure.messaging.inbox.InboxStore;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter.OutgoingMessage;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

    private final ContractReader reader;
    private final InboxStore inbox;
    private final OutboxWriter outbox;
    private final ReserveForOrderUseCase reserveForOrder;
    private final ReleaseForOrderUseCase releaseForOrder;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MessagingProperties properties;

    InventoryCommandListener(ContractReader reader, InboxStore inbox, OutboxWriter outbox,
            ReserveForOrderUseCase reserveForOrder, ReleaseForOrderUseCase releaseForOrder,
            TransactionTemplate transactions, Clock clock, MessagingProperties properties) {
        this.reader = reader;
        this.inbox = inbox;
        this.outbox = outbox;
        this.reserveForOrder = reserveForOrder;
        this.releaseForOrder = releaseForOrder;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    @KafkaListener(topics = "${inventory.messaging.topics.commands}")
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        MessageEnvelope envelope = reader.readEnvelope(record.value());

        MDC.put("correlationId", envelope.correlationId());
        try {
            switch (envelope.type()) {
                case ReserveInventoryPayload.TYPE -> reserve(envelope);
                case ReleaseReservationPayload.TYPE -> release(envelope);
                default -> log.warn("Ignoring message {} of unexpected type {}", envelope.messageId(),
                        envelope.type());
            }

            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void reserve(MessageEnvelope envelope) {
        ReserveInventoryPayload payload = reader.readReserveInventory(envelope.payload());

        var outcome = once(envelope, () -> {
            var result = reserveForOrder.reserve(new ReserveForOrderCommand(payload.orderId(),
                    payload.lines().stream()
                            .map(line -> new ReserveForOrderCommand.Line(line.skuCode(), line.quantity()))
                            .toList(), envelope.correlationId()));
            outbox.enqueue(properties.topics().events(), reservationReply(envelope, payload, result));
            return result;
        });

        if (outcome != null) {
            log.info("Order {} reservation outcome {}", payload.orderId(), outcome.getClass().getSimpleName());
        }
    }

    private void release(MessageEnvelope envelope) {
        ReleaseReservationPayload payload = reader.readReleaseReservation(envelope.payload());

        var outcome = once(envelope, () -> {
            var result = releaseForOrder.release(payload.orderId(), payload.reservationId());
            releaseReply(envelope, payload, result)
                    .ifPresent(reply -> outbox.enqueue(properties.topics().events(), reply));
            return result;
        });

        if (outcome instanceof ReleaseForOrderUseCase.Outcome.Rejected rejected) {
            log.error("Order {} release of reservation {} rejected: {}", payload.orderId(),
                    payload.reservationId(), rejected.reason());
        } else if (outcome != null) {
            log.info("Order {} release outcome {}", payload.orderId(), outcome.getClass().getSimpleName());
        }
    }

    private <T> T once(MessageEnvelope envelope, Supplier<T> effect) {
        T outcome = transactions.execute(status ->
                inbox.register(envelope.messageId(), envelope.type(), clock.instant()) ? effect.get() : null);

        if (outcome == null) {
            log.info("Duplicate message {} ignored", envelope.messageId());
        }

        return outcome;
    }

    private OutgoingMessage reservationReply(MessageEnvelope command, ReserveInventoryPayload payload,
            ReserveForOrderUseCase.Outcome outcome) {
        UUID orderId = payload.orderId();

        return switch (outcome) {
            case ReserveForOrderUseCase.Outcome.Reserved reserved -> reply(command, orderId,
                    OutboundMessageType.INVENTORY_RESERVED,
                    new InventoryReservedPayload(orderId, reserved.reservationId(), reserved.expiresAt()));
            case ReserveForOrderUseCase.Outcome.OutOfStock outOfStock -> reply(command, orderId,
                    OutboundMessageType.INVENTORY_RESERVATION_FAILED,
                    new InventoryReservationFailedPayload(orderId, InventoryReservationFailedPayload.Reason.OUT_OF_STOCK));
        };
    }

    private Optional<OutgoingMessage> releaseReply(MessageEnvelope command, ReleaseReservationPayload payload,
            ReleaseForOrderUseCase.Outcome outcome) {
        UUID orderId = payload.orderId();

        return switch (outcome) {
            case ReleaseForOrderUseCase.Outcome.Released released -> Optional.of(reply(command, orderId,
                    OutboundMessageType.RESERVATION_RELEASED,
                    new ReservationReleasedPayload(orderId, released.reservationId())));
            case ReleaseForOrderUseCase.Outcome.AlreadyExpired expired -> Optional.of(reply(command, orderId,
                    OutboundMessageType.RESERVATION_EXPIRED,
                    new ReservationExpiredPayload(orderId, expired.reservationId(), expired.expiresAt())));
            case ReleaseForOrderUseCase.Outcome.Rejected rejected -> Optional.empty();
        };
    }

    private OutgoingMessage reply(MessageEnvelope command, UUID orderId, OutboundMessageType type, Object payload) {
        return new OutgoingMessage(type, orderId.toString(), command.correlationId(),
                command.messageId().toString(), payload);
    }
}
