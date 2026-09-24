package com.obd.api.car;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.dto.CarDTO;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.car.exception.LicensePlateAlreadyRegisteredException;
import com.obd.api.car.exception.ModelNotFoundException;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for POST /api/v1/cars. The service layer is mocked; see
 * {@link CarRepositoryTest} for the database side.
 *
 * The production chain is excluded because JwtAuthFilter drags in JwtService
 * and AppUserDetailsService, and {@link SliceSecurityConfig} stands in for it
 * so that {@code @AuthenticationPrincipal} still resolves.
 */
@WebMvcTest(controllers = CarController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class CarControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CAR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CarService carService;

    /** Puts a real UserPrincipal in the SecurityContext, as JwtAuthFilter would. */
    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(OWNER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static CarDTO.Read created() {
        return new CarDTO.Read(CAR_ID, "Ada's Gol", "AB123CD",
                new CarDTO.ModelRead(MODEL_ID, "Volkswagen", "Gol", "ISO 15765-4 (CAN)"),
                120_000, null, null, null, null, null, null);
    }

    private static final String VALID_BODY = """
            {
              "name": "Ada's Gol",
              "licensePlate": "AB123CD",
              "modelId": "00000000-0000-4000-8000-000000000001",
              "mileage": 120000
            }
            """;

    @Test
    void createReturns201WithTheCarAndItsLocation() throws Exception {
        given(carService.create(eq(OWNER_ID), any(CarDTO.Create.class))).willReturn(created());

        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/api/v1/cars/" + CAR_ID))
                .andExpect(jsonPath("$.id").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.name").value("Ada's Gol"))
                .andExpect(jsonPath("$.licensePlate").value("AB123CD"))
                .andExpect(jsonPath("$.model.brand").value("Volkswagen"))
                .andExpect(jsonPath("$.model.model").value("Gol"))
                .andExpect(jsonPath("$.mileage").value(120000))
                // A car that has never reported has no snapshot yet - and no
                // time for one either.
                .andExpect(jsonPath("$.fuelLevel").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.snapshotAt").doesNotExist());
    }

    @Test
    void createTakesTheOwnerFromThePrincipalNotTheBody() throws Exception {
        given(carService.create(eq(OWNER_ID), any(CarDTO.Create.class))).willReturn(created());

        // The body names a different owner; it must be ignored entirely, so the
        // stub above (which only matches OWNER_ID) is what has to be hit.
        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Ada's Gol",
                                  "modelId": "00000000-0000-4000-8000-000000000001",
                                  "ownerId": "99999999-9999-9999-9999-999999999999"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsAMissingNameAndModel() throws Exception {
        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  ", "mileage": -5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.modelId").exists())
                .andExpect(jsonPath("$.errors.mileage").exists());
    }

    @Test
    void createRejectsAnOverlongPlate() throws Exception {
        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Ada's Gol",
                                  "modelId": "00000000-0000-4000-8000-000000000001",
                                  "licensePlate": "THIS-PLATE-IS-FAR-TOO-LONG"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.licensePlate").exists());
    }

    @Test
    void carsReturns200WithTheCallersList() throws Exception {
        given(carService.getCars(OWNER_ID)).willReturn(List.of(created()));

        mockMvc.perform(get("/api/v1/cars").with(caller()))
                // 200, not 302 FOUND - that one is a redirect.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(CAR_ID.toString()))
                .andExpect(jsonPath("$[0].model.brand").value("Volkswagen"));
    }

    @Test
    void carsIsEmptyNotAnErrorForANewUser() throws Exception {
        given(carService.getCars(OWNER_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/cars").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- GET /cars/{id} ------------------------------------------------------

    @Test
    void carReturns200WithTheSameShapeAsTheList() throws Exception {
        given(carService.getCar(OWNER_ID, CAR_ID)).willReturn(created());

        mockMvc.perform(get("/api/v1/cars/" + CAR_ID).with(caller()))
                // 200, not 202 ACCEPTED - that one means "queued for later".
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.name").value("Ada's Gol"))
                .andExpect(jsonPath("$.model.brand").value("Volkswagen"))
                .andExpect(jsonPath("$.mileage").value(120_000));
    }

    @Test
    void carMapsACarTheCallerMayNotSeeToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID)).given(carService).getCar(OWNER_ID, CAR_ID);

        // Same answer as for a car that does not exist - the id is not confirmed.
        mockMvc.perform(get("/api/v1/cars/" + CAR_ID).with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    @Test
    void carRejectsAMalformedIdAs400Not500() throws Exception {
        mockMvc.perform(get("/api/v1/cars/not-a-uuid").with(caller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));

        then(carService).shouldHaveNoInteractions();
    }

    // --- sharing -------------------------------------------------------------

    private static final UUID GROUP_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static CarDTO.Read shared() {
        return new CarDTO.Read(CAR_ID, "Ada's Gol", "AB123CD",
                new CarDTO.ModelRead(MODEL_ID, "Volkswagen", "Gol", "ISO 15765-4 (CAN)"),
                120_000, null, null, null, null, null,
                new CarDTO.GroupRef(GROUP_ID, "Familia Lazzari"));
    }

    @Test
    void shareReturnsTheCarWithItsGroup() throws Exception {
        given(carService.share(eq(OWNER_ID), eq(CAR_ID), any(CarDTO.Share.class))).willReturn(shared());

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/group").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\": \"" + GROUP_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.group.id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.group.name").value("Familia Lazzari"));
    }

    @Test
    void shareRejectsAMissingGroupId() throws Exception {
        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/group").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.groupId").exists());
    }

    @Test
    void shareMapsACarThatIsNotMineToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID))
                .given(carService).share(eq(OWNER_ID), eq(CAR_ID), any(CarDTO.Share.class));

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/group").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\": \"" + GROUP_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    @Test
    void shareMapsAGroupIAmNotInToNotFound() throws Exception {
        willThrow(new NotAMemberException(OWNER_ID, GROUP_ID))
                .given(carService).share(eq(OWNER_ID), eq(CAR_ID), any(CarDTO.Share.class));

        // Not 403: a group the caller is not in must look like no group.
        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/group").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\": \"" + GROUP_ID + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unshareReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/cars/" + CAR_ID + "/group").with(caller()))
                .andExpect(status().isNoContent());

        then(carService).should().unshare(OWNER_ID, CAR_ID);
    }

    @Test
    void forGroupListsTheGroupsCars() throws Exception {
        given(carService.forGroup(OWNER_ID, GROUP_ID)).willReturn(List.of(shared()));

        mockMvc.perform(get("/api/v1/groups/" + GROUP_ID + "/cars").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].group.id").value(GROUP_ID.toString()));
    }

    @Test
    void forGroupMapsANonMemberToNotFound() throws Exception {
        willThrow(new NotAMemberException(OWNER_ID, GROUP_ID)).given(carService).forGroup(OWNER_ID, GROUP_ID);

        mockMvc.perform(get("/api/v1/groups/" + GROUP_ID + "/cars").with(caller()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createMapsAnUnknownModelToNotFound() throws Exception {
        willThrow(new ModelNotFoundException(MODEL_ID))
                .given(carService).create(eq(OWNER_ID), any(CarDTO.Create.class));

        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car model"));
    }

    @Test
    void createMapsADuplicatePlateToConflict() throws Exception {
        willThrow(new LicensePlateAlreadyRegisteredException("AB123CD"))
                .given(carService).create(eq(OWNER_ID), any(CarDTO.Create.class));

        mockMvc.perform(post("/api/v1/cars").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("You already have a car with that licence plate"));
    }
}
