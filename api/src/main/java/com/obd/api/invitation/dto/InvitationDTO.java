package com.obd.api.invitation.dto;

import com.obd.api.invitation.Invitation;
import com.obd.api.invitation.InvitationStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public class InvitationDTO {

    public record Create(
          @NotBlank @Email String email
    ){}

    public record Pending(
            UUID id,
            UUID groupId,
            String groupName,
            Instant invitationCreatedAt,
            Instant invitationExpiresAt
    ){}

    public record Read(
            UUID invitationId,
            UUID groupId,
            String invitationEmail,
            UUID invitationBy,
            Instant invitationCreatedAt,
            Instant invitationExpiresAt,
            InvitationStatus invitationStatus
    ){
        public static Read from(Invitation i){
            return new Read(i.getInvitationId(),
                    i.getInvitationGroupId(),
                    i.getInvitationEmail(),
                    i.getInvitationInvitedBy(),
                    i.getInvitationCreatedAt(),
                    i.getInvitationExpiresAt(),
                    i.getStatus()
                    );
        }

    }

}
