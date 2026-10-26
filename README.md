# mercatus-inventory-service

Serviço responsável por estoque físico, quantidades reservadas/comprometidas,
reservas por pedido e movimentos imutáveis de estoque.

## Execução local

Pré-requisitos: Java 25, Maven 3.9+, Docker com acesso ao daemon e acesso ao parent
`com.mercatus:mercatus-parent:1.0.0` no GitHub Packages ou no repositório Maven local.

O serviço usa Spring Boot 4.1, Spring MVC, JPA, Flyway, PostgreSQL 17 e springdoc.
Os testes usam JUnit Jupiter 6, AssertJ, ArchUnit e Testcontainers. A estrutura
segue Ports & Adapters: domínio puro em `domain`, casos de uso e portas em
`application`, persistência e scheduler em `infrastructure` e API em `web`.

Para preparar o parent localmente, a partir deste repositório:

```bash
mvn -version
mvn -f ../mercatus-parent/pom.xml install
```

O Maven precisa usar JDK 25; ajuste `JAVA_HOME` antes do build se necessário.
O Inventory ainda não possui `.mvn/settings.xml` próprio. Para consumir o parent
remoto na estrutura guarda-chuva, configure `MAVEN_USERNAME` e `MAVEN_PASSWORD`
e use o settings do Catalog explicitamente, por exemplo
`mvn -s ../mercatus-catalog-service/.mvn/settings.xml verify`.
As variáveis sozinhas não configuram o repositório. Consulte o
[README do parent](../mercatus-parent/README.md) para resolução e publicação.

Na pasta guarda-chuva, subir o PostgreSQL:

```bash
docker compose -f mercatus-platform/compose/docker-compose.yml --profile core up -d
```

O script de initdb do platform cria o database `inventory` em volumes novos.
Se o volume já existia antes desse database ser incluído, criar o database no
PostgreSQL existente antes de iniciar o serviço, preservando os demais bancos.
O [README do Platform](../mercatus-platform/README.md) contém os comandos para
conferir e criar databases ausentes e configurar o Compose. Aguarde o PostgreSQL
ficar saudável antes de iniciar a aplicação.

Dentro deste repositório:

```bash
mvn verify
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

O Maven deve estar usando Java 25 (`mvn -version`). Se necessário, selecionar
um JDK 25 instalado por `JAVA_HOME` e `PATH` antes de executar os comandos.
Os testes de integração usam containers próprios de PostgreSQL 17.

## Configuração

| Propriedade/variável | Padrão |
|---|---|
| `INVENTORY_DB_URL` | `jdbc:postgresql://localhost:5432/inventory` |
| `INVENTORY_DB_USER` | `mercatus` |
| `INVENTORY_DB_PASSWORD` | `mercatus` |
| `SERVER_PORT` | `8080` (usar porta própria ao executar vários serviços) |
| `inventory.reservation.ttl` | `PT30M` |
| `inventory.reservation.expiry-interval-ms` | `60000` |
| `inventory.reservation.expiry-batch-size` | `100` |

Propriedades Spring podem ser fornecidas por argumentos `--nome=valor`.
O perfil `local` desativa a saída estruturada de logs para facilitar a leitura.
A aplicação usa `/api/v1`, logs ECS, correlation ID e health do Actuator.
Com a porta `8083` do exemplo:

- OpenAPI: http://localhost:8083/api/v1/v3/api-docs
- Swagger UI: http://localhost:8083/api/v1/swagger-ui/index.html
- Health: http://localhost:8083/api/v1/actuator/health
- Ping: http://localhost:8083/api/v1/ping

Para usar o perfil `local`, acrescente `-Dspring-boot.run.profiles=local` ao
comando Maven. O filtro recebe ou gera `X-Correlation-Id` e o devolve na resposta.

## API

| Operação | Método e caminho (após `/api/v1`) | Sucesso |
|---|---|---|
| Entrada | `POST /stock/{skuCode}/receipts` | `202` |
| Consulta de saldo | `GET /stock/{skuCode}` | `200` |
| Ajuste | `POST /stock/{skuCode}/adjustments` | `204` |
| Criar reserva | `POST /reservations` | `201`, com `reservationId` |
| Confirmar | `POST /reservations/{id}/confirm` | `204` |
| Liberar | `POST /reservations/{id}/release` | `204` |
| Consumir | `POST /reservations/{id}/consume` | `204` |

```bash
curl -X POST http://localhost:8083/api/v1/stock/SHIRT-P/receipts \
  -H 'Content-Type: application/json' -d '{"quantity":10}'

curl -X POST http://localhost:8083/api/v1/stock/SHIRT-P/adjustments \
  -H 'Content-Type: application/json' -d '{"delta":-2,"reason":"Damaged units"}'

curl -X POST http://localhost:8083/api/v1/reservations \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"b4986ca0-a081-4531-bf53-06638881eeb2","lines":[{"skuCode":"SHIRT-P","quantity":3}]}'
```

