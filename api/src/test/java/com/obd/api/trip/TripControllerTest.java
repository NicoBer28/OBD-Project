package com.obd.api.trip;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.devicetoken.DeviceAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.support.SliceSecurityConfig;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.trip.exception.CarAlreadyOnATripException;
import com.obd.api.trip.exception.TripAlreadyEndedException;
import com.obd.api.trip.exception.TripEndsBeforeItStartsException;
import com.obd.api.trip.exception.TripNotFoundException;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class, DeviceAuthFilter.class}))
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
    void startReturns201WithTheTrip() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class)))
                .willReturn(new TripService.Started(started(), true));

        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                // No Location: there is no GET /trips/{id} for it to point at.
                .andExpect(header().doesNotExist("Location"))
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
                .andExpect(jsonPath("$.distanceKm").doesNotExist())
                .andExpect(jsonPath("$.active").value(true));
    }

    // --- GET /trips, GET /cars/{id}/trips, GET /cars/{id}/trips/active ------

    @Test
    void tripsListsTheCallersTrips() throws Exception {
        given(tripService.getTrips(DRIVER_ID)).willReturn(List.of(started()));

        // Driver from the token; there is no way to ask for someone else's.
        mockMvc.perform(get("/api/v1/trips").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(TRIP_ID.toString()))
                .andExpect(jsonPath("$[0].driverId").value(DRIVER_ID.toString()));
    }

    @Test
    void tripsIsEmptyNotAnErrorForSomeoneWhoNeverDrove() throws Exception {
        given(tripService.getTrips(DRIVER_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/trips").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void carTripsListsTripsForThatCar() throws Exception {
        given(tripService.getCarTrips(DRIVER_ID, CAR_ID)).willReturn(List.of(started()));

        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/trips").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].carId").value(CAR_ID.toString()));
    }

    @Test
    void activeReturnsTheOpenTrip() throws Exception {
        given(tripService.active(DRIVER_ID, CAR_ID)).willReturn(Optional.of(started()));

        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/trips/active").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRIP_ID.toString()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void activeIs204WhenTheCarIsIdle() throws Exception {
        given(tripService.active(DRIVER_ID, CAR_ID)).willReturn(Optional.empty());

        // The usual state of a car. Not an error, and not a 404 - that one
        // means "no such car".
        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/trips/active").with(caller()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void activeMapsACarTheCallerMayNotSeeToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID)).given(tripService).active(DRIVER_ID, CAR_ID);

        // Same answer as for a car that does not exist - the id is not confirmed.
        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/trips/active").with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    // --- POST /trips/{id}/finish, DELETE /trips/{id} -----------------------------

    private static TripDTO.Read finished() {
        return new TripDTO.Read(TRIP_ID, CAR_ID, DRIVER_ID,
                Instant.parse("2026-09-08T12:00:00Z"), Instant.parse("2026-09-08T13:30:00Z"),
                Instant.parse("2026-09-08T12:00:00Z"), null,
                70, 52, 18, new BigDecimal("140.00"), false);
    }

    @Test
    void finishReturnsTheClosedTripWithItsExpense() throws Exception {
        given(tripService.finish(eq(DRIVER_ID), eq(TRIP_ID), any(TripDTO.finish.class))).willReturn(finished());

        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripFinalFuel\": 52, \"distanceKm\": 140}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.finalFuel").value(52))
                .andExpect(jsonPath("$.fuelUsed").value(18))
                .andExpect(jsonPath("$.distanceKm").value(140));
    }

    @Test
    void finishRejectsNegativeFuelAndZeroDistance() throws Exception {
        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripFinalFuel\": -1, \"distanceKm\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tripFinalFuel").exists())
                .andExpect(jsonPath("$.errors.distanceKm").exists());
    }

    @Test
    void finishPassesAClientSuppliedEndTimeThrough() throws Exception {
        given(tripService.finish(eq(DRIVER_ID), eq(TRIP_ID), any(TripDTO.finish.class))).willReturn(finished());

        // The phone is the only thing present when a trip ends, so it may say
        // when - a trip driven through a tunnel and uploaded two hours later
        // must not be recorded as having ended two hours late. The service
        // bounds the value; the controller only has to carry it.
        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tripFinalFuel": 52, "distanceKm": 140.75,
                                 "endedAt": "2026-09-08T13:30:00Z"}
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(TripDTO.finish.class);
        verify(tripService).finish(eq(DRIVER_ID), eq(TRIP_ID), captor.capture());
        assertThat(captor.getValue().endedAt()).isEqualTo(Instant.parse("2026-09-08T13:30:00Z"));
        // Decimals survive, which is the whole reason the type changed.
        assertThat(captor.getValue().distanceKm()).isEqualByComparingTo("140.75");
    }

    @Test
    void finishRejectsAThirdDecimalRatherThanRoundingIt() throws Exception {
        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tripFinalFuel": 52, "distanceKm": 140.755}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.distanceKm").exists());

        verifyNoInteractions(tripService);
    }

    @Test
    void finishMapsAnEndBeforeTheStartToBadRequest() throws Exception {
        willThrow(new TripEndsBeforeItStartsException(TRIP_ID,
                Instant.parse("2026-09-08T11:00:00Z"), Instant.parse("2026-09-08T12:00:00Z")))
                .given(tripService).finish(eq(DRIVER_ID), eq(TRIP_ID), any(TripDTO.finish.class));

        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endedAt": "2026-09-08T11:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The trip cannot end before it started"));
    }

    @Test
    void startAnswers200NotCreatedWhenTheClientRepeatsItsOwnTripId() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class)))
                .willReturn(new TripService.Started(started(), false));

        // A retry after a lost response. 200 is how the phone tells "this is
        // the trip I already created" from a genuine second start.
        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                 "clientTripId": "11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRIP_ID.toString()));
    }

    @Test
    void startCarriesTheClientsOwnFieldsThrough() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class)))
                .willReturn(new TripService.Started(started(), true));

        mockMvc.perform(post("/api/v1/trips").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                 "initialFuel": 70,
                                 "startedAt": "2026-09-08T12:00:00Z",
                                 "clientTripId": "11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(TripDTO.Create.class);
        verify(tripService).start(eq(DRIVER_ID), captor.capture());
        assertThat(captor.getValue().startedAt()).isEqualTo(Instant.parse("2026-09-08T12:00:00Z"));
        assertThat(captor.getValue().clientTripId())
                .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void routeReturnsThePositionsInOrder() throws Exception {
        given(tripService.route(DRIVER_ID, TRIP_ID)).willReturn(List.of(
                new TripDTO.RoutePoint(Instant.parse("2026-09-08T12:00:00Z"), -34.6037, -58.3816, 0),
                new TripDTO.RoutePoint(Instant.parse("2026-09-08T12:01:00Z"), -34.6040, -58.3820, 42)));

        mockMvc.perform(get("/api/v1/trips/" + TRIP_ID + "/route").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].latitude").value(-34.6037))
                .andExpect(jsonPath("$[1].speed").value(42));
    }

    @Test
    void routeMapsAnUnreadableTripToNotFound() throws Exception {
        willThrow(new TripNotFoundException(TRIP_ID)).given(tripService).route(DRIVER_ID, TRIP_ID);

        mockMvc.perform(get("/api/v1/trips/" + TRIP_ID + "/route").with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such trip"));
    }

    @Test
    void finishMapsNotFoundAndNotMineToNotFound() throws Exception {
        willThrow(new TripNotFoundException(TRIP_ID))
                .given(tripService).finish(eq(DRIVER_ID), eq(TRIP_ID), any(TripDTO.finish.class));

        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripFinalFuel\": 52, \"distanceKm\": 140}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such trip"));
    }

    @Test
    void finishMapsAlreadyEndedToConflict() throws Exception {
        willThrow(new TripAlreadyEndedException(TRIP_ID))
                .given(tripService).finish(eq(DRIVER_ID), eq(TRIP_ID), any(TripDTO.finish.class));

        mockMvc.perform(post("/api/v1/trips/" + TRIP_ID + "/finish").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripFinalFuel\": 52, \"distanceKm\": 140}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That trip has already ended"));
    }

    @Test
    void cancelAnswers204WithNoBody() throws Exception {
        mockMvc.perform(delete("/api/v1/trips/" + TRIP_ID).with(caller()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(tripService).delete(DRIVER_ID, TRIP_ID);
    }

    @Test
    void cancelMapsAnUnknownOrForeignTripToNotFound() throws Exception {
        willThrow(new TripNotFoundException(TRIP_ID)).given(tripService).delete(DRIVER_ID, TRIP_ID);

        mockMvc.perform(delete("/api/v1/trips/" + TRIP_ID).with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such trip"));
    }

    @Test
    void cancelMapsAFinishedTripToConflict() throws Exception {
        willThrow(new TripAlreadyEndedException(TRIP_ID)).given(tripService).delete(DRIVER_ID, TRIP_ID);

        mockMvc.perform(delete("/api/v1/trips/" + TRIP_ID).with(caller()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That trip has already ended"));
    }

    @Test
    void startTakesTheDriverFromThePrincipalNotTheBody() throws Exception {
        given(tripService.start(eq(DRIVER_ID), any(TripDTO.Create.class)))
                .willReturn(new TripService.Started(started(), true));

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
