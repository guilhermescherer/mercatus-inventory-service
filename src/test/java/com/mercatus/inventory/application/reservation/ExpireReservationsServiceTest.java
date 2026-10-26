package com.mercatus.inventory.application.reservation;

import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.ExpireReservationsService;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationDeadline;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExpireReservationsServiceTest {

    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private final DeadlineRepository repository = new DeadlineRepository();
    private final MutableClock clock = new MutableClock(new AtomicReference<>(now), ZoneOffset.UTC);

    @Test
    void failureInAFullBatchDoesNotPreventTheNextCandidateFromExpiring() {
        var failed = repository.add(now.minusSeconds(120));
        var healthy = repository.add(now.minusSeconds(60));
        var service = new ExpireReservationsService(repository, id -> {
            if (id.equals(failed.reservationId())) {
                throw new IllegalStateException("Unavailable stock");
            }
            return repository.remove(id);
        }, clock, 1);

        assertThat(service.expireDue()).isZero();
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(repository.candidates).containsExactly(failed).doesNotContain(healthy);
    }

    @Test
    void completedScanReturnsToFailedCandidatesForAnotherAttempt() {
        repository.add(now.minusSeconds(60));
        var attempts = new AtomicInteger();
        var service = new ExpireReservationsService(repository, id -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Temporary failure");
            }
            return repository.remove(id);
        }, clock, 1);

        assertThat(service.expireDue()).isZero();
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(attempts).hasValue(2);
        assertThat(repository.candidates).isEmpty();
    }

    @Test
    void scanKeepsItsCutoffUntilTheNextSweep() {
        repository.add(now.minusSeconds(60));
        repository.add(now.minusSeconds(30));
        repository.add(now.plusSeconds(30));
        var service = new ExpireReservationsService(repository, repository::remove, clock, 1);

        assertThat(service.expireDue()).isEqualTo(1);
        Instant later = now.plusSeconds(60);
        clock.current.set(later);
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(repository.cutoffs).containsExactly(now, now);
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(repository.cutoffs).containsExactly(now, now, now, later);
        assertThat(repository.candidates).isEmpty();
    }

    @Test
    void anEmptyScanCanPickUpReservationsThatBecomeDueLater() {
        repository.add(now.plusSeconds(30));
        var service = new ExpireReservationsService(repository, repository::remove, clock, 1);

        assertThat(service.expireDue()).isZero();
        clock.current.set(now.plusSeconds(60));
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(repository.candidates).isEmpty();
    }

    private static final class DeadlineRepository implements ReservationRepository {

        private static final Comparator<ReservationDeadline> ORDER = Comparator
                .comparing(ReservationDeadline::expiresAt).thenComparing(ReservationDeadline::reservationId);
        private final List<ReservationDeadline> candidates = new ArrayList<>();
        private final List<Instant> cutoffs = new ArrayList<>();

        ReservationDeadline add(Instant expiresAt) {
            var candidate = new ReservationDeadline(UUID.randomUUID(), expiresAt);
            candidates.add(candidate);
            return candidate;
        }

        boolean remove(UUID id) {
            return candidates.removeIf(candidate -> candidate.reservationId().equals(id));
        }

        @Override
        public List<ReservationDeadline> findDueForExpiration(Instant cutoff, ReservationDeadline after, int limit) {
            cutoffs.add(cutoff);
            return candidates.stream()
                    .filter(candidate -> !candidate.expiresAt().isAfter(cutoff))
                    .filter(candidate -> after == null || ORDER.compare(candidate, after) > 0)
                    .sorted(ORDER).limit(limit).toList();
        }

        @Override
        public Reservation save(Reservation reservation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> findByOrderId(UUID orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> findByIdForUpdate(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryCreate(Reservation reservation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
