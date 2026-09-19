package com.obd.api.model.exception;

import com.obd.api.model.Model;

public class ModelAlreadyExistsException extends RuntimeException {

    private static final String MESSAGE = "Model already exists for model: %s and brand: %s";

    public ModelAlreadyExistsException(Model model) {
        super(MESSAGE.formatted(model.getModelName(), model.getModelBrand()));
    }
}
