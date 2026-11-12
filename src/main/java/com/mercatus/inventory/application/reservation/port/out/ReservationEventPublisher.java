package com.mercatus.inventory.application.reservation.port.out;

import com.mercatus.inventory.domain.reservation.Reservation;

public interface ReservationEventPublisher {

    void expired(Reservation reservation);
}
