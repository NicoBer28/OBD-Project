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

@Service
@RequiredArgsConstructor
public class InviteCodeService {

    private static final int CODE_BYTES = 32;

    private final InviteCodeRepository inviteCodeRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupAccess groupAccess;

    private final SecureRandom random = new SecureRandom();

    @Value("${app.invite.code-ttl-hours:24}")
    private int defaultTtlHours;

    @Value("${app.invite.join-url-base:http://localhost:5173/join}")
    private String joinUrlBase;


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

    @Transactional
    public Optional<InviteCodeDTO.Read> current(UUID userId, UUID groupId) {
        groupAccess.requireAdmin(userId, groupId);

        return inviteCodeRepository.findByInviteCodeGroupIdAndInviteCodeRevokedAtIsNull(groupId)
                .filter(c -> c.isUsableAt(Instant.now()))
                .map(InviteCodeDTO.Read::from);
    }

    @Transactional
    public void revoke(UUID userId, UUID groupId) {
        groupAccess.requireAdmin(userId, groupId);

        inviteCodeRepository.revokeLiveFor(groupId, Instant.now());
    }

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


    @Transactional
    public GroupDTO.Read join(UUID userId, String code) {
        InviteCode inviteCode = usable(code);
        UUID groupId = inviteCode.getInviteCodeGroupId();

        Optional<GroupMember> existing = groupAccess.memberOf(userId, groupId);
        if (existing.isPresent()) {
            return read(groupId, existing.get().getRole());
        }

        if (inviteCodeRepository.claim(sha256(code), Instant.now()) == 0) {
            throw new InviteCodeNoLongerValidException(inviteCode.getInviteCodeId());
        }

        groupMemberRepository.save(GroupMember.of(groupId, userId, GroupRole.MEMBER));

        return read(groupId, GroupRole.MEMBER);
    }

    private InviteCode usable(String code) {
        InviteCode inviteCode = inviteCodeRepository.findByInviteCodeHash(sha256(code))
                .orElseThrow(InviteCodeNotFoundException::new);

        if (!inviteCode.isUsableAt(Instant.now())) {
            throw new InviteCodeNoLongerValidException(inviteCode.getInviteCodeId());
        }
        return inviteCode;
    }

    private GroupDTO.Read read(UUID groupId, GroupRole callerRole) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(InviteCodeNotFoundException::new);

        return GroupDTO.Read.from(group, groupMemberRepository.countByIdGroupId(groupId), callerRole);
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String joinUrl(String code) {
        return joinUrlBase.endsWith("/") ? joinUrlBase + code : joinUrlBase + "/" + code;
    }

    static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
