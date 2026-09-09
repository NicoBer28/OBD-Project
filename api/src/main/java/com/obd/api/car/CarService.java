package com.obd.api.car;

import com.obd.api.car.dto.CarDTO;
import com.obd.api.car.exception.LicensePlateAlreadyRegisteredException;
import com.obd.api.car.exception.ModelNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CarService {

    private final CarRepository carRepository;
    private final ModelRepository modelRepository;

    /**
     * Registers a car owned by {@code ownerId}.
     *
     * The owner is taken from the authenticated principal, never from the
     * request body - otherwise any caller could create a car in someone else's
     * name just by naming them.
     */
    @Transactional
    public CarDTO.Read create(UUID ownerId, CarDTO.Create request) {
        Model model = modelRepository.findById(request.modelId())
                .orElseThrow(() -> new ModelNotFoundException(request.modelId()));

        Car car = Car.builder()
                .carOwnerId(ownerId)
                .carModel(model)
                .carName(request.name().trim())
                // Normalised so "ab123cd" and "AB123CD " collide on the unique
                // index instead of both being stored as separate cars.
                .carLicensePlate(normalisePlate(request.licensePlate()))
                .carMileage(request.mileage())
                .build();

        Car saved;
        try {
            // saveAndFlush (not save): the INSERT has to hit the database inside
            // this try block so a duplicate-plate violation surfaces here rather
            // than at commit time, outside the catch.
            saved = carRepository.saveAndFlush(car);
        } catch (DataIntegrityViolationException e) {
            throw new LicensePlateAlreadyRegisteredException(car.getCarLicensePlate());
        }

        // Still inside the transaction, so reading the lazy model is safe.
        return CarDTO.Read.from(saved);
    }

    private static String normalisePlate(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase();
        // A blank plate is "no plate", not an empty string: the unique index is
        // partial on `license_plate is not null`, so empty strings would collide
        // with each other while nulls correctly do not.
        return trimmed.isEmpty() ? null : trimmed;
    }
}
