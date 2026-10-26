package com.mercatus.inventory;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = {
        "inventory.reservation.ttl=PT0S",
        "inventory.reservation.expiry-interval-ms=3600000"
})
class ReservationExpiryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    ExpireReservationsUseCase expireReservations;

    private RestClient client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
    }

    @Test
    void expirationIsIdempotentAndDoesNotReleaseTwice() {
        RestClient client = client();
        String a = "A-" + UUID.randomUUID().toString().substring(0, 8);
        client.post().uri("/stock/" + a + "/receipts").body(Map.of("quantity", 5))
                .retrieve().toBodilessEntity();

        client.post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID().toString(),
                        "lines", List.of(Map.of("skuCode", a, "quantity", 3))))
                .retrieve().toBodilessEntity();

        int firstRun = expireReservations.expireDue();
        int secondRun = expireReservations.expireDue();

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isEqualTo(0);

        Map<String, Object> stock = client.get().uri("/stock/" + a).retrieve().body(Map.class);
        assertThat(((Number) stock.get("available")).longValue()).isEqualTo(5);
        assertThat(((Number) stock.get("reserved")).longValue()).isEqualTo(0);
    }
}
