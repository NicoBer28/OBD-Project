package com.obd.api.group;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitation.exception.NotAMemberException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the group endpoints: POST /groups, GET /groups and
 * GET /groups/{id}/members. See {@link GroupRepositoryTest} for the database
 * side and {@link GroupServiceTest} for the membership rules.
 */
@WebMvcTest(controllers = GroupController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class GroupControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID GROUP_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GroupService groupService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static GroupDTO.Read created() {
        return new GroupDTO.Read(GROUP_ID, "Familia Lazzari",
                Instant.parse("2026-09-08T12:00:00Z"), 1, GroupRole.ADMIN);
    }

    @Test
    void createReturns201WithTheGroupAndItsLocation() throws Exception {
        given(groupService.create(eq(CALLER_ID), any(GroupDTO.Create.class))).willReturn(created());

        mockMvc.perform(post("/api/v1/groups").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Familia Lazzari"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/api/v1/groups/" + GROUP_ID))
                .andExpect(jsonPath("$.id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.name").value("Familia Lazzari"))
                .andExpect(jsonPath("$.createdAt").exists())
                // The creator is enrolled as ADMIN, so a brand-new group has
                // exactly one member.
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.callerRole").value("ADMIN"));
    }

    @Test
    void createTakesTheCreatorFromThePrincipalNotTheBody() throws Exception {
        given(groupService.create(eq(CALLER_ID), any(GroupDTO.Create.class))).willReturn(created());

        // The stub only matches CALLER_ID, so naming someone else in the body
        // must not change who the group is created for.
        mockMvc.perform(post("/api/v1/groups").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Familia Lazzari",
                                  "creatorId": "99999999-9999-9999-9999-999999999999"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsABlankName() throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void createRejectsAnOverlongName() throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + "x".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // --- GET /groups ---------------------------------------------------------

    @Test
    void groupsListsTheCallersGroupsWithTheirRoleInEach() throws Exception {
        UUID other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        given(groupService.getGroups(CALLER_ID)).willReturn(List.of(
                created(),
                new GroupDTO.Read(other, "Los Lopez",
                        Instant.parse("2026-09-09T12:00:00Z"), 4, GroupRole.MEMBER)));

        mockMvc.perform(get("/api/v1/groups").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$[0].callerRole").value("ADMIN"))
                .andExpect(jsonPath("$[1].id").value(other.toString()))
                .andExpect(jsonPath("$[1].memberCount").value(4))
                .andExpect(jsonPath("$[1].callerRole").value("MEMBER"));
    }

    @Test
    void groupsIsAnEmptyListNotAnErrorForAUserInNoGroup() throws Exception {
        given(groupService.getGroups(CALLER_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/groups").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- GET /groups/{id}/members --------------------------------------------

    @Test
    void membersListsEveryMemberWithTheirRole() throws Exception {
        UUID grace = UUID.fromString("99999999-9999-9999-9999-999999999999");
        given(groupService.members(CALLER_ID, GROUP_ID)).willReturn(List.of(
                new GroupDTO.Member(CALLER_ID, "Ada", "ada@example.com", GroupRole.ADMIN),
                new GroupDTO.Member(grace, "Grace", "grace@example.com", GroupRole.MEMBER)));

        mockMvc.perform(get("/api/v1/groups/{id}/members", GROUP_ID).with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(CALLER_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Ada"))
                .andExpect(jsonPath("$[0].email").value("ada@example.com"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].userId").value(grace.toString()))
                .andExpect(jsonPath("$[1].role").value("MEMBER"));
    }

    @Test
    void membersIs404ForANonMemberSoTheGroupIsNotConfirmedToExist() throws Exception {
        given(groupService.members(CALLER_ID, GROUP_ID))
                .willThrow(new NotAMemberException(CALLER_ID, GROUP_ID));

        mockMvc.perform(get("/api/v1/groups/{id}/members", GROUP_ID).with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Not a Member of the Group"));
    }

    @Test
    void membersRejectsAMalformedGroupIdAs400Not500() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{id}/members", "not-a-uuid").with(caller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("'not-a-uuid' is not a valid value for 'id'"));

        verifyNoInteractions(groupService);
    }
}
