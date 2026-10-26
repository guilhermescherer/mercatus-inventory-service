package com.mercatus.inventory.infrastructure.persistence.reservation;

import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationLine;

import java.util.ArrayList;
import java.util.List;

final class ReservationMapper {

    private ReservationMapper() {
    }

    static ReservationEntity toEntity(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity();
        entity.setId(reservation.id());
        entity.setOrderId(reservation.orderId());
        entity.setStatus(reservation.status());
        entity.setCreatedAt(reservation.createdAt());
        entity.setExpiresAt(reservation.expiresAt());
        List<ReservationLineEmbeddable> lines = reservation.lines().stream()
                .map(l -> new ReservationLineEmbeddable(l.skuCode(), l.quantity()))
                .toList();
        entity.setLines(new ArrayList<>(lines));
        return entity;
    }

    static Reservation toDomain(ReservationEntity entity) {
        List<ReservationLine> lines = entity.getLines().stream()
                .map(l -> new ReservationLine(l.getSkuCode(), l.getQuantity()))
                .toList();
        return Reservation.reconstitute(
                entity.getId(),
                entity.getOrderId(),
                lines,
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getExpiresAt());
    }
}
