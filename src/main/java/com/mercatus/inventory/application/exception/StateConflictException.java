package com.mercatus.inventory.application.exception;

public class StateConflictException extends RuntimeException {

    public StateConflictException(String message) {
        super(message);
    }
}