`GET /api/v1/stock/{skuCode}` retorna `skuCode`, `onHand`, `reserved`,
`committed` e `available`. A criação de reserva recebe `orderId` e uma lista
não vazia de `lines`, cada uma com `skuCode` e `quantity` positiva; SKUs repetidos
são rejeitados. O retorno contém `reservationId`, usado nas ações de confirmar,
liberar e consumir. Repetir a criação para o mesmo pedido retorna o mesmo ID,
também com `201`, sem substituir os itens.

A entrada cria o saldo do SKU quando necessário. Reservas e ajustes exigem
saldo cadastrado no Inventory; não há consulta ao Catalog para validar o código.
Embora a entrada responda `202`, o processamento atual ocorre na requisição,
com transação no banco. Kafka, outbox e inbox entram na fase da saga.

Ajuste exige SKU existente, delta diferente de zero e motivo não vazio. Não pode
reduzir estoque disponível abaixo de zero. Erros usam Problem Details: validação
ou corpo inválido `400`, recurso inexistente `404`, conflito de persistência `409`
e violação de domínio `422`. Controle de acesso do operador entra na fase de segurança.

## Consistência e histórico

`available = onHand - reserved - committed`. Confirmar move reservado para
comprometido; liberar devolve disponibilidade; consumir reduz comprometido e
estoque físico. Apenas entrada, ajuste e consumo geram movimentos físicos:
`RECEIPT` positivo, `ADJUSTMENT` com sinal e motivo, `SHIPMENT` negativo.
A soma dos movimentos por SKU corresponde a `onHand`.

Transições bloqueiam primeiro a reserva, depois os SKUs em ordem crescente,
até o commit/rollback. Entrada e ajuste bloqueiam somente estoque. A criação
adquire atomicamente o `orderId` por inserção com tratamento de conflito antes de
alterar saldos. Chamadas repetidas retornam a reserva original, inclusive terminal,
sem substituir linhas. Saldo, movimento e estado são persistidos na mesma transação.

A expiração seleciona candidatos por vencimento/ID e usa uma transação por
reserva, revalidando estado e prazo sob lock. Cada execução tenta no máximo
`expiry-batch-size` candidatos. O cursor avança entre ciclos mesmo após falhas;
ao terminar a varredura, volta ao início e tenta novamente os pendentes. O limite
temporal fica fixo durante a varredura, para que novos vencimentos não adiem
indefinidamente essa retomada. O progresso fica em memória por instância e
reinicia ao subir a aplicação. Falhas são registradas e não revertem outras expirações.
A confirmação de pagamento ainda será integrada em fases posteriores; o TTL
precisará ser maior que o prazo de pagamento.

O lock pessimista foi escolhido após comparação já realizada pelo responsável
pelo projeto. A localização do relatório de latência ainda precisa ser vinculada;
esta entrega não repete o benchmark nem atribui resultados numéricos a ele.

## Schema inicial e reconciliação

O schema do Inventory está consolidado em `V1__inventory.sql`: estoque,
reservas, linhas e movimentos, com suas constraints e índices. Essa migration
inicializa um banco vazio. Os movimentos começam pelas operações da aplicação;
não há geração de saldo de abertura nem migração de schemas anteriores.

Para conferir a igualdade entre estoque físico e histórico:

```sql
SELECT s.sku_code, s.on_hand, COALESCE(SUM(m.quantity), 0) AS movement_balance
FROM stock_item s
LEFT JOIN stock_movement m ON m.sku_code = s.sku_code
GROUP BY s.sku_code, s.on_hand
HAVING s.on_hand <> COALESCE(SUM(m.quantity), 0);
```

O resultado deve ser vazio. A aplicação não oferece atualização/exclusão de
movimentos nem exclusão em cascata; `SHIPMENT` é único por reserva e SKU.

## Validação desta entrega

```bash
mvn test
mvn verify
```

`mvn test` executa os testes locais de domínio, aplicação, validação e
arquitetura; `mvn verify` também executa os testes `*IT` com PostgreSQL real.
Testcontainers cria containers próprios e não exige que o Compose esteja
iniciado. Este repositório ainda não possui workflow de CI próprio.

Após as melhorias da revisão em 2026-10-24, compilação e 61 testes de domínio,
aplicação, validação e arquitetura passaram com Java 25. `mvn clean verify`
chegou aos testes de integração, mas Testcontainers não conseguiu
acessar o socket Docker (`Operation not permitted`). Os testes de migrations,
SQL de locks e concorrência foram escritos e compilados; a execução com
PostgreSQL real permanece pendente. A fase 4 ainda não está declarada concluída.
Os resultados e as pendências estão nas
[evidências da entrega](../docs/specs/inventory-phase4/validation.md).
