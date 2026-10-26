package com.mercatus.inventory.support;

import com.mercatus.inventory.web.reservation.dto.ReservationResponse;
import com.mercatus.inventory.web.stock.dto.StockResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public final class InventoryFixture {

    private final RestClient client;
    private final JdbcTemplate jdbc;

    public InventoryFixture(int port, JdbcTemplate jdbc) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
        this.jdbc = jdbc;
    }

    public RestClient client() {
        return client;
    }

    public String uniqueSkuCode() {
        return "SKU-" + UUID.randomUUID();
    }

    public void receive(String skuCode, long quantity) {
        var response = client.post().uri("/stock/" + skuCode + "/receipts")
                .body(Map.of("quantity", quantity)).retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    public String reserve(UUID orderId, String skuCode, long quantity) {
        var response = client.post().uri("/reservations")
                .body(Map.of("orderId", orderId,
                        "lines", List.of(Map.of("skuCode", skuCode, "quantity", quantity))))
                .retrieve().toEntity(ReservationResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().reservationId().toString();
    }

    public int transition(String reservationId, String action) {
        return client.post().uri("/reservations/" + reservationId + "/" + action)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    public int adjust(String skuCode, Object body) {
        return client.post().uri("/stock/" + skuCode + "/adjustments").body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    public StockResponse stock(String skuCode) {
        return client.get().uri("/stock/" + skuCode).retrieve().body(StockResponse.class);
    }

    public String reservationStatus(String reservationId) {
        return jdbc.queryForObject("select status from reservation where id = ?",
                String.class, UUID.fromString(reservationId));
    }

    public void assertMovementBalance(String skuCode) {
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity), 0) from stock_movement where sku_code = ?",
                Long.class, skuCode)).isEqualTo(stock(skuCode).onHand());
    }

    public void makeDue(String reservationId) {
        jdbc.update("update reservation set expires_at = current_timestamp - interval '1 minute' where id = ?",
                UUID.fromString(reservationId));
    }
}
