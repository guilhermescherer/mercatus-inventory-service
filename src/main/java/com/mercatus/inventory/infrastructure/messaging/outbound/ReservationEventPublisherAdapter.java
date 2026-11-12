package com.mercatus.inventory.infrastructure.messaging.outbound;

import com.mercatus.inventory.application.reservation.port.out.ReservationEventPublisher;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.infrastructure.messaging.config.MessagingProperties;
import com.mercatus.inventory.infrastructure.messaging.contract.OutboundMessageType;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReservationExpiredPayload;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter.OutgoingMessage;
import org.springframework.stereotype.Component;

@Component
class ReservationEventPublisherAdapter implements ReservationEventPublisher {

    private final OutboxWriter outbox;
    private final MessagingProperties properties;

    ReservationEventPublisherAdapter(OutboxWriter outbox, MessagingProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    public void expired(Reservation reservation) {
        String orderId = reservation.orderId().toString();

        outbox.enqueue(properties.topics().events(), new OutgoingMessage(OutboundMessageType.RESERVATION_EXPIRED,
                orderId, reservation.correlationId().orElse(orderId), null,
                new ReservationExpiredPayload(reservation.orderId(), reservation.id(), reservation.expiresAt())));
    }
}
