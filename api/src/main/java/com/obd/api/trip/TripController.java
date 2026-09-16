package com.obd.api.trip;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.trip.dto.TripDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    // 201: a trip was created. No Location, since GET /trips/{id} does not exist.
    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    public TripDTO.Read start(@AuthenticationPrincipal UserPrincipal principal, @RequestBody @Valid TripDTO.Create request) {
        return tripService.start(principal.getId(), request);
    }

    @GetMapping("/trips")
    public List<TripDTO.Read> getTrips(@AuthenticationPrincipal UserPrincipal principal){
        return tripService.getTrips(principal.getId());
    }

    @GetMapping("/cars/{id}/trips")
    public List<TripDTO.Read> getCarTrips(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.getCarTrips(principal.getId(), id);
    }

    /** 200 with the open trip, or 204 when the car is idle - the usual case. */
    @GetMapping("/cars/{id}/trips/active")
    public ResponseEntity<TripDTO.Read> active(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.active(principal.getId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/trips/{id}/finish")
    public TripDTO.Read finish(@AuthenticationPrincipal UserPrincipal principal, @RequestBody @Valid TripDTO.finish trip, @PathVariable UUID id){
        return tripService.finish(principal.getId(), id ,trip);
    }

    @DeleteMapping("/trips/{id}")
    public TripDTO.Read delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.delete(principal.getId(), id);
    }

}
