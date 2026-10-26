package com.mercatus.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    private RestClient client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
    }

    private void seed(RestClient client, String sku, long qty) {
        client.post().uri("/stock/" + sku + "/receipts").body(Map.of("quantity", qty))
                .retrieve().toBodilessEntity();
    }

    private long available(RestClient client, String sku) {
        Map<String, Object> s = client.get().uri("/stock/" + sku).retrieve().body(Map.class);
        return ((Number) s.get("available")).longValue();
    }

    @Test
    void multiSkuReservationIsAllOrNothing() {
        RestClient client = client();
        String a = "A-" + UUID.randomUUID().toString().substring(0, 8);
        String b = "B-" + UUID.randomUUID().toString().substring(0, 8);
        seed(client, a, 5);
        seed(client, b, 1);

        HttpStatusCode status = client.post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID().toString(),
                        "lines", List.of(Map.of("skuCode", a, "quantity", 2),
                                Map.of("skuCode", b, "quantity", 5))))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status.value()).isEqualTo(422);
        assertThat(available(client, a)).isEqualTo(5);
        assertThat(available(client, b)).isEqualTo(1);
    }

    @Test
    void reserveConfirmConsumeFlow() {
        RestClient client = client();
        String a = "A-" + UUID.randomUUID().toString().substring(0, 8);
        seed(client, a, 10);

        Map<String, Object> created = client.post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID().toString(),
                        "lines", List.of(Map.of("skuCode", a, "quantity", 3))))
                .retrieve().body(Map.class);
        String id = (String) created.get("reservationId");
        assertThat(available(client, a)).isEqualTo(7);

        client.post().uri("/reservations/" + id + "/confirm").retrieve().toBodilessEntity();
        assertThat(available(client, a)).isEqualTo(7);

        client.post().uri("/reservations/" + id + "/consume").retrieve().toBodilessEntity();
        Map<String, Object> stock = client.get().uri("/stock/" + a).retrieve().body(Map.class);
        assertThat(((Number) stock.get("onHand")).longValue()).isEqualTo(7);
        assertThat(((Number) stock.get("available")).longValue()).isEqualTo(7);
    }

    @Test
    void releaseReturnsReservedToAvailable() {
        RestClient client = client();
        String a = "A-" + UUID.randomUUID().toString().substring(0, 8);
        seed(client, a, 4);

        Map<String, Object> created = client.post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID().toString(),
                        "lines", List.of(Map.of("skuCode", a, "quantity", 4))))
                .retrieve().body(Map.class);
        String id = (String) created.get("reservationId");
        assertThat(available(client, a)).isEqualTo(0);

        client.post().uri("/reservations/" + id + "/release").retrieve().toBodilessEntity();
        assertThat(available(client, a)).isEqualTo(4);

        client.post().uri("/reservations/" + id + "/release").retrieve().toBodilessEntity();
        assertThat(available(client, a)).isEqualTo(4);
    }

    @Test
    void reReservingSameOrderReturnsExistingReservation() {
        RestClient client = client();
        String a = "A-" + UUID.randomUUID().toString().substring(0, 8);
        seed(client, a, 5);
        String orderId = UUID.randomUUID().toString();

        Map<String, Object> first = client.post().uri("/reservations")
                .body(Map.of("orderId", orderId, "lines", List.of(Map.of("skuCode", a, "quantity", 1))))
                .retrieve().body(Map.class);
        Map<String, Object> second = client.post().uri("/reservations")
                .body(Map.of("orderId", orderId, "lines", List.of(Map.of("skuCode", a, "quantity", 1))))
                .retrieve().body(Map.class);

        assertThat(second.get("reservationId")).isEqualTo(first.get("reservationId"));
        assertThat(available(client, a)).isEqualTo(4);
    }
}
