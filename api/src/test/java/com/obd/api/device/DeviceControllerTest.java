package com.obd.api.device;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.device.dto.DeviceDTO;
import com.obd.api.device.exception.DeviceAlreadyPairedException;
import com.obd.api.device.exception.DeviceNotFoundException;
import com.obd.api.device.exception.NoDevicePairedException;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the four device routes. The service is mocked; see
 * {@link DeviceServiceTest} for the behaviour against the database.
 */
@WebMvcTest(controllers = DeviceController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class DeviceControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CAR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID DEVICE_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String SERIAL = "A4:CF:12:8B:3C:7E";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static DeviceDTO.Read device() {
        return new DeviceDTO.Read(DEVICE_ID, SERIAL, CAR_ID, "Ada's Gol",
                Instant.parse("2026-09-15T12:00:00Z"), null);
    }

    // --- PUT /cars/{carId}/device ----------------------------------------------

    @Test
    void pairReturnsTheDevice() throws Exception {
        given(deviceService.pair(eq(CALLER_ID), eq(CAR_ID), any(DeviceDTO.Pair.class)))
                .willReturn(device());

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/device").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serial\": \"a4:cf:12:8b:3c:7e\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.serial").value(SERIAL))
                .andExpect(jsonPath("$.carId").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.carName").value("Ada's Gol"))
                .andExpect(jsonPath("$.pairedAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
    }

    @Test
    void pairRejectsABlankOrMalformedSerial() throws Exception {
        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/device").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serial\": \"has spaces and/slashes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.serial").exists());

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/device").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serial\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.serial").exists());
    }

    @Test
    void pairMapsACarThatIsNotMineToNotFound() throws Exception {
        willThrow(new CarNotFoundException(CAR_ID))
                .given(deviceService).pair(eq(CALLER_ID), eq(CAR_ID), any(DeviceDTO.Pair.class));

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/device").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serial\": \"" + SERIAL + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such car"));
    }

    @Test
    void pairMapsADonglePairedElsewhereToConflict() throws Exception {
        willThrow(new DeviceAlreadyPairedException(SERIAL))
                .given(deviceService).pair(eq(CALLER_ID), eq(CAR_ID), any(DeviceDTO.Pair.class));

        mockMvc.perform(put("/api/v1/cars/" + CAR_ID + "/device").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serial\": \"" + SERIAL + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("That device is paired to another car; unpair it there first"));
    }

    // --- GET /cars/{carId}/device ----------------------------------------------

    @Test
    void forCarReturnsTheDevice() throws Exception {
        given(deviceService.forCar(CALLER_ID, CAR_ID)).willReturn(device());

        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/device").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value(SERIAL));
    }

    @Test
    void forCarMapsNoDongleToNotFound() throws Exception {
        willThrow(new NoDevicePairedException(CAR_ID)).given(deviceService).forCar(CALLER_ID, CAR_ID);

        mockMvc.perform(get("/api/v1/cars/" + CAR_ID + "/device").with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No device is paired to this car"));
    }

    // --- DELETE /cars/{carId}/device -------------------------------------------

    @Test
    void unpairReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/cars/" + CAR_ID + "/device").with(caller()))
                .andExpect(status().isNoContent());

        then(deviceService).should().unpair(CALLER_ID, CAR_ID);
    }

    // --- GET /devices/{serial} -------------------------------------------------

    @Test
    void resolveReturnsTheCarForASerial() throws Exception {
        given(deviceService.resolve(CALLER_ID, "a4:cf:12:8b:3c:7e")).willReturn(device());

        // What a phone calls on connect: serial in, car out.
        mockMvc.perform(get("/api/v1/devices/a4:cf:12:8b:3c:7e").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carId").value(CAR_ID.toString()))
                .andExpect(jsonPath("$.carName").value("Ada's Gol"));
    }

    @Test
    void resolveMapsUnknownAndNotMineToNotFound() throws Exception {
        willThrow(new DeviceNotFoundException(SERIAL)).given(deviceService).resolve(CALLER_ID, SERIAL);

        mockMvc.perform(get("/api/v1/devices/" + SERIAL).with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such device"));
    }
}
