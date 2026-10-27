package com.mercatus.inventory.domain.reservation;

import com.mercatus.inventory.domain.exception.DomainRuleException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Reservation {

    private final UUID id;
    private final UUID orderId;
    private final List<ReservationLine> lines;
    private ReservationStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;

    private Reservation(UUID id, UUID orderId, List<ReservationLine> lines,
                        ReservationStatus status, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.orderId = orderId;
        this.lines = List.copyOf(lines);
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static Reservation create(UUID id, UUID orderId, List<ReservationLine> lines, Duration ttl, Instant now) {
        if (orderId == null) {
            throw new DomainRuleException("Order ID is required");
        }
        if (ttl == null || ttl.isNegative()) {
            throw new DomainRuleException("Reservation TTL must not be negative");
        }
        if (lines == null || lines.isEmpty()) {
            throw new DomainRuleException("Reservation must have at least one line");
        }
        long distinctSkus = lines.stream().map(ReservationLine::skuCode).distinct().count();
        if (distinctSkus != lines.size()) {
            throw new DomainRuleException("Reservation must not repeat the same SKU");
        }
        return new Reservation(id, orderId, lines,
                ReservationStatus.RESERVED, now, now.plus(ttl));
    }

    public static Reservation reconstitute(UUID id, UUID orderId, List<ReservationLine> lines,
                                           ReservationStatus status, Instant createdAt, Instant expiresAt) {
        return new Reservation(id, orderId, lines, status, createdAt, expiresAt);
    }

    public void confirm() {
        if (status == ReservationStatus.CONFIRMED) {
            return;
        }
        requireStatus(ReservationStatus.RESERVED, "confirm");
        this.status = ReservationStatus.CONFIRMED;
    }

    public void consume() {
        if (status == ReservationStatus.CONSUMED) {
            return;
        }
        requireStatus(ReservationStatus.CONFIRMED, "consume");
        this.status = ReservationStatus.CONSUMED;
    }

    public void release() {
        if (status == ReservationStatus.RESERVED || status == ReservationStatus.CONFIRMED) {
            this.status = ReservationStatus.RELEASED;
            return;
        }
        if (status == ReservationStatus.RELEASED) {
            return;
        }
        throw new DomainRuleException("Cannot release a reservation in status " + status);
    }

    public void expire() {
        if (status == ReservationStatus.RESERVED) {
            this.status = ReservationStatus.EXPIRED;
            return;
        }
        if (status == ReservationStatus.EXPIRED) {
            return;
        }
        throw new DomainRuleException("Cannot expire a reservation in status " + status);
    }

    public boolean isDueForExpiration(Instant now) {
        return status == ReservationStatus.RESERVED && expiresAt != null && !now.isBefore(expiresAt);
    }

    private void requireStatus(ReservationStatus expected, String action) {
        if (status == expected) {
            return;
        }
        throw new DomainRuleException("Cannot " + action + " a reservation in status " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public List<ReservationLine> lines() {
        return lines;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
