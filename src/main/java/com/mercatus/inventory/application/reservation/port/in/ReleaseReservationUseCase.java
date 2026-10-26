package com.mercatus.inventory.application.reservation.port.in;

import java.util.UUID;

public interface ReleaseReservationUseCase {

    void release(UUID reservationId);
}
