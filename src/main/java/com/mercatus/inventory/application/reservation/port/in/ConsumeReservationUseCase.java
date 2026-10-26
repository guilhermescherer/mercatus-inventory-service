package com.mercatus.inventory.application.reservation.port.in;

import java.util.UUID;

public interface ConsumeReservationUseCase {

    void consume(UUID reservationId);
}
