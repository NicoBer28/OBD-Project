package com.obd.api.user.exception;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    private static final String MESSAGE = "User of id: %s does not exist";

    public UserNotFoundException(UUID userId) {
        super(MESSAGE.formatted(userId));
    }
}
