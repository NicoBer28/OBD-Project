package com.obd.api.trip;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.trip.dto.TripDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @PostMapping
    public ResponseEntity<TripDTO.Read> start(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestBody @Valid TripDTO.Create request,
                                              UriComponentsBuilder uriBuilder) {
        TripDTO.Read started = tripService.start(principal.getId(), request);

        URI location = uriBuilder.path("/api/v1/trips/{id}")
                .buildAndExpand(started.id())
                .toUri();

        return ResponseEntity.created(location).body(started);
    }
}
