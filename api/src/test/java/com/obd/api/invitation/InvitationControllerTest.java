package com.obd.api.invitation;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.invitation.dto.InvitationDTO;
import com.obd.api.invitation.exception.*;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the three invitation routes. The service is mocked; see
 * {@link InvitationServiceTest} for the flow against the database.
 */
@WebMvcTest(controllers = InvitationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class InvitationControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CALLER_EMAIL = "ada@example.com";
    private static final UUID GROUP_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID INVITATION_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOON = Instant.parse("2026-09-15T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvitationService invitationService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, CALLER_EMAIL, "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static InvitationDTO.Read invitation(InvitationStatus status) {
        return new InvitationDTO.Read(INVITATION_ID, GROUP_ID, "new@example.com", CALLER_ID,
                NOON, NOON.plusSeconds(7 * 86_400), status);
    }

    // --- POST /invitations/invite/{groupId} ---------------------------------

    @Test
    void inviteReturnsThePendingInvitation() throws Exception {
        given(invitationService.invite(eq(CALLER_ID), eq("new@example.com"), eq(GROUP_ID)))
                .willReturn(invitation(InvitationStatus.PENDING));

        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$.groupId").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.invitationEmail").value("new@example.com"))
                .andExpect(jsonPath("$.invitationBy").value(CALLER_ID.toString()))
                .andExpect(jsonPath("$.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.invitationExpiresAt").exists());
    }

    @Test
    void inviteTakesTheInviterFromThePrincipalNotTheBody() throws Exception {
        given(invitationService.invite(eq(CALLER_ID), eq("new@example.com"), eq(GROUP_ID)))
                .willReturn(invitation(InvitationStatus.PENDING));

        // The stub matches CALLER_ID only; an invitedBy in the body is ignored.
        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@example.com",
                                 "invitedBy": "99999999-9999-9999-9999-999999999999"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void inviteRejectsAMissingOrMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void inviteMapsANonMemberToNotFound() throws Exception {
        willThrow(new NotAMemberException(CALLER_ID, GROUP_ID))
                .given(invitationService).invite(eq(CALLER_ID), eq("new@example.com"), eq(GROUP_ID));

        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inviteMapsANonAdminToForbidden() throws Exception {
        willThrow(new NotAnAdminException(GROUP_ID))
                .given(invitationService).invite(eq(CALLER_ID), eq("new@example.com"), eq(GROUP_ID));

        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inviteMapsAnExistingMemberToConflict() throws Exception {
        willThrow(new AlreadyAMemberException("new@example.com", GROUP_ID))
                .given(invitationService).invite(eq(CALLER_ID), eq("new@example.com"), eq(GROUP_ID));

        mockMvc.perform(post("/api/v1/invitations/invite/" + GROUP_ID).with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Already a Member of the Group"));
    }

    // --- GET /invitations/pending --------------------------------------------

    @Test
    void pendingListsInvitationsForThePrincipalsEmail() throws Exception {
        given(invitationService.pending(eq(CALLER_EMAIL))).willReturn(List.of(
                new InvitationDTO.Pending(INVITATION_ID, GROUP_ID, "Familia Lazzari",
                        NOON, NOON.plusSeconds(7 * 86_400))));

        // The email comes from the token; there is no way to ask for anyone
        // else's list.
        mockMvc.perform(get("/api/v1/invitations/pending").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$[0].groupName").value("Familia Lazzari"))
                .andExpect(jsonPath("$[0].invitationExpiresAt").exists());
    }

    @Test
    void pendingIsAnEmptyListNotAnError() throws Exception {
        given(invitationService.pending(eq(CALLER_EMAIL))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/invitations/pending").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- POST /invitations/{id}/accept --------------------------------------

    @Test
    void acceptReturnsTheAcceptedInvitation() throws Exception {
        given(invitationService.accept(eq(CALLER_ID), eq(CALLER_EMAIL), eq(INVITATION_ID)))
                .willReturn(invitation(InvitationStatus.ACCEPTED));

        mockMvc.perform(post("/api/v1/invitations/" + INVITATION_ID + "/accept").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$.invitationStatus").value("ACCEPTED"));
    }

    @Test
    void acceptMapsNotFoundAndNotYoursToNotFound() throws Exception {
        willThrow(new InvitationNotFoundException(INVITATION_ID, CALLER_EMAIL))
                .given(invitationService).accept(eq(CALLER_ID), eq(CALLER_EMAIL), eq(INVITATION_ID));

        mockMvc.perform(post("/api/v1/invitations/" + INVITATION_ID + "/accept").with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Invitation Not Found"));
    }

    @Test
    void acceptMapsAlreadyAcceptedToConflict() throws Exception {
        willThrow(new InvitationAlreadyAccepted(INVITATION_ID, NOON))
                .given(invitationService).accept(eq(CALLER_ID), eq(CALLER_EMAIL), eq(INVITATION_ID));

        mockMvc.perform(post("/api/v1/invitations/" + INVITATION_ID + "/accept").with(caller()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Invitation already accepted"));
    }

    @Test
    void acceptMapsExpiredToConflict() throws Exception {
        willThrow(new InvitationExpiredException(INVITATION_ID, NOON))
                .given(invitationService).accept(eq(CALLER_ID), eq(CALLER_EMAIL), eq(INVITATION_ID));

        mockMvc.perform(post("/api/v1/invitations/" + INVITATION_ID + "/accept").with(caller()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Invitation Expired"));
    }
}
