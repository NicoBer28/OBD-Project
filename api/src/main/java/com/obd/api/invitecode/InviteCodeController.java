package com.obd.api.invitecode;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitecode.dto.InviteCodeDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InviteCodeController {

    private final InviteCodeService inviteCodeService;

    /**
     * 201 with the code - the only response that ever carries it. No Location:
     * the GET below returns metadata, never the code, so no URL serves this
     * resource again.
     */
    @PostMapping("/groups/{groupId}/invite-code")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteCodeDTO.Minted mint(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable UUID groupId,
                                     @RequestBody(required = false) @Valid InviteCodeDTO.Create request) {
        return inviteCodeService.mint(principal.getId(), groupId, request);
    }

    /** 200 with the live code's metadata, or 204 when the group has none. */
    @GetMapping("/groups/{groupId}/invite-code")
    public ResponseEntity<InviteCodeDTO.Read> current(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable UUID groupId) {
        return inviteCodeService.current(principal.getId(), groupId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/groups/{groupId}/invite-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID groupId) {
        inviteCodeService.revoke(principal.getId(), groupId);
    }

    /**
     * "What am I about to join?" - answered without a token, so someone who
     * scans before registering sees the group's name rather than a bare login
     * wall. The code is the capability; holding it is the authorisation.
     */
    @GetMapping("/invite-codes/{code}")
    public InviteCodeDTO.Preview preview(@PathVariable String code) {
        return inviteCodeService.preview(code);
    }

    /** 200 with the group the caller just joined, so the app can go straight in. */
    @PostMapping("/invite-codes/{code}/join")
    public GroupDTO.Read join(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String code) {
        return inviteCodeService.join(principal.getId(), code);
    }
}
