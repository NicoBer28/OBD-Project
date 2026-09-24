package com.obd.api.group;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.group.dto.GroupDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping("/groups")
    public ResponseEntity<GroupDTO.Read> create(@AuthenticationPrincipal UserPrincipal principal, @RequestBody @Valid GroupDTO.Create request, UriComponentsBuilder uriBuilder) {
        GroupDTO.Read created = groupService.create(principal.getId(), request);

        URI location = uriBuilder.path("/api/v1/groups/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/groups")
    public List<GroupDTO.Read> groups(@AuthenticationPrincipal UserPrincipal principal){
        return groupService.getGroups(principal.getId());
    }

    @GetMapping("/groups/{id}/members")
    public List<GroupDTO.Member> members(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return groupService.members(principal.getId(), id);
    }

}
