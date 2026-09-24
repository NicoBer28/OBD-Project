package com.obd.api.invitation.exception;

import java.time.Instant;
import java.util.UUID;

public class InvitationExpiredException extends RuntimeException {

    private static final String MESSAGE = "Invitation, id: %s, expired at %s";

    public InvitationExpiredException(UUID invitationId, Instant expiredAt) {
        super(MESSAGE.formatted(invitationId, expiredAt));
    }
}
