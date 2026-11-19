package com.mercatus.inventory.integration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercatus.inventory.support.TestTokens;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryIdentityProviderOutageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void unreachableIdentityProvider(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        String unreachable = "http://localhost:" + freePort() + "/certs";
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> unreachable);
    }

    @LocalServerPort
    int port;

    @Test
    void serviceStartsAndAnswersWithoutTokenWhileIdentityProviderIsDown() {
        int status = client().get().uri("/stock/SKU-1")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }

    @Test
    void tokenThatCannotBeVerifiedYieldsServiceUnavailable() {
        String[] result = client().get().uri("/stock/SKU-1")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.customer()))
                .exchange((request, response) -> new String[] {
                        String.valueOf(response.getStatusCode().value()),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getHeaders().getFirst("X-Correlation-Id"),
                        new String(response.getBody().readAllBytes())});

        assertThat(result[0]).isEqualTo("503");
        assertThat(result[1]).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result[2]).isNotBlank();
        assertThat(result[3]).contains("\"detail\":\"Identity provider unavailable\"");
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
