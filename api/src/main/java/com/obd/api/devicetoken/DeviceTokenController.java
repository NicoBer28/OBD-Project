package com.obd.api.devicetoken;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.devicetoken.dto.DeviceTokenDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping("/cars/{carId}/device-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceTokenDTO.Minted mint(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable UUID carId,
                                      @RequestBody @Valid DeviceTokenDTO.Create request) {
        return deviceTokenService.mint(principal.getId(), carId, request);
    }

    @GetMapping("/cars/{carId}/device-tokens")
    public List<DeviceTokenDTO.Read> forCar(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable UUID carId) {
        return deviceTokenService.forCar(principal.getId(), carId);
    }

    @DeleteMapping("/device-tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        deviceTokenService.revoke(principal.getId(), id);
    }
}
