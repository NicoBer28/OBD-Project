package com.obd.api.invitation.exception;

import java.util.UUID;

public class NotAnAdminException extends RuntimeException {

    private static final String MESSAGE = "Member is not an Admin for group: %s";

    public NotAnAdminException(UUID groupId) {
        super(MESSAGE.formatted(groupId));
    }
}
