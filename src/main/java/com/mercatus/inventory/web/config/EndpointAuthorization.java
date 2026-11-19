package com.mercatus.inventory.web.config;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class EndpointAuthorization {

    private static final String OPERATOR = "OPERATOR";

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth.requestMatchers(GET, "/stock/*").hasRole(OPERATOR)
                .requestMatchers(POST, "/stock/*/receipts", "/stock/*/adjustments").hasRole(OPERATOR)
                .requestMatchers(POST, "/reservations").hasRole(OPERATOR)
                .requestMatchers(POST, "/reservations/*/confirm", "/reservations/*/release", "/reservations/*/consume")
                .hasRole(OPERATOR);
    }
}
