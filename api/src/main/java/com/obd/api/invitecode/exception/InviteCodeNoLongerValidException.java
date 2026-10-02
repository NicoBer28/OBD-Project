package com.obd.api.invitecode.exception;

import java.util.UUID;

/**
 * The code exists but cannot be used any more: revoked, expired, or out of
 * uses. Told apart from "no such code" on purpose - only someone holding the
 * real code gets this far, and the two need different words in the app ("that
 * link is wrong" against "ask them for a new QR").
 */
public class InviteCodeNoLongerValidException extends RuntimeException {

    private static final String MESSAGE = "Invite code %s is no longer valid";

    public InviteCodeNoLongerValidException(UUID inviteCodeId) {
        super(MESSAGE.formatted(inviteCodeId));
    }
}
