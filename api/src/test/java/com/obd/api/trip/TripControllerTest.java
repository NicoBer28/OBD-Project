package com.obd.api.trip;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.support.SliceSecurityConfig;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.trip.exception.CarAlreadyOnATripException;
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
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for POST /api/v1/trips. The service layer is mocked; see
 * {@link TripRepositoryTest} for the database side.
 */
@WebMvcTest(controllers = TripController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class TripControllerTest {

    private static final UUID DRIVER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CAR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TRIP_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripService tripService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(DRIVER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    /** What the service returns for a trip that has just been started. */
    private static TripDTO.Read started() {
        return new TripDTO.Read(TRIP_ID, CAR_ID, DRIVER_ID,
                Instant.parse("2026-09-08T12:00:00Z"), null,
                70, null, null, null, true);
    }

    private static final String VALID_BODY = """
            {
              "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "initialFuel": 70
            }
            """;

    @Test
    void startReturns201WithTheTripAndItsLocation() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class))).willReturn(started());

        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/api/v1/trips/" + TRIP_ID))
                .andExpect(jsonPath("$.id").value(TRIP_ID.toString()))
                .andExpect(jsonPath("$.carId").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.driverId").value(DRIVER_ID.toString()))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.initialFuel").value(70))
                // A trip that has just started has no result yet, and its
                // consumption is not knowable until it is finished.
                .andExpect(jsonPath("$.endedAt").doesNotExist())
                .andExpect(jsonPath("$.finalFuel").doesNotExist())
                .andExpect(jsonPath("$.fuelUsed").doesNotExist())
                .andExpect(jsonPath("$.distance").doesNotExist())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void startTakesTheDriverFromThePrincipalNotTheBody() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class))).willReturn(started());

        // The stub only matches DRIVER_ID, so naming someone else in the body
        // must not change whose trip this is - and therefore who the fuel is
        // charged to.
        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                  "driverId": "99999999-9999-9999-9999-999999999999"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void startRejectsAMissingCarAndNegativeFuel() throws Exception {
        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initialFuel": -5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.carId").exists())
                .andExpect(jsonPath("$.errors.initialFuel").exists());
    }

    @Test
    void startMapsAnUnknownOrForeignCarToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID))
                .given(tripService).start(eq(DRIVER_ID), any(TripDTO.Create.class));

        // Someone else's car answers exactly like a car that does not exist -
        // a 403 here would confirm the id is real.
        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    @Test
    void startMapsACarThatIsAlreadyInUseToConflict() throws Exception {
        willThrow(new CarAlreadyOnATripException(CAR_ID))
                .given(tripService).start(eq(DRIVER_ID), any(TripDTO.Create.class));

        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That car is already on a trip"));
    }
}
