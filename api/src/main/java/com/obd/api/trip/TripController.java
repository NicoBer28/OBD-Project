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

    @PostMapping("/trips")
    public ResponseEntity<TripDTO.Read> start(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestBody @Valid TripDTO.Create request) {
        TripService.Started started = tripService.start(principal.getId(), request);

        return ResponseEntity
                .status(started.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(started.trip());
    }

    @GetMapping("/trips")
    public List<TripDTO.Read> getTrips(@AuthenticationPrincipal UserPrincipal principal){
        return tripService.getTrips(principal.getId());
    }

    @GetMapping("/cars/{id}/trips")
    public List<TripDTO.Read> getCarTrips(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.getCarTrips(principal.getId(), id);
    }

    @GetMapping("/cars/{id}/trips/active")
    public ResponseEntity<TripDTO.Read> active(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.active(principal.getId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/trips/{id}/route")
    public List<TripDTO.RoutePoint> route(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        return tripService.route(principal.getId(), id);
    }

    @PostMapping("/trips/{id}/finish")
    public TripDTO.Read finish(@AuthenticationPrincipal UserPrincipal principal, @RequestBody @Valid TripDTO.finish trip, @PathVariable UUID id){
        return tripService.finish(principal.getId(), id ,trip);
    }

    @DeleteMapping("/trips/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id){
        tripService.delete(principal.getId(), id);
    }

}
