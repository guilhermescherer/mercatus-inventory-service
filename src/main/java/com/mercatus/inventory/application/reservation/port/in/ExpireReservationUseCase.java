package com.mercatus.inventory.application.reservation.port.in;

import java.util.UUID;

public interface ExpireReservationUseCase {

    boolean expire(UUID reservationId);
}
