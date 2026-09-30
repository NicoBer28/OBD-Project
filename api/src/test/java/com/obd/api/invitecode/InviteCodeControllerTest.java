package com.obd.api.invitecode;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.group.GroupRole;
import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitation.exception.NotAMemberException;
import com.obd.api.invitation.exception.NotAnAdminException;
import com.obd.api.invitecode.dto.InviteCodeDTO;
import com.obd.api.invitecode.exception.InviteCodeNoLongerValidException;
import com.obd.api.invitecode.exception.InviteCodeNotFoundException;
import com.obd.api.support.SliceSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the QR invite endpoints. See {@link InviteCodeServiceTest}
 * for the rules underneath and {@link InviteCodeRepositoryTest} for the
 * database side.
 */
@WebMvcTest(controllers = InviteCodeController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class InviteCodeControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID GROUP_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String CODE = "kJ8xQv7sT2nR4mW9pL1yB6zC3dF5gH0jK8nM2qS4tV6";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InviteCodeService inviteCodeService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    // --- POST /groups/{id}/invite-code ---------------------------------------

    @Test
    void mintReturns201WithTheCodeAndNoLocation() throws Exception {
        given(inviteCodeService.mint(eq(CALLER_ID), eq(GROUP_ID), any()))
                .willReturn(new InviteCodeDTO.Minted(CODE, "https://obd-c.app/join/" + CODE,
                        Instant.parse("2026-09-25T18:00:00Z"), null, true));

        mockMvc.perform(post("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                // Nothing serves this resource again - the code is not stored.
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.joinUrl").value("https://obd-c.app/join/" + CODE))
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andExpect(jsonPath("$.replacedPrevious").value(true));
    }

    @Test
    void mintWorksWithNoBodyAtAll() throws Exception {
        given(inviteCodeService.mint(eq(CALLER_ID), eq(GROUP_ID), any()))
                .willReturn(new InviteCodeDTO.Minted(CODE, "url",
                        Instant.parse("2026-09-25T18:00:00Z"), null, false));

        // The defaults - a day, unlimited - are the common case.
        mockMvc.perform(post("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isCreated());
    }

    @Test
    void mintRejectsAnAbsurdTtlOrUseLimit() throws Exception {
        mockMvc.perform(post("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ttlHours": 0, "maxUses": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.ttlHours").exists())
                .andExpect(jsonPath("$.errors.maxUses").exists());

        verifyNoInteractions(inviteCodeService);
    }

    @Test
    void mintMapsANonAdminTo403AndANonMemberTo404() throws Exception {
        willThrow(new NotAnAdminException(GROUP_ID))
                .given(inviteCodeService).mint(eq(CALLER_ID), eq(GROUP_ID), any());

        mockMvc.perform(post("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Not an Admin of the Group"));

        willThrow(new NotAMemberException(CALLER_ID, GROUP_ID))
                .given(inviteCodeService).mint(eq(CALLER_ID), eq(GROUP_ID), any());

        mockMvc.perform(post("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isNotFound());
    }

    // --- GET /groups/{id}/invite-code ----------------------------------------

    @Test
    void currentReturns200WithMetadataAndNeverTheCode() throws Exception {
        given(inviteCodeService.current(CALLER_ID, GROUP_ID)).willReturn(Optional.of(
                new InviteCodeDTO.Read(UUID.randomUUID(),
                        Instant.parse("2026-09-24T18:00:00Z"),
                        Instant.parse("2026-09-25T18:00:00Z"), 3, 5, 2)));

        mockMvc.perform(get("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses").value(3))
                .andExpect(jsonPath("$.maxUses").value(5))
                .andExpect(jsonPath("$.remainingUses").value(2))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void currentReturns204WhenTheGroupHasNoLiveCode() throws Exception {
        given(inviteCodeService.current(CALLER_ID, GROUP_ID)).willReturn(Optional.empty());

        // The usual state of a group, so not an error - and 404 keeps its one
        // meaning here, "no such group".
        mockMvc.perform(get("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // --- DELETE /groups/{id}/invite-code -------------------------------------

    @Test
    void revokeReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/groups/{id}/invite-code", GROUP_ID).with(caller()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(inviteCodeService).revoke(CALLER_ID, GROUP_ID);
    }

    // --- GET /invite-codes/{code} --------------------------------------------

    @Test
    void previewReturnsTheGroupBehindTheCode() throws Exception {
        given(inviteCodeService.preview(CODE)).willReturn(new InviteCodeDTO.Preview(
                GROUP_ID, "Familia Lazzari", 3, Instant.parse("2026-09-25T18:00:00Z")));

        mockMvc.perform(get("/api/v1/invite-codes/{code}", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.groupName").value("Familia Lazzari"))
                .andExpect(jsonPath("$.memberCount").value(3));
    }

    @Test
    void previewMapsAnUnknownCodeTo404AndADeadOneTo409() throws Exception {
        willThrow(new InviteCodeNotFoundException()).given(inviteCodeService).preview("garbage");

        mockMvc.perform(get("/api/v1/invite-codes/{code}", "garbage"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such invite code"));

        willThrow(new InviteCodeNoLongerValidException(UUID.randomUUID()))
                .given(inviteCodeService).preview(CODE);

        mockMvc.perform(get("/api/v1/invite-codes/{code}", CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That invite code is no longer valid"));
    }

    // --- POST /invite-codes/{code}/join --------------------------------------

    @Test
    void joinReturnsTheGroupTheCallerJustJoined() throws Exception {
        given(inviteCodeService.join(CALLER_ID, CODE)).willReturn(new GroupDTO.Read(
                GROUP_ID, "Familia Lazzari", Instant.parse("2026-09-08T12:00:00Z"),
                4, GroupRole.MEMBER));

        mockMvc.perform(post("/api/v1/invite-codes/{code}/join", CODE).with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.name").value("Familia Lazzari"))
                .andExpect(jsonPath("$.memberCount").value(4))
                .andExpect(jsonPath("$.callerRole").value("MEMBER"));
    }

    @Test
    void joinTakesTheUserFromThePrincipalNotTheBody() throws Exception {
        given(inviteCodeService.join(CALLER_ID, CODE)).willReturn(new GroupDTO.Read(
                GROUP_ID, "Familia Lazzari", Instant.parse("2026-09-08T12:00:00Z"),
                4, GroupRole.MEMBER));

        // The stub only matches CALLER_ID, so naming someone else cannot
        // enrol them - joining is the scanner's own consent.
        mockMvc.perform(post("/api/v1/invite-codes/{code}/join", CODE).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "99999999-9999-9999-9999-999999999999"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void joinMapsADeadCodeToConflict() throws Exception {
        willThrow(new InviteCodeNoLongerValidException(UUID.randomUUID()))
                .given(inviteCodeService).join(CALLER_ID, CODE);

        mockMvc.perform(post("/api/v1/invite-codes/{code}/join", CODE).with(caller()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That invite code is no longer valid"));
    }
}
