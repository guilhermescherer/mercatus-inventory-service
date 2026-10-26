package com.mercatus.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class StockMovementMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    Flyway flyway(String schema) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
    }

    Connection connection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    @Test
    void emptyDatabaseMigratesAndRejectsInvalidMovement() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = flyway(schema);
        flyway.migrate();
        flyway.validate();
        try (var connection = connection(schema); var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("select count(*) from stock_movement")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            statement.executeUpdate("insert into stock_item values ('SKU', 0, 0, 0, 0)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into stock_movement (id, sku_code, type, quantity, occurred_at)
                    values (gen_random_uuid(), 'SKU', 'ADJUSTMENT', 1, current_timestamp)
                    """)).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void repeatedMigrationPreservesBalancesReservationsAndMovements() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema).migrate();
        UUID reservation = UUID.randomUUID();
        try (var connection = connection(schema); var statement = connection.createStatement()) {
            statement.executeUpdate("insert into stock_item values ('SKU', 10, 0, 3, 4)");
            try (var insert = connection.prepareStatement("insert into reservation values (?, ?, 'CONFIRMED', current_timestamp, current_timestamp)")) {
                insert.setObject(1, reservation);
                insert.setObject(2, UUID.randomUUID());
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("insert into reservation_line values (?, 'SKU', 3)")) {
                insert.setObject(1, reservation);
                insert.executeUpdate();
            }
            statement.executeUpdate("""
                    insert into stock_movement (id, sku_code, type, quantity, occurred_at)
                    values (gen_random_uuid(), 'SKU', 'RECEIPT', 10, current_timestamp)
                    """);
        }
        var flyway = flyway(schema);
        flyway.migrate();
        flyway.validate();
        flyway.migrate();
        try (var connection = connection(schema); var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("select * from stock_movement")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("sku_code")).isEqualTo("SKU");
                assertThat(rs.getLong("quantity")).isEqualTo(10);
                assertThat(rs.getString("type")).isEqualTo("RECEIPT");
                assertThat(rs.getString("reason")).isNull();
                assertThat(rs.getObject("reservation_id")).isNull();
                assertThat(rs.next()).isFalse();
            }
            try (var rs = statement.executeQuery("select on_hand, reserved, committed, version from stock_item where sku_code = 'SKU'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(10);
                assertThat(rs.getLong(2)).isZero();
                assertThat(rs.getLong(3)).isEqualTo(3);
                assertThat(rs.getLong(4)).isEqualTo(4);
            }
            try (var rs = statement.executeQuery("select status from reservation")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("CONFIRMED");
            }
            assertThatThrownBy(() -> statement.executeUpdate("delete from stock_item where sku_code = 'SKU'"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
