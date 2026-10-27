package com.mercatus.inventory.domain.reservation;

import com.mercatus.inventory.domain.exception.DomainRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private Reservation reserved() {
        return Reservation.create(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new ReservationLine("SKU-A", 1), new ReservationLine("SKU-B", 2)),
                Duration.ofMinutes(30), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void createRequiresAtLeastOneLine() {
        assertThatThrownBy(() -> Reservation.create(UUID.randomUUID(), UUID.randomUUID(), List.of(),
                Duration.ofMinutes(30), Instant.now()))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void createRejectsDuplicateSku() {
        assertThatThrownBy(() -> Reservation.create(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new ReservationLine("SKU-A", 1), new ReservationLine("SKU-A", 2)),
                Duration.ofMinutes(30), Instant.now()))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void confirmFromReserved() {
        Reservation reservation = reserved();
        reservation.confirm();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void consumeRequiresConfirmed() {
        Reservation reservation = reserved();
        assertThatThrownBy(reservation::consume).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void releaseIsIdempotent() {
        Reservation reservation = reserved();
        reservation.release();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        reservation.release();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void expireOnlyFromReserved() {
        Reservation reservation = reserved();
        reservation.confirm();
        assertThatThrownBy(reservation::expire).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void isDueForExpirationWhenPastTtlAndReserved() {
        Reservation reservation = reserved();
        assertThat(reservation.isDueForExpiration(Instant.parse("2026-01-01T00:31:00Z"))).isTrue();
        assertThat(reservation.isDueForExpiration(Instant.parse("2026-01-01T00:10:00Z"))).isFalse();
    }
    @ParameterizedTest
    @CsvSource({
            "RESERVED, confirm, CONFIRMED",
            "CONFIRMED, confirm, CONFIRMED",
            "CONFIRMED, consume, CONSUMED",
            "CONSUMED, consume, CONSUMED",
            "RESERVED, release, RELEASED",
            "CONFIRMED, release, RELEASED",
            "RELEASED, release, RELEASED",
            "RESERVED, expire, EXPIRED",
            "EXPIRED, expire, EXPIRED"
    })
    void validTransitionsAndRepetitionsFollowTheStateTable(ReservationStatus initial, String action,
                                                         ReservationStatus expected) {
        Reservation reservation = inStatus(initial);
        apply(reservation, action);
        assertThat(reservation.status()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "RESERVED, consume",
            "CONFIRMED, expire",
            "CONSUMED, confirm",
            "CONSUMED, release",
            "CONSUMED, expire",
            "RELEASED, confirm",
            "RELEASED, consume",
            "RELEASED, expire",
            "EXPIRED, confirm",
            "EXPIRED, consume",
            "EXPIRED, release"
    })
    void incompatibleTransitionsAreRejectedWithoutChangingState(ReservationStatus initial, String action) {
        Reservation reservation = inStatus(initial);
        assertThatThrownBy(() -> apply(reservation, action)).isInstanceOf(DomainRuleException.class);
        assertThat(reservation.status()).isEqualTo(initial);
    }

    @Test
    void reservationIsDueExactlyAtItsDeadline() {
        Reservation reservation = reserved();
        assertThat(reservation.isDueForExpiration(reservation.expiresAt())).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
    void onlyReservedReservationsAreCandidatesForExpiration(ReservationStatus status) {
        Reservation reservation = inStatus(status);
        assertThat(reservation.isDueForExpiration(reservation.expiresAt().plusSeconds(1))).isFalse();
    }

    private Reservation inStatus(ReservationStatus status) {
        Reservation reservation = reserved();
        return Reservation.reconstitute(reservation.id(), reservation.orderId(), reservation.lines(),
                status, reservation.createdAt(), reservation.expiresAt());
    }

    private void apply(Reservation reservation, String action) {
        switch (action) {
            case "confirm" -> reservation.confirm();
            case "consume" -> reservation.consume();
            case "release" -> reservation.release();
            case "expire" -> reservation.expire();
            default -> throw new IllegalArgumentException("Unknown reservation action: " + action);
        }
    }
}
