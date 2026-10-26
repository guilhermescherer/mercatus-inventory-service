package com.mercatus.inventory.web;

import com.mercatus.inventory.web.reservation.dto.ReservationLineRequest;
import com.mercatus.inventory.web.reservation.dto.ReserveRequest;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReserveRequestValidationTest {

    @Test
    void nullLineIsRejectedBeforeTheControllerMapsItsFields() {
        var request = new ReserveRequest(UUID.randomUUID(), Arrays.asList((ReservationLineRequest) null));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertThat(violations).hasSize(1).allSatisfy(violation -> {
                assertThat(violation.getPropertyPath().toString()).contains("lines");
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotNull.class);
            });
        }
    }
}
