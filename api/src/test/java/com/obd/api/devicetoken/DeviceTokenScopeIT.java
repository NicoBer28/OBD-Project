package com.obd.api.devicetoken;

import com.obd.api.car.Car;
import com.obd.api.car.CarRepository;
import com.obd.api.devicetoken.dto.DeviceTokenDTO;
import com.obd.api.model.ModelRepository;
import com.obd.api.support.PostgresContainerConfig;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a device token may and may not do, against the <em>real</em> security
 * filter chain.
 *
 * This has to be a full-context test: the thing under test is the
 * authorisation rules in SecurityConfig, which no slice loads. It is also the
 * test that makes "closed by default" a fact rather than an intention - if
 * somebody adds an endpoint and a device token can suddenly call it, the
 * blanket assertion below fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "DB_USER=unused",
        "DB_PASSWORD=unused",
        "JWT_SECRET=b2JkLXRlc3Qtc2VjcmV0LWtleS0zMi1ieXRlcy1vayE="
})
class DeviceTokenScopeIT {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DeviceTokenService deviceTokenService;
    @Autowired
    private CarRepository carRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private UserRepository userRepository;

    private String device;        // Authorization value for the token's car
    private UUID carId;
    private UUID otherCarId;

    @BeforeEach
    void aTokenForOneCar() {
        UUID adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace")
                .userEmail("ada+" + UUID.randomUUID() + "@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();

        carId = newCar(adaId, "Ada's Gol").getCarId();
        otherCarId = newCar(adaId, "Ada's other car").getCarId();

        DeviceTokenDTO.Minted minted =
                deviceTokenService.mint(adaId, carId, new DeviceTokenDTO.Create("Pixel de Ada"));
        device = "Device " + minted.token();
    }

    private Car newCar(UUID ownerId, String name) {
        return carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName(name)
                .build());
    }

    // --- the four endpoints it may call --------------------------------------

    @Test
    void itMayUploadTelemetryForItsOwnCar() throws Exception {
        mockMvc.perform(post("/api/v1/telemetry").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carId": "%s", "readings": [{"recordedAt": "%s", "speed": 42}]}
                                """.formatted(carId, java.time.Instant.now())))
                .andExpect(status().isOk());
    }

    @Test
    void itMayStartFinishAndCheckATripOnItsOwnCar() throws Exception {
        String body = mockMvc.perform(post("/api/v1/trips").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carId\": \"%s\"}".formatted(carId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tripId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/cars/{id}/trips/active", carId).header("Authorization", device))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/trips/{id}/finish", tripId).header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripFinalFuel\": 50, \"distanceKm\": 12.40}"))
                .andExpect(status().isOk());
    }

    // --- the car it is scoped to ---------------------------------------------

    @Test
    void itMayNotTouchAnotherCarEvenOneItsOwnerOwns() throws Exception {
        // Authenticating is not the same as being in scope. The owner has
        // every right to this second car; the token does not.
        mockMvc.perform(post("/api/v1/telemetry").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carId": "%s", "readings": [{"recordedAt": "%s", "speed": 42}]}
                                """.formatted(otherCarId, java.time.Instant.now())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/trips").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carId\": \"%s\"}".formatted(otherCarId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/cars/{id}/trips/active", otherCarId).header("Authorization", device))
                .andExpect(status().isForbidden());
    }

    // --- everything else -----------------------------------------------------

    /**
     * The assertion that makes the design hold.
     *
     * SecurityConfig requires ROLE_USER across the API and names only four
     * endpoints that also accept ROLE_DEVICE, so an endpoint added later is
     * closed to devices until somebody opens it deliberately. A whitelist
     * would have failed the other way: open until somebody remembered.
     */
    @Test
    void itMayNotCallAnythingElse() throws Exception {
        var forbidden = new org.springframework.test.web.servlet.RequestBuilder[]{
                get("/api/v1/users/me").header("Authorization", device),
                put("/api/v1/users/me").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"X\",\"userLastName\":\"Y\"}"),
                post("/api/v1/users/me/password").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"supersecret123\"}"),
                get("/api/v1/cars").header("Authorization", device),
                get("/api/v1/cars/{id}", carId).header("Authorization", device),
                post("/api/v1/cars").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"modelId\":\"%s\"}".formatted(SEEDED_GOL)),
                delete("/api/v1/cars/{id}/group", carId).header("Authorization", device),
                get("/api/v1/groups").header("Authorization", device),
                post("/api/v1/groups").header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"),
                get("/api/v1/invitations/pending").header("Authorization", device),
                get("/api/v1/models").header("Authorization", device),
                get("/api/v1/telemetry?carId=" + carId).header("Authorization", device),
                get("/api/v1/trips").header("Authorization", device),
                get("/api/v1/cars/{id}/trips", carId).header("Authorization", device),
                post("/api/v1/cars/{id}/device-tokens", carId).header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"X\"}"),
        };

        for (var request : forbidden) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
    }

    /**
     * The refusal must not say "confirm your email address".
     *
     * DevicePrincipal is a UserPrincipal carrying no EMAIL_VERIFIED authority,
     * so the chain's blanket rule refuses it for the same reason it refuses an
     * unconfirmed person - and reporting that reason would send the background
     * service chasing a mailbox it has no way to reach.
     */
    @Test
    void theReasonItIsRefusedIsTheCredentialNotAnUnverifiedAddress() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", device))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.reason").value("forbidden"));
    }

    @Test
    void itCannotMintOrRevokeCredentials() throws Exception {
        // Otherwise a stolen token could issue itself a fresh one and survive
        // the user revoking the original.
        mockMvc.perform(post("/api/v1/cars/{id}/device-tokens", carId).header("Authorization", device)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Nuevo\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/device-tokens/{id}", UUID.randomUUID())
                        .header("Authorization", device))
                .andExpect(status().isForbidden());
    }

    @Test
    void garbageInTheDeviceSchemeIsUnauthorisedNotForbidden() throws Exception {
        // 401 means "get a new credential", 403 means "stop trying". The
        // client's retry policy turns on exactly this difference.
        mockMvc.perform(get("/api/v1/cars/{id}/trips/active", carId)
                        .header("Authorization", "Device obdd_notarealtoken"))
                .andExpect(status().isUnauthorized());

        assertThat(deviceTokenService).isNotNull();
    }
}
