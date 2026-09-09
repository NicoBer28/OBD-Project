package com.obd.api.group;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.group.dto.GroupDTO;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for POST /api/v1/groups. See {@link GroupRepositoryTest} for
 * the database side.
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
}
