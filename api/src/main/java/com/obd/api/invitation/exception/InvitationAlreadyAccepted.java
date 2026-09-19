package com.obd.api.invitation.exception;

import java.time.Instant;
import java.util.UUID;

public class InvitationAlreadyAccepted extends RuntimeException {

    private static final String MESSAGE = "Invitation, id: %s, already accepted at: %s";

    public InvitationAlreadyAccepted(UUID invitationId, Instant acceptedAt) {
        super(MESSAGE.formatted(invitationId,acceptedAt));
    }
}
