package com.mercatus.inventory.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

// Formato comum de toda mensagem trocada pelo Kafka (comandos e eventos).
// ignoreUnknown: campos novos que o produtor venha a adicionar são ignorados, para
// que o produtor possa evoluir sem quebrar este consumidor. Já os campos que este
// serviço precisa são obrigatórios (validados no construtor abaixo).
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageEnvelope(
        UUID messageId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String correlationId,
        String causationId,
        JsonNode payload) {

    public MessageEnvelope {
        if (messageId == null) {
            throw new ContractViolationException("messageId is required");
        }
        if (type == null || type.isBlank()) {
            throw new ContractViolationException("type is required");
        }
        if (schemaVersion <= 0) {
            throw new ContractViolationException("schemaVersion must be positive");
        }
        if (occurredAt == null) {
            throw new ContractViolationException("occurredAt is required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new ContractViolationException("aggregateId is required");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new ContractViolationException("correlationId is required");
        }
        if (payload == null || payload.isNull()) {
            throw new ContractViolationException("payload is required");
        }
    }
}
