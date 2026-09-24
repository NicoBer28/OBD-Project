package com.obd.api.model;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.model.dto.ModelDTO;
import com.obd.api.model.exception.ModelAlreadyExistsException;
import com.obd.api.support.SliceSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the model catalog: GET /models for everyone, POST /models
 * for admins only. See {@link ModelServiceTest} for the database side.
 *
 * {@code @EnableMethodSecurity} normally lives on SecurityConfig, which the
 * slice excludes, so it is re-enabled here - otherwise {@code @PreAuthorize}
 * would be silently ignored and the admin check untested.
 */
@WebMvcTest(controllers = ModelController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import({SliceSecurityConfig.class, ModelControllerTest.MethodSecurity.class})
class ModelControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity {}

    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-4000-8000-000000000099");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelService modelService;

    private static RequestPostProcessor as(String role) {
        var principal = new UserPrincipal(UUID.randomUUID(), "someone@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static ModelDTO.Read punto() {
        return new ModelDTO.Read(MODEL_ID, "Fiat", "Punto", "ISO 9141-2");
    }

    // --- GET /models ---------------------------------------------------------

    @Test
    void getModelsListsTheCatalogForAnyUser() throws Exception {
        given(modelService.getModels()).willReturn(List.of(
                new ModelDTO.Read(UUID.randomUUID(), "Chevrolet", "Onix", "ISO 15765-4 (CAN)"),
                punto()));

        mockMvc.perform(get("/api/v1/models").with(as("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelBrand").value("Chevrolet"))
                .andExpect(jsonPath("$[1].modelId").value(MODEL_ID.toString()))
                .andExpect(jsonPath("$[1].modelBrand").value("Fiat"))
                .andExpect(jsonPath("$[1].modelName").value("Punto"))
                .andExpect(jsonPath("$[1].modelProtocol").value("ISO 9141-2"));
    }

    // --- POST /models --------------------------------------------------------

    @Test
    void createAnswers201ForAnAdmin() throws Exception {
        given(modelService.create(any(ModelDTO.Create.class))).willReturn(punto());

        mockMvc.perform(post("/api/v1/models").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelBrand": "Fiat", "modelName": "Punto", "modelProtocol": "ISO 9141-2"}
                                """))
                .andExpect(status().isCreated())
                // No GET /models/{id}, so no Location to point at.
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.modelId").value(MODEL_ID.toString()))
                .andExpect(jsonPath("$.modelBrand").value("Fiat"))
                .andExpect(jsonPath("$.modelName").value("Punto"));
    }

    @Test
    void createIsForbiddenForARegularUser() throws Exception {
        mockMvc.perform(post("/api/v1/models").with(as("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelBrand": "Fiat", "modelName": "Punto", "modelProtocol": "ISO 9141-2"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(modelService);
    }

    @Test
    void createMapsADuplicateToConflict() throws Exception {
        willThrow(new ModelAlreadyExistsException(
                Model.builder().modelBrand("Fiat").modelName("Punto").build()))
                .given(modelService).create(any(ModelDTO.Create.class));

        mockMvc.perform(post("/api/v1/models").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelBrand": "Fiat", "modelName": "Punto", "modelProtocol": "ISO 9141-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That model already exists"));
    }

    @Test
    void createRejectsMissingAndOverlongFields() throws Exception {
        mockMvc.perform(post("/api/v1/models").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelBrand\": \"" + "x".repeat(61) + "\", \"modelProtocol\": \"CAN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.modelBrand").exists())
                .andExpect(jsonPath("$.errors.modelName").exists());

        verifyNoInteractions(modelService);
    }
}
