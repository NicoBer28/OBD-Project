package com.obd.api.device.dto;

import com.obd.api.car.Car;
import com.obd.api.device.Device;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class DeviceDTO {

    public record Pair(
            // Letters, digits, and the separators a MAC-derived serial uses.
            // Surrounding whitespace and case are not significant: normalised
            // before storing, so the pattern tolerates both.
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^\\s*[A-Za-z0-9:_-]+\\s*$", message = "letters, digits, ':', '_' and '-' only")
            String serial
    ) {}

    /**
     * One shape for every device read, including the resolve-by-serial call
     * a phone makes on connect: it gets the car's id and name, which is all
     * it needs to start uploading and to tell the user what it found.
     */
    public record Read(
            UUID id,
            String serial,
            UUID carId,
            String carName,
            Instant pairedAt,
            Instant lastSeenAt
    ) {
        public static Read from(Device d, Car car) {
            return new Read(d.getDeviceId(), d.getDeviceSerial(), car.getCarId(), car.getCarName(),
                    d.getDevicePairedAt(), d.getDeviceLastSeenAt());
        }
    }
}
