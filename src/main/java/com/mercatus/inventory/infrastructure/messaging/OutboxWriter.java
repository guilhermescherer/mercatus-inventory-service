package com.mercatus.inventory.infrastructure.messaging;

import com.mercatus.inventory.infrastructure.persistence.outbox.OutboxMessage;
import com.mercatus.inventory.infrastructure.persistence.outbox.OutboxStore;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static java.time.temporal.ChronoUnit.MICROS;

@Component
public class OutboxWriter {

    private final OutboxStore outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    OutboxWriter(OutboxStore outbox, JsonMapper jsonMapper, Clock clock, Supplier<UUID> identifiers) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.identifiers = identifiers;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MessageEnvelope enqueue(String topic, OutgoingMessage message) {
        var envelope = new MessageEnvelope(
                identifiers.get(),
                message.type(),
                message.schemaVersion(),
                clock.instant().truncatedTo(MICROS),
                message.aggregateId(),
                message.correlationId(),
                message.causationId(),
                jsonMapper.valueToTree(message.payload()));

        outbox.append(new OutboxMessage(envelope.messageId(), topic, envelope.aggregateId(), envelope.type(),
                jsonMapper.writeValueAsString(envelope), envelope.occurredAt()));

        return envelope;
    }

    public record OutgoingMessage(String type, int schemaVersion, String aggregateId, String correlationId,
            String causationId, Object payload) {
    }
}
