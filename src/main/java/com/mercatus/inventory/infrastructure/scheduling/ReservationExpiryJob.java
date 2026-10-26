package com.mercatus.inventory.infrastructure.scheduling;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ExpireReservationsUseCase expireReservations;

    ReservationExpiryJob(ExpireReservationsUseCase expireReservations) {
        this.expireReservations = expireReservations;
    }

    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-interval-ms:60000}")
    void run() {
        int expired = expireReservations.expireDue();
        if (expired > 0) {
            log.info("Expired {} reservations", expired);
        }
    }
}
