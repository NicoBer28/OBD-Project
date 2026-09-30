package com.obd.api.invitecode;

import com.obd.api.group.*;
import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitecode.dto.InviteCodeDTO;
import com.obd.api.invitecode.exception.InviteCodeNoLongerValidException;
import com.obd.api.invitecode.exception.InviteCodeNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Group invite codes: mint, revoke, preview, join.
 *
 * Two invariants hold across every method here:
 * <ul>
 *   <li>only {@link #join} ever spends a use - previewing a code, or minting
 *       one, never does;</li>
 *   <li>nothing here changes an existing member's role. A code always grants
 *       {@code MEMBER}, and someone who is already in the group is left
 *       exactly as they are.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InviteCodeService {

    /** 32 bytes: guessing is not a threat model, so nothing rate-limits this. */
    private static final int CODE_BYTES = 32;

    private final InviteCodeRepository inviteCodeRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupAccess groupAccess;

    private final SecureRandom random = new SecureRandom();

    @Value("${app.invite.code-ttl-hours:24}")
    private int defaultTtlHours;

    /** Base of the deep link put in the QR; the code is appended as a path segment. */
    @Value("${app.invite.join-url-base:http://localhost:5173/join}")
    private String joinUrlBase;

    /**
     * Mints a code for the group, replacing whatever it had.
     *
     * Admin-only: a member may drive the cars, but deciding who else gets in
     * is the admin's call - the same split as pairing a dongle.
     *
     * The old code is revoked first, in this transaction, because
     * {@code ux_gic_one_live_per_group} allows only one unrevoked row per
     * group; that is also the point, since it means showing a new QR kills
     * the printed one.
     */
    @Transactional
    public InviteCodeDTO.Minted mint(UUID userId, UUID groupId, InviteCodeDTO.Create request) {
        groupAccess.requireAdmin(userId, groupId);

        Instant now = Instant.now();
        int ttlHours = request != null && request.ttlHours() != null ? request.ttlHours() : defaultTtlHours;
        Integer maxUses = request == null ? null : request.maxUses();

        boolean replacedPrevious = inviteCodeRepository.revokeLiveFor(groupId, now) > 0;

        String code = newCode();
        InviteCode saved = inviteCodeRepository.saveAndFlush(InviteCode.builder()
                .inviteCodeGroupId(groupId)
                .inviteCodeHash(sha256(code))
                .inviteCodeCreatedBy(userId)
                .inviteCodeCreatedAt(now)
                .inviteCodeExpiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .inviteCodeMaxUses(maxUses)
                .build());

        return new InviteCodeDTO.Minted(code, joinUrl(code),
                saved.getInviteCodeExpiresAt(), saved.getInviteCodeMaxUses(), replacedPrevious);
    }

    /**
     * The group's usable code, for the admin screen. Empty is the normal
     * answer - most groups have no code most of the time - so the controller
     * turns it into 204 rather than an error.
     */
    @Transactional
    public Optional<InviteCodeDTO.Read> current(UUID userId, UUID groupId) {
        groupAccess.requireAdmin(userId, groupId);

        return inviteCodeRepository.findByInviteCodeGroupIdAndInviteCodeRevokedAtIsNull(groupId)
                .filter(c -> c.isUsableAt(Instant.now()))
                .map(InviteCodeDTO.Read::from);
    }

    /**
     * Kills the group's code. Idempotent: revoking when there is nothing to
     * revoke is a success, as unsharing an unshared car is.
     *
     * Members who already joined are untouched - a code is how you got in,
     * never what keeps you in.
     */
    @Transactional
    public void revoke(UUID userId, UUID groupId) {
        groupAccess.requireAdmin(userId, groupId);

        inviteCodeRepository.revokeLiveFor(groupId, Instant.now());
    }

    /**
     * What the scanner sees before committing: which group this is, and how
     * big it is. Read-only on purpose - someone who scans, reads the name and
     * backs out must not have consumed a use.
     */
    @Transactional
    public InviteCodeDTO.Preview preview(String code) {
        InviteCode inviteCode = usable(code);

        Group group = groupRepository.findById(inviteCode.getInviteCodeGroupId())
                .orElseThrow(InviteCodeNotFoundException::new);

        return new InviteCodeDTO.Preview(
                group.getGroupId(),
                group.getGroupName(),
                groupMemberRepository.countByIdGroupId(group.getGroupId()),
                inviteCode.getInviteCodeExpiresAt());
    }

    /**
     * Joins the caller to the group the code belongs to, as {@code MEMBER}.
     *
     * Membership is checked <em>before</em> the code is spent, for two
     * reasons. Scanning the same QR twice is ordinary behaviour, so it must
     * not burn a second use; and {@code GroupMemberRepository.save} is an
     * upsert on an assigned composite key - writing MEMBER over the row of an
     * admin testing their own QR would silently demote them, and a group
     * whose last admin was demoted cannot be administered by anyone.
     *
     * Spending the use and the insert are one transaction: a crash between
     * them would consume a use without letting anybody in.
     */
    @Transactional
    public GroupDTO.Read join(UUID userId, String code) {
        InviteCode inviteCode = usable(code);
        UUID groupId = inviteCode.getInviteCodeGroupId();

        Optional<GroupMember> existing = groupAccess.memberOf(userId, groupId);
        if (existing.isPresent()) {
            return read(groupId, existing.get().getRole());
        }

        if (inviteCodeRepository.claim(sha256(code), Instant.now()) == 0) {
            // Lost a race for the last use of the code, or it was revoked
            // between the lookup above and here.
            throw new InviteCodeNoLongerValidException(inviteCode.getInviteCodeId());
        }

        groupMemberRepository.save(GroupMember.of(groupId, userId, GroupRole.MEMBER));

        return read(groupId, GroupRole.MEMBER);
    }

    /** Resolves a code to its row, or says why it cannot be used. */
    private InviteCode usable(String code) {
        InviteCode inviteCode = inviteCodeRepository.findByInviteCodeHash(sha256(code))
                .orElseThrow(InviteCodeNotFoundException::new);

        if (!inviteCode.isUsableAt(Instant.now())) {
            throw new InviteCodeNoLongerValidException(inviteCode.getInviteCodeId());
        }
        return inviteCode;
    }

    /** The group as the caller now sees it, so the app can navigate straight in. */
    private GroupDTO.Read read(UUID groupId, GroupRole callerRole) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(InviteCodeNotFoundException::new);

        return GroupDTO.Read.from(group, groupMemberRepository.countByIdGroupId(groupId), callerRole);
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        // URL-safe and unpadded: it goes in a path segment and in a QR.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String joinUrl(String code) {
        return joinUrlBase.endsWith("/") ? joinUrlBase + code : joinUrlBase + "/" + code;
    }

    /** Same construction as refresh tokens; the code itself is never stored. */
    static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
