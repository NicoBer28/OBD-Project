package com.obd.api.invitecode;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A group's QR code: a bearer capability to join it.
 *
 * The counterpart of {@link com.obd.api.invitation.Invitation}, which names an
 * email and is only acceptable by that account. This one names nobody - the
 * server learns who joined when they scan.
 *
 * Holds the SHA-256 of the code, never the code, as
 * {@code refresh_tokens.token_hash} does. The consequence is deliberate: the
 * code is displayable exactly once, at mint time.
 */
@Entity
@Table(name = "group_invite_codes")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "invite_code_id")
    private UUID inviteCodeId;

    // Raw ids rather than associations, as Invitation and Trip do: nothing in
    // the lifecycle of a code needs the group's name or the admin's.
    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID inviteCodeGroupId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String inviteCodeHash;

    /** Null once the admin who minted it deletes their account. */
    @Column(name = "created_by", updatable = false)
    private UUID inviteCodeCreatedBy;

    // @Builder ignores plain field initialisers, hence @Builder.Default.
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant inviteCodeCreatedAt = Instant.now();

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant inviteCodeExpiresAt;

    /**
     * Null while live. Written only by
     * {@link InviteCodeRepository#revokeLiveFor}, so revocation always goes
     * through the statement that can find the row by group.
     */
    @Column(name = "revoked_at")
    private Instant inviteCodeRevokedAt;

    /** Null = unlimited until expiry or revocation. */
    @Column(name = "max_uses", updatable = false)
    private Integer inviteCodeMaxUses;

    /**
     * Incremented only by {@link InviteCodeRepository#claim}, which checks
     * every condition in its WHERE clause - never read-modify-written here,
     * so two simultaneous scans of a single-use code cannot both win.
     */
    @Column(name = "uses", nullable = false)
    @Builder.Default
    private int inviteCodeUses = 0;

    /**
     * Whether the code may still be used, at the given instant. Derived from
     * the three columns rather than stored, so it cannot disagree with them -
     * the same reasoning as {@code Invitation.getStatus()}.
     */
    @Transient
    public boolean isUsableAt(Instant now) {
        return inviteCodeRevokedAt == null
                && now.isBefore(inviteCodeExpiresAt)
                && !isExhausted();
    }

    @Transient
    public boolean isExhausted() {
        return inviteCodeMaxUses != null && inviteCodeUses >= inviteCodeMaxUses;
    }

    /** Null when unlimited, mirroring {@code maxUses}. */
    @Transient
    public Integer getRemainingUses() {
        return inviteCodeMaxUses == null ? null : Math.max(0, inviteCodeMaxUses - inviteCodeUses);
    }
}
