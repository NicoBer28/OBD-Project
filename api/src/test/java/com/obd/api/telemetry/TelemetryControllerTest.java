package com.obd.api.telemetry;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.support.SliceSecurityConfig;
import com.obd.api.telemetry.dto.TelemetryDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for POST /api/v1/telemetry. The service is mocked; see
 * {@link TelemetryServiceTest} for ingestion against the database.
 */
@WebMvcTest(controllers = TelemetryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class TelemetryControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CAR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TRIP_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelemetryService telemetryService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static final String VALID_BODY = """
            {
              "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "readings": [
                {"recordedAt": "2026-09-10T12:00:00Z", "latitude": -34.6037, "longitude": -58.3816,
                 "speed": 60, "fuelLevel": 70, "batteryLevel": 85, "mileage": 120000,
                 "raw": {"pids": {"04": "5020"}}},
                {"recordedAt": "2026-09-10T12:00:05Z", "fuelLevel": 69, "raw": "01045020"}
              ]
            }
            """;

    @Test
    void ingestReturns200WithTheBatchSummary() throws Exception {
        given(telemetryService.ingest(eq(CALLER_ID), any(TelemetryDTO.Ingest.class)))
                .willReturn(new TelemetryDTO.Ingested(CAR_ID, 1, 1, TRIP_ID, true,
                        Instant.parse("2026-09-10T12:00:05Z")));

        mockMvc.perform(post("/api/v1/telemetry").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                // 200, not 201: an all-duplicate batch creates nothing, and
                // there is no single resource for a Location to point at.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carId").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.stored").value(1))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.tripId").value(TRIP_ID.toString()))
                .andExpect(jsonPath("$.snapshotUpdated").value(true))
                .andExpect(jsonPath("$.latestRecordedAt").value("2026-09-10T12:00:05Z"));
    }

    @Test
    void ingestTakesTheUploaderFromThePrincipal() throws Exception {
        given(telemetryService.ingest(eq(CALLER_ID), any(TelemetryDTO.Ingest.class)))
                .willReturn(new TelemetryDTO.Ingested(CAR_ID, 2, 0, null, true,
                        Instant.parse("2026-09-10T12:00:05Z")));

        // The stub only matches CALLER_ID; a userId in the body must be
        // ignored rather than honoured.
        mockMvc.perform(post("/api/v1/telemetry").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"readings\"",
                                "\"userId\": \"99999999-9999-9999-9999-999999999999\", \"readings\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").doesNotExist());
    }

    @Test
    void ingestRejectsAMissingCarAndAnEmptyBatch() throws Exception {
        mockMvc.perform(post("/api/v1/telemetry").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readings": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.carId").exists())
                .andExpect(jsonPath("$.errors.readings").exists());
    }

    @Test
    void ingestRejectsABadReadingAndSaysWhichOne() throws Exception {
        // Whole batch rejected: a malformed reading is a serialiser bug on the
        // phone, and half-applying a batch would leave its buffer state
        // unknowable. The index tells the client which reading to drop.
        mockMvc.perform(post("/api/v1/telemetry").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                  "readings": [
                                    {"recordedAt": "2026-09-10T12:00:00Z", "fuelLevel": 70},
                                    {"recordedAt": "2026-09-10T12:00:05Z", "latitude": 91.0, "longitude": 0.0},
                                    {"recordedAt": "2026-09-10T12:00:10Z", "latitude": -34.6},
                                    {"recordedAt": "2099-01-01T00:00:00Z", "speed": -1}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['readings[1].latitude']").exists())
                .andExpect(jsonPath("$.errors['readings[2].positionComplete']").exists())
                .andExpect(jsonPath("$.errors['readings[3].notFromTheFuture']").exists())
                .andExpect(jsonPath("$.errors['readings[3].speed']").exists())
                .andExpect(jsonPath("$.errors['readings[0].fuelLevel']").doesNotExist());
    }

    // --- GET /api/v1/telemetry ------------------------------------------------

    private static TelemetryDTO.Page onePage() {
        return new TelemetryDTO.Page(CAR_ID,
                List.of(new TelemetryDTO.Read(TRIP_ID,
                        Instant.parse("2026-09-10T12:00:00Z"), Instant.parse("2026-09-10T12:00:03Z"),
                        -34.6037, -58.3816, 60, 70, 85, 120_000,
                        "{\"pids\": {\"04\": \"5020\"}}")),
                Instant.parse("2026-09-10T12:00:00Z"), true);
    }

    @Test
    void historyReturnsAPageWithItsCursor() throws Exception {
        given(telemetryService.history(eq(CALLER_ID), any(TelemetryDTO.Query.class)))
                .willReturn(onePage());

        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("carId", CAR_ID.toString())
                        .param("since", "2026-09-10T11:00:00Z")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carId").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].tripId").value(TRIP_ID.toString()))
                .andExpect(jsonPath("$.readings[0].recordedAt").value("2026-09-10T12:00:00Z"))
                .andExpect(jsonPath("$.readings[0].fuelLevel").value(70))
                // The stored jsonb text is emitted as JSON, not as a string
                // containing JSON - the client gets back the frame it sent.
                .andExpect(jsonPath("$.readings[0].raw.pids.04").value("5020"))
                .andExpect(jsonPath("$.nextSince").value("2026-09-10T12:00:00Z"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void historyDefaultsSinceAndLimitWhenAbsent() throws Exception {
        given(telemetryService.history(eq(CALLER_ID), any(TelemetryDTO.Query.class)))
                .willReturn(onePage());

        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("carId", CAR_ID.toString()))
                .andExpect(status().isOk());

        // Absent means "from the beginning" and "the default page size", so a
        // first sync needs no cursor.
        var captor = ArgumentCaptor.forClass(TelemetryDTO.Query.class);
        verify(telemetryService).history(eq(CALLER_ID), captor.capture());
        assertThat(captor.getValue().since()).isEqualTo(Instant.EPOCH);
        assertThat(captor.getValue().limit()).isEqualTo(TelemetryDTO.Query.DEFAULT_LIMIT);
    }

    @Test
    void historyRejectsAMissingCarAndABadLimit() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.carId").exists())
                .andExpect(jsonPath("$.errors.limit").exists());

        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("carId", CAR_ID.toString())
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.limit").exists());
    }

    @Test
    void historyRejectsAnUnparseableSinceAs400Not500() throws Exception {
        // A type-conversion failure on a query parameter would otherwise fall
        // through to the catch-all handler as a 500. Binding to a record turns
        // it into a field error on the same path as everything else.
        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("carId", CAR_ID.toString())
                        .param("since", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.since").exists());
    }

    @Test
    void historyMapsAForeignCarToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID))
                .given(telemetryService).history(eq(CALLER_ID), any(TelemetryDTO.Query.class));

        mockMvc.perform(get("/api/v1/telemetry").with(caller())
                        .param("carId", CAR_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    @Test
    void ingestMapsAForeignCarToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID))
                .given(telemetryService).ingest(eq(CALLER_ID), any(TelemetryDTO.Ingest.class));

        mockMvc.perform(post("/api/v1/telemetry").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }
}
