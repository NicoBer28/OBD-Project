package com.obd.api.invitation;

import com.obd.api.group.GroupMember;
import com.obd.api.group.GroupMemberRepository;
import com.obd.api.group.GroupRole;
import com.obd.api.invitation.dto.InvitationDTO;
import com.obd.api.invitation.exception.*;
import com.obd.api.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    @Transactional
    public InvitationDTO.Read invite(UUID userId, String email, UUID groupID){
        Invitation invite = Invitation.builder()
                .invitationInvitedBy(userId)
                .invitationEmail(email.toLowerCase().trim())
                .invitationGroupId(groupID)
                .build();

        GroupMember groupMember = groupMemberRepository.findByIdGroupIdAndIdUserId(groupID, userId).orElseThrow(()-> new NotAMemberException(userId,groupID));
        if(groupMember.getRole() != GroupRole.ADMIN)
            throw new NotAnAdminException(groupID);

        GroupMember groupMember2 = groupMemberRepository.findByGroupIdAndUserEmail(groupID, email.trim().toLowerCase()).orElse(null);
        if(groupMember2 != null)
            throw new AlreadyAMemberException(email, groupID);

        Invitation save;

        try {
            save = invitationRepository.saveAndFlush(invite);
        }catch (DataIntegrityViolationException e){
            throw new FailedInvitationException(userId, email, groupID);
        }

        return InvitationDTO.Read.from(save);
    }

    @Transactional
    public InvitationDTO.Read accept(String email ,UUID invitationId){

        if(invitationRepository.accept(invitationId, email, Instant.now()) == 0){
            Invitation invitation = invitationRepository.findById(invitationId)
                    .filter(i -> i.getInvitationEmail().equals(email))
                    .orElseThrow(() -> new InvitationNotFoundException(invitationId, email));

            if(invitation.getInvitationAcceptedAt() != null) throw new InvitationAlreadyAccepted(invitationId, invitation.getInvitationAcceptedAt());
            throw new InvitationExpiredException(invitationId, invitation.getInvitationExpiresAt());
        }

        return InvitationDTO.Read.from(invitationRepository.findById(invitationId).orElse(new Invitation()));
    }

    @Transactional
    public List<InvitationDTO.Pending> pending(String email){
        return invitationRepository.findPendingFor(email, Instant.now());
    }
}
