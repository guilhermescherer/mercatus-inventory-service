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

// Ponto de entrada para "publicar" uma mensagem no Kafka sem falar com o Kafka.
//
// Problema que isso resolve (dual write): se o caso de uso salvasse no banco e
// depois enviasse direto ao Kafka, uma das duas coisas poderia falhar sozinha —
// banco salvo e mensagem perdida, ou mensagem enviada e banco desfeito.
//
// Solução (outbox): a mensagem vira uma linha na tabela outbox_message, gravada na
// MESMA transação do dado de negócio. Ou as duas ficam salvas, ou nenhuma. Quem
// leva a linha até o Kafka depois é o OutboxPublisher.
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

    // MANDATORY: só funciona dentro de uma transação que já existe (a do caso de
    // uso). Chamado fora de transação, lança erro — assim é impossível gravar a
    // mensagem "separada" do dado de negócio por engano.
    @Transactional(propagation = Propagation.MANDATORY)
    public MessageEnvelope enqueue(String topic, OutgoingMessage message) {
        // O envelope é o "cabeçalho" padrão de toda mensagem: id único (usado pelo
        // consumidor para descartar repetições), tipo, versão do formato, quando
        // aconteceu, a qual pedido se refere e de qual fluxo/mensagem veio.
        var envelope = new MessageEnvelope(
                identifiers.get(),
                message.type(),
                message.schemaVersion(),
                clock.instant().truncatedTo(MICROS),
                message.aggregateId(),
                message.correlationId(),
                message.causationId(),
                jsonMapper.valueToTree(message.payload()));

        // aggregateId (o orderId) vira a CHAVE da mensagem no Kafka: mensagens com a
        // mesma chave vão para a mesma partição e são lidas na ordem em que entraram.
        outbox.append(new OutboxMessage(envelope.messageId(), topic, envelope.aggregateId(), envelope.type(),
                jsonMapper.writeValueAsString(envelope), envelope.occurredAt()));

        return envelope;
    }

    public record OutgoingMessage(String type, int schemaVersion, String aggregateId, String correlationId,
            String causationId, Object payload) {
    }
}
