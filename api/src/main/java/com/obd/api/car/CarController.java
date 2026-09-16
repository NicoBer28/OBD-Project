package com.obd.api.car;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.car.dto.CarDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CarController {

    private final CarService carService;

    @PostMapping("/cars")
    public ResponseEntity<CarDTO.Read> create(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestBody @Valid CarDTO.Create request,
                                              UriComponentsBuilder uriBuilder) {
        CarDTO.Read created = carService.create(principal.getId(), request);

        URI location = uriBuilder.path("/api/v1/cars/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/cars")
    public List<CarDTO.Read> cars(@AuthenticationPrincipal UserPrincipal principal){
        return carService.getCars(principal.getId());
    }

    /**
     * PUT, not POST: "the group this car is shared with" is a single slot the
     * caller sets, replaces or clears - not a collection they add to.
     */
    @PutMapping("/cars/{carId}/group")
    public CarDTO.Read share(@AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable UUID carId,
                             @RequestBody @Valid CarDTO.Share request) {
        return carService.share(principal.getId(), carId, request);
    }

    @DeleteMapping("/cars/{carId}/group")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@AuthenticationPrincipal UserPrincipal principal,
                        @PathVariable UUID carId) {
        carService.unshare(principal.getId(), carId);
    }

    @GetMapping("/groups/{groupId}/cars")
    public List<CarDTO.Read> forGroup(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable UUID groupId) {
        return carService.forGroup(principal.getId(), groupId);
    }

    @GetMapping("/cars/{car_id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CarDTO.Read car(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID car_id){
        return carService.getCar(principal.getId(), car_id);
    }


}
