-- Outbox: mensagens a enviar ao Kafka, gravadas na mesma transação do dado de
-- negócio. published_at NULL = ainda não confirmada pelo Kafka.
CREATE TABLE outbox_message (
    id            UUID PRIMARY KEY,
    topic         VARCHAR(255) NOT NULL,
    aggregate_id  VARCHAR(255) NOT NULL,
    type          VARCHAR(255) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    published_at  TIMESTAMPTZ
);

-- Índice parcial: só as pendentes, que são as que o publicador procura.
CREATE INDEX idx_outbox_message_pending ON outbox_message (created_at, id) WHERE published_at IS NULL;

-- Inbox: ids das mensagens já processadas, para ignorar repetições.
CREATE TABLE inbox_message (
    message_id   UUID PRIMARY KEY,
    type         VARCHAR(255) NOT NULL,
    consumed_at  TIMESTAMPTZ NOT NULL
);
