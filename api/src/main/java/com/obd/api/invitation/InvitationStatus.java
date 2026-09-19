package com.obd.api.invitation;

/**
 * Derived from the invitation's timestamps, never stored - see
 * {@link Invitation#getStatus()}.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED
}
