package com.mercatus.inventory.infrastructure.persistence.inbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Inbox: o "caderno de mensagens já processadas" do consumidor.
// Como o Kafka pode entregar a mesma mensagem mais de uma vez, antes de agir o
// consumidor registra o messageId aqui. Se já estava registrado, ignora.
// O registro acontece na MESMA transação do efeito de negócio: se o processamento
// falhar e for desfeito, o registro também some e a mensagem pode ser reprocessada.
@Repository
public class InboxStore {

    private final InboxMessageJpaRepository repository;

    InboxStore(InboxMessageJpaRepository repository) {
        this.repository = repository;
    }

    // true = primeira vez (pode processar); false = repetida (ignorar).
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean register(UUID messageId, String type, Instant consumedAt) {
        return repository.insertIfAbsent(messageId, type, consumedAt) == 1;
    }

    @Transactional(readOnly = true)
    public boolean contains(UUID messageId) {
        return repository.existsById(messageId);
    }
}
