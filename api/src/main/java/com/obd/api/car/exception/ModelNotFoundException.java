package com.obd.api.car.exception;

import java.util.UUID;

public class ModelNotFoundException extends RuntimeException {

    private final static String MESSAGE = "No car model with id: %s";

    public ModelNotFoundException(UUID modelId) {
        super(MESSAGE.formatted(modelId));
    }
}
