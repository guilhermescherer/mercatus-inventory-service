package com.mercatus.inventory.integration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercatus.inventory.support.TestTokens;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventorySecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) {
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ping", "/actuator/health", "/actuator/info", "/v3/api-docs"})
    void publicRoutesNeedNoToken(String path) {
        assertThat(call(HttpMethod.GET, path, null).status()).isEqualTo(200);
    }

    @Test
    void missingTokenIsRejectedWithProblemDetails() {
        Result result = call(HttpMethod.GET, "/stock/SKU-1", null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body()).contains("\"detail\":\"Authentication required\"");
        assertThat(result.wwwAuthenticate()).startsWith("Bearer");
        assertThat(result.correlationId()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void invalidTokensAreRejected(String description, String token) {
        Result result = call(HttpMethod.GET, "/stock/SKU-1", token);

        assertThat(result.status()).as(description).isEqualTo(401);
        assertThat(result.body()).as(description).contains("\"detail\":\"Authentication required\"");
        assertThat(result.wwwAuthenticate()).as(description).contains("invalid_token");
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                Arguments.of("malformed", TestTokens.malformed()),
                Arguments.of("unknown signing key", TestTokens.signedByUnknownKey()),
                Arguments.of("expired", TestTokens.expired()),
                Arguments.of("wrong issuer", TestTokens.wrongIssuer()));
    }

    @Test
    void forbiddenRoleIsRejectedWithProblemDetails() {
        Result result = call(HttpMethod.GET, "/stock/SKU-1", TestTokens.customer());

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.body()).contains("\"detail\":\"Access denied\"");
        assertThat(result.correlationId()).isNotBlank();
    }

    @Test
    void correlationIdIsEchoedOnRejection() {
        String correlationId = UUID.randomUUID().toString();
        String echoed = client.get().uri("/stock/SKU-1")
                .header("X-Correlation-Id", correlationId)
                .exchange((request, response) -> response.getHeaders().getFirst("X-Correlation-Id"));

        assertThat(echoed).isEqualTo(correlationId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE /stock/{id}", "GET /unmapped", "GET /reservations/{id}"})
    void routesWithoutRuleAreDenied(String route) {
        String[] parts = route.split(" ");
        Result result = call(HttpMethod.valueOf(parts[0]), parts[1].replace("{id}", UUID.randomUUID().toString()),
                TestTokens.admin());

        assertThat(result.status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0} {1} as {2} allowed={3}")
    @MethodSource("matrix")
    void rolesFollowEndpointMatrix(HttpMethod method, String path, String role, boolean allowed) {
        Result result = call(method, path, TestTokens.forRole(role));

        if (allowed) {
            assertThat(result.status()).isNotIn(401, 403);
        } else {
            assertThat(result.status()).isEqualTo(403);
        }
    }

    static Stream<Arguments> matrix() {
        String stock = "/stock/SKU-" + UUID.randomUUID();
        String reservation = "/reservations/" + UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.GET, stock, TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.GET, stock, TestTokens.ADMIN, true),
                Arguments.of(HttpMethod.GET, stock, TestTokens.CUSTOMER, false),
                Arguments.of(HttpMethod.GET, stock, TestTokens.SERVICE, false),
                Arguments.of(HttpMethod.POST, stock + "/receipts", TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.POST, stock + "/receipts", TestTokens.CUSTOMER, false),
                Arguments.of(HttpMethod.POST, stock + "/adjustments", TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.POST, stock + "/adjustments", TestTokens.SERVICE, false),
                Arguments.of(HttpMethod.POST, "/reservations", TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.POST, "/reservations", TestTokens.CUSTOMER, false),
                Arguments.of(HttpMethod.POST, "/reservations", TestTokens.SERVICE, false),
                Arguments.of(HttpMethod.POST, reservation + "/confirm", TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.POST, reservation + "/confirm", TestTokens.CUSTOMER, false),
                Arguments.of(HttpMethod.POST, reservation + "/release", TestTokens.ADMIN, true),
                Arguments.of(HttpMethod.POST, reservation + "/release", TestTokens.CUSTOMER, false),
                Arguments.of(HttpMethod.POST, reservation + "/consume", TestTokens.OPERATOR, true),
                Arguments.of(HttpMethod.POST, reservation + "/consume", TestTokens.SERVICE, false));
    }

    private Result call(HttpMethod method, String path, String token) {
        RestClient.RequestBodySpec request = client.method(method).uri(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token));
        }
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            request.contentType(MediaType.APPLICATION_JSON).body("{}");
        }
        return request.exchange((req, response) -> new Result(
                response.getStatusCode().value(),
                response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE),
                response.getHeaders().getFirst("X-Correlation-Id"),
                new String(response.getBody().readAllBytes())));
    }

    private record Result(int status, String contentType, String wwwAuthenticate, String correlationId,
            String body) {
    }
}
