package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationsUseCase;
import com.mercatus.inventory.application.reservation.port.in.ExpireReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.domain.reservation.ReservationDeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ExpireReservationsService implements ExpireReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireReservationsService.class);

    private final ReservationRepository reservationRepository;
    private final ExpireReservationUseCase expireReservation;
    private final Clock clock;
    private final int batchSize;
    private ReservationDeadline cursor;
    private Instant scanUntil;

    public ExpireReservationsService(ReservationRepository reservationRepository,
                                     ExpireReservationUseCase expireReservation, Clock clock,
                                     @Value("${inventory.reservation.expiry-batch-size:100}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Expiry batch size must be positive");
        }
        this.reservationRepository = reservationRepository;
        this.expireReservation = expireReservation;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized int expireDue() {
        int expired = 0;
        for (var candidate : nextBatch()) {
            cursor = candidate;
            try {
                if (expireReservation.expire(candidate.reservationId())) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to expire reservation {}", candidate.reservationId(), ex);
            }
        }
        return expired;
    }

    private List<ReservationDeadline> nextBatch() {
        if (cursor == null) {
            scanUntil = Instant.now(clock);
        }
        var candidates = reservationRepository.findDueForExpiration(scanUntil, cursor, batchSize);
        if (candidates.isEmpty() && cursor != null) {
            cursor = null;
            scanUntil = Instant.now(clock);
            return reservationRepository.findDueForExpiration(scanUntil, null, batchSize);
        }
        return candidates;
    }
}
