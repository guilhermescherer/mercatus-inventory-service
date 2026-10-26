package com.mercatus.inventory.web.reservation;

import com.mercatus.inventory.application.reservation.port.in.ConfirmReservationUseCase;
import com.mercatus.inventory.application.reservation.port.in.ConsumeReservationUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReleaseReservationUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveUseCase;
import com.mercatus.inventory.web.reservation.dto.ReservationResponse;
import com.mercatus.inventory.web.reservation.dto.ReserveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReserveUseCase reserve;
    private final ConfirmReservationUseCase confirm;
    private final ReleaseReservationUseCase release;
    private final ConsumeReservationUseCase consume;

    public ReservationController(ReserveUseCase reserve, ConfirmReservationUseCase confirm,
                                 ReleaseReservationUseCase release, ConsumeReservationUseCase consume) {
        this.reserve = reserve;
        this.confirm = confirm;
        this.release = release;
        this.consume = consume;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse create(@Valid @RequestBody ReserveRequest request) {
        List<ReserveUseCase.Line> lines = request.lines().stream()
                .map(l -> new ReserveUseCase.Line(l.skuCode(), l.quantity()))
                .toList();
        UUID id = reserve.reserve(request.orderId(), lines);
        return new ReservationResponse(id);
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable UUID id) {
        confirm.confirm(id);
    }

    @PostMapping("/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id) {
        release.release(id);
    }

    @PostMapping("/{id}/consume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void consume(@PathVariable UUID id) {
        consume.consume(id);
    }
}
