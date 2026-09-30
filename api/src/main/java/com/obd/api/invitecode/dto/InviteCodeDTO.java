package com.obd.api.invitecode.dto;

import com.obd.api.invitecode.InviteCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

public class InviteCodeDTO {

    /**
     * Both knobs are optional: the defaults - a day, unlimited uses - are the
     * family case, everyone scanning one QR at dinner.
     */
    public record Create(
            @Min(1) @Max(168) Integer ttlHours,
            @Positive Integer maxUses
    ) {}

    /**
     * The mint response, and the only time the code leaves the server: only
     * its hash is stored, so this cannot be produced again.
     */
    public record Minted(
            String code,
            String joinUrl,
            Instant expiresAt,
            Integer maxUses,
            // Whether a previous code was killed to make room for this one -
            // the cue for "the QR you printed no longer works".
            boolean replacedPrevious
    ) {}

    /** The admin's view of the live code. Never carries the code itself. */
    public record Read(
            UUID id,
            Instant createdAt,
            Instant expiresAt,
            int uses,
            Integer maxUses,
            Integer remainingUses
    ) {
        public static Read from(InviteCode c) {
            return new Read(c.getInviteCodeId(), c.getInviteCodeCreatedAt(),
                    c.getInviteCodeExpiresAt(), c.getInviteCodeUses(),
                    c.getInviteCodeMaxUses(), c.getRemainingUses());
        }
    }

    /**
     * What a scanner sees before deciding to join. {@code groupId} is included
     * so the app can spot a group the user is already in without a second
     * request; it is useless on its own, since every group endpoint checks
     * membership.
     */
    public record Preview(
            UUID groupId,
            String groupName,
            long memberCount,
            Instant expiresAt
    ) {}
}
