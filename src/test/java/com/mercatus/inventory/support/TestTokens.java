package com.mercatus.inventory.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class TestTokens {

    public static final String ISSUER = "https://issuer.test/realms/mercatus";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";
    public static final String SERVICE = "SERVICE";

    private static final RSAKey KEY = generateKey();
    private static final RSAKey UNKNOWN_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer(KEY);

    private TestTokens() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    public static String jwkSetUri() {
        return "http://localhost:" + JWKS_SERVER.getAddress().getPort() + "/certs";
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String forRole(String role) {
        return ADMIN.equals(role) ? withRoles(ADMIN, OPERATOR) : withRoles(role);
    }

    public static String customer() {
        return forRole(CUSTOMER);
    }

    public static String operator() {
        return forRole(OPERATOR);
    }

    public static String admin() {
        return forRole(ADMIN);
    }

    public static String service() {
        return forRole(SERVICE);
    }

    public static String withRoles(String... roles) {
        return sign(KEY, claims(ISSUER, Instant.now().plus(Duration.ofHours(1)), roles));
    }

    public static String expired() {
        return sign(KEY, claims(ISSUER, Instant.now().minus(Duration.ofMinutes(5)), ADMIN, OPERATOR));
    }

    public static String wrongIssuer() {
        return sign(KEY, claims("https://other-issuer.test/realms/mercatus",
                Instant.now().plus(Duration.ofHours(1)), ADMIN, OPERATOR));
    }

    public static String signedByUnknownKey() {
        return sign(UNKNOWN_KEY, claims(ISSUER, Instant.now().plus(Duration.ofHours(1)), ADMIN, OPERATOR));
    }

    public static String malformed() {
        return "not-a-jwt";
    }

    private static JWTClaimsSet claims(String issuer, Instant expiresAt, String... roles) {
        Instant issuedAt = expiresAt.isAfter(Instant.now())
                ? Instant.now().minus(Duration.ofMinutes(1))
                : expiresAt.minus(Duration.ofMinutes(10));
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static HttpServer startJwksServer(RSAKey key) {
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
