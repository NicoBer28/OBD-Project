package com.obd.api.invitation.exception;

import java.util.UUID;

public class InvitationNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Invitation id: %s doesnt not exist for email: %s";

    public InvitationNotFoundException(UUID invitationId, String email) {
        super(MESSAGE.formatted(invitationId, email));
    }
}
