package com.obd.api.invitation.exception;

import java.util.UUID;

public class AlreadyAMemberException extends RuntimeException {

    private static final String MESSAGE = "User, email: %s is already a member of group: %s";

    public AlreadyAMemberException(String email, UUID groupId) {
        super(MESSAGE.formatted(email, groupId));
    }
}
