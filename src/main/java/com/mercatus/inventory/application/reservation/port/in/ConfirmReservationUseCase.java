package com.mercatus.inventory.application.reservation.port.in;

import java.util.UUID;

public interface ConfirmReservationUseCase {

    void confirm(UUID reservationId);
}
