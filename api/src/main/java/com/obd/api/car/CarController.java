package com.obd.api.car;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.dto.CarDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/cars")
@RequiredArgsConstructor
public class CarController {

    private final CarService carService;

    @PostMapping
    public ResponseEntity<CarDTO.Read> create(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestBody @Valid CarDTO.Create request,
                                              UriComponentsBuilder uriBuilder) {
        CarDTO.Read created = carService.create(principal.getId(), request);

        URI location = uriBuilder.path("/api/v1/cars/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }


}
