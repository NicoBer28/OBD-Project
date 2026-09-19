package com.obd.api.invitation.exception;

import java.util.UUID;

public class NotAMemberException extends RuntimeException {

    private static final String MESSAGE = "User id %s is not a member of group: %s";

    public NotAMemberException( UUID userId , UUID groupId) {
        super(MESSAGE.formatted(userId,groupId));
    }
}
