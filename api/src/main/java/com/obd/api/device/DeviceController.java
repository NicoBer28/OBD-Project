package com.obd.api.device;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.device.dto.DeviceDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A car's dongle lives under the car (pair, read, unpair); resolving a serial
 * to a car is the one route keyed by the dongle itself, because that is all
 * a phone knows when it connects.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PutMapping("/cars/{carId}/device")
    public DeviceDTO.Read pair(@AuthenticationPrincipal UserPrincipal principal,
                               @PathVariable UUID carId,
                               @RequestBody @Valid DeviceDTO.Pair request) {
        return deviceService.pair(principal.getId(), carId, request);
    }

    @GetMapping("/cars/{carId}/device")
    public DeviceDTO.Read forCar(@AuthenticationPrincipal UserPrincipal principal,
                                 @PathVariable UUID carId) {
        return deviceService.forCar(principal.getId(), carId);
    }

    @DeleteMapping("/cars/{carId}/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpair(@AuthenticationPrincipal UserPrincipal principal,
                       @PathVariable UUID carId) {
        deviceService.unpair(principal.getId(), carId);
    }

    @GetMapping("/devices/{serial}")
    public DeviceDTO.Read resolve(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable String serial) {
        return deviceService.resolve(principal.getId(), serial);
    }
}
