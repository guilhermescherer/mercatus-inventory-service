package com.mercatus.inventory.infrastructure.persistence.reservation;

import com.mercatus.inventory.domain.reservation.ReservationStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservation")
class ReservationEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reservation_line", joinColumns = @JoinColumn(name = "reservation_id"))
    private List<ReservationLineEmbeddable> lines = new ArrayList<>();

    protected ReservationEntity() {
    }

    UUID getId() {
        return id;
    }

    void setId(UUID id) {
        this.id = id;
    }

    UUID getOrderId() {
        return orderId;
    }

    void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    ReservationStatus getStatus() {
        return status;
    }

    void setStatus(ReservationStatus status) {
        this.status = status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    List<ReservationLineEmbeddable> getLines() {
        return lines;
    }

    void setLines(List<ReservationLineEmbeddable> lines) {
        this.lines = lines;
    }
}
