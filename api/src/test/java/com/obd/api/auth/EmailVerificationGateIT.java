package com.obd.api.auth;

import com.obd.api.car.Car;
import com.obd.api.car.CarRepository;
import com.obd.api.model.ModelRepository;
import com.obd.api.support.PostgresContainerConfig;
import com.obd.api.support.RecordedMail;
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
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hard gate: an account that has not confirmed its email address can do
 * nothing, against the real security filter chain.
 *
 * A full-context test because the rule lives in SecurityConfig, which no slice
 * loads - and because the point of the gate is what it refuses, which only the
 * real chain decides.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, RecordedMail.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "DB_USER=unused",
        "DB_PASSWORD=unused",
        "JWT_SECRET=b2JkLXRlc3Qtc2VjcmV0LWtleS0zMi1ieXRlcy1vayE="
})
class EmailVerificationGateIT {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String PASSWORD = "supersecret123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CarRepository carRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private RecordedMail.Recorder mail;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String unverified;   // bearer token of an account that never confirmed
    private String unverifiedEmail;
    private UUID carId;

    @BeforeEach
    void anAccountThatNeverConfirmed() throws Exception {
        mail.clear();
        unverifiedEmail = "sinverificar+" + UUID.randomUUID() + "@example.com";

        // Registration still issues tokens, so this is exactly what the app
        // holds right after signing someone up.
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Sin","userLastName":"Verificar",
                                 "userEmail":"%s","userPassword":"%s"}
                                """.formatted(unverifiedEmail, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        unverified = "Bearer " + body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        // Owned by somebody real: cars.owner_id is a foreign key. Whose car it
        // is does not matter - the walled account cannot reach it either way.
        UUID someoneElse = userRepository.saveAndFlush(User.builder()
                .userName("Otro").userLastName("Duenio")
                .userEmail("otro+" + UUID.randomUUID() + "@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .userEmailVerifiedAt(Instant.now())
                .role(Role.USER).enabled(true).build()).getUserId();

        carId = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(someoneElse)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Un auto cualquiera")
                .build()).getCarId();
    }

    // --- what it may still do ------------------------------------------------

    @Test
    void itMaySeeItselfAndAskForAnotherLink() throws Exception {
        // The wall screen needs exactly this: read the state, offer a resend.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", unverified))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));

        mockMvc.perform(post("/api/v1/users/me/verify-email").header("Authorization", unverified))
                .andExpect(status().isTooManyRequests());   // throttled: one was just sent

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", unverified))
                .andExpect(status().isNoContent());
    }

    // --- what it may not ----------------------------------------------------

    @Test
    void everythingElseIsForbiddenWithAReasonTheAppCanSwitchOn() throws Exception {
        RequestBuilder[] walled = {
                get("/api/v1/cars").header("Authorization", unverified),
                post("/api/v1/cars").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mi auto\",\"modelId\":\"%s\"}".formatted(SEEDED_GOL)),
                get("/api/v1/groups").header("Authorization", unverified),
                post("/api/v1/groups").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Familia\"}"),
                get("/api/v1/invitations/pending").header("Authorization", unverified),
                get("/api/v1/models").header("Authorization", unverified),
                get("/api/v1/trips").header("Authorization", unverified),
                post("/api/v1/trips").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carId\":\"%s\"}".formatted(carId)),
                get("/api/v1/telemetry?carId=" + carId).header("Authorization", unverified),
                post("/api/v1/telemetry").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carId\":\"%s\",\"readings\":[]}".formatted(carId)),
                put("/api/v1/users/me").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"X\",\"userLastName\":\"Y\"}"),
                post("/api/v1/users/me/password").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"otraclave456\"}".formatted(PASSWORD)),
                post("/api/v1/cars/{id}/device-tokens", carId).header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Pixel\"}"),
        };

        for (RequestBuilder request : walled) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason").value("email_not_verified"));
        }
    }

    @Test
    void itCannotEvenLogInAgain() throws Exception {
        // The token registration gave it is the only way in until the address
        // is confirmed; coming back later means using the public resend.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail":"%s","userPassword":"%s"}
                                """.formatted(unverifiedEmail, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("email_not_verified"));
    }

    @Test
    void aWrongPasswordStillAnswers401WhateverTheAccountsState() throws Exception {
        // Order matters: password first, gate second. Otherwise the 403 would
        // say "this address is registered" to anybody who asked.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail":"%s","userPassword":"unaclaveequivocada"}
                                """.formatted(unverifiedEmail)))
                .andExpect(status().isUnauthorized());
    }

    // --- the way back in ----------------------------------------------------

    @Test
    void thePublicResendAnswers202ForAnythingAtAll() throws Exception {
        for (String email : new String[]{unverifiedEmail, "nadie@example.com"}) {
            mockMvc.perform(post("/api/v1/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userEmail\":\"%s\"}".formatted(email)))
                    .andExpect(status().isAccepted());
        }

        // Identical answers, so a public endpoint cannot be used to ask who
        // has an account here.
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmingTheAddressOpensEverything() throws Exception {
        verifyFromTheMail();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", unverified))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        // The token issued before verifying keeps working: nothing about it
        // changed, only what the account is allowed to do.
        mockMvc.perform(post("/api/v1/groups").header("Authorization", unverified)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Familia\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail":"%s","userPassword":"%s"}
                                """.formatted(unverifiedEmail, PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * An ADMIN account is not locked out of the API.
     *
     * The gate is an authority of its own and not a role, because the chain's
     * blanket rule used to be {@code hasRole("USER")} - and an admin's only
     * role authority is ROLE_ADMIN, so that rule refused admins every endpoint
     * in the application, the admin-only ones included.
     */
    @Test
    void anAdminAccountIsNotLockedOut() throws Exception {
        String email = "admin+" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(User.builder()
                .userName("Admin").userLastName("Cuenta")
                .userEmail(email)
                .userPasswordHash(passwordEncoder.encode(PASSWORD))
                .userEmailVerifiedAt(Instant.now())
                .role(Role.ADMIN).enabled(true).build());

        String token = "Bearer " + mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail":"%s","userPassword":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        // An ordinary endpoint, which the old rule refused an admin.
        mockMvc.perform(get("/api/v1/models").header("Authorization", token))
                .andExpect(status().isOk());

        // And the admin-only one, which has to pass the chain before
        // @PreAuthorize even runs.
        mockMvc.perform(post("/api/v1/models").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelBrand":"Honda","modelName":"Civic %s","modelProtocol":"ISO 15765-4 (CAN)"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    private void verifyFromTheMail() throws Exception {
        String token = mail.latestToken();
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isNoContent());
    }
}
