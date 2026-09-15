package com.obd.api.invitation;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.invitation.dto.InvitationDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping("/invite/{groupId}")
    public InvitationDTO.Read invite(@AuthenticationPrincipal UserPrincipal principal,
                                     @RequestBody @Valid InvitationDTO.Create request,
                                     @PathVariable UUID groupId){
        return invitationService.invite(principal.getId(), request.email(), groupId);
    }

    @GetMapping("/pending")
    public List<InvitationDTO.Pending> pending(@AuthenticationPrincipal UserPrincipal principal){
        return invitationService.pending(principal.getEmail());
    }

    @PostMapping("/{id}/accept")
    @ResponseStatus(HttpStatus.OK)
    public InvitationDTO.Read accept(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable UUID id){
        return invitationService.accept(principal.getId() ,principal.getEmail() ,id);
    }
}
