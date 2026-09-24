package com.obd.api.invitation.exception;

import java.util.UUID;

public class FailedInvitationException extends RuntimeException {

    private static final String MESSAGE = "Invitation failed for user sender: %s, user receiver: %s for group: %s";

    public FailedInvitationException(UUID sender, String email, UUID groupId) {
        super(MESSAGE.formatted(sender, email, groupId));
    }
}
