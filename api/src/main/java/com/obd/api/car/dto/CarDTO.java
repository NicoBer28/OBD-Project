package com.obd.api.car.dto;

import com.obd.api.car.Car;
import com.obd.api.model.Model;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public class CarDTO {

    public record Create(
            @NotBlank @Size(max = 60) String name,
            // Optional. Kept loose on purpose - plate formats differ by country
            // and a regex here would reject valid plates rather than catch typos.
            @Size(max = 16) String licensePlate,
            @NotNull UUID modelId,
            // Odometer reading when the car is registered. Everything else in
            // the snapshot comes from the device, never from the client.
            @PositiveOrZero Integer mileage
    ) {}

    public record Read(
            UUID id,
            String name,
            String licensePlate,
            ModelRead model,
            Integer mileage,
            Integer fuelLevel,
            Integer batteryLevel,
            Double latitude,
            Double longitude,
            Instant snapshotAt
    ) {
        /**
         * Must be called while the persistence context is still open - it walks
         * the lazy model association.
         */
        public static Read from(Car car) {
            var location = car.getCarLocation();
            return new Read(
                    car.getCarId(),
                    car.getCarName(),
                    car.getCarLicensePlate(),
                    ModelRead.from(car.getCarModel()),
                    car.getCarMileage(),
                    car.getCarFuelLevel(),
                    car.getCarBatteryLevel(),
                    location == null ? null : location.getLatitude(),
                    location == null ? null : location.getLongitude(),
                    car.getCarSnapshotAt()
            );
        }
    }

    public record ModelRead(UUID id, String brand, String model, String protocol) {
        public static ModelRead from(Model m) {
            return new ModelRead(m.getModelId(), m.getModelBrand(),
                    m.getModelName(), m.getModelProtocol());
        }
    }
}
