package com.obd.api.device;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.device.dto.DeviceDTO;
import com.obd.api.device.exception.DeviceAlreadyPairedException;
import com.obd.api.device.exception.DeviceNotFoundException;
import com.obd.api.device.exception.NoDevicePairedException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final CarAccess carAccess;

    /**
     * Pairs a dongle to a car the caller owns. PUT semantics: pairing the car
     * to a new serial replaces its current dongle; pairing the same serial
     * again is a no-op that returns the existing row.
     *
     * Owner-only. Members may drive the car and upload for it, but which
     * physical device speaks for a car is the owner's decision.
     */
    @Transactional
    public DeviceDTO.Read pair(UUID userId, UUID carId, DeviceDTO.Pair request) {
        Car car = carAccess.ownedBy(userId, carId)
                .orElseThrow(() -> new CarNotFoundException(carId));
        String serial = normaliseSerial(request.serial());

        Device bySerial = deviceRepository.findByDeviceSerial(serial).orElse(null);
        if (bySerial != null) {
            if (bySerial.getDeviceCarId().equals(carId)) {
                return DeviceDTO.Read.from(bySerial, car);          // already paired here
            }
            // Moving a dongle between cars is explicit - unpair there first -
            // so readings are never silently re-attributed.
            throw new DeviceAlreadyPairedException(serial);
        }

        // Update in place rather than delete-then-insert: Hibernate orders
        // inserts before deletes within a flush, and ux_devices_car_id would
        // reject the new row while the old one still exists.
        Device device = deviceRepository.findByDeviceCarId(carId)
                .map(existing -> {
                    existing.setDeviceSerial(serial);
                    existing.setDevicePairedAt(Instant.now());
                    existing.setDeviceLastSeenAt(null);           // a new dongle has not reported yet
                    return existing;
                })
                .orElseGet(() -> Device.builder().deviceCarId(carId).deviceSerial(serial).build());

        try {
            return DeviceDTO.Read.from(deviceRepository.saveAndFlush(device), car);
        } catch (DataIntegrityViolationException e) {
            // Lost a race for ux_devices_serial: another car paired it first.
            throw new DeviceAlreadyPairedException(serial);
        }
    }

    /** The dongle paired to a car the caller may read. */
    @Transactional
    public DeviceDTO.Read forCar(UUID userId, UUID carId) {
        Car car = carAccess.readableBy(userId, carId)
                .orElseThrow(() -> new CarNotFoundException(carId));
        Device device = deviceRepository.findByDeviceCarId(carId)
                .orElseThrow(() -> new NoDevicePairedException(carId));
        return DeviceDTO.Read.from(device, car);
    }

    /**
     * "Which car is this dongle?" - what a phone asks the moment it connects.
     * Answered only if the caller may read that car; otherwise the serial
     * might as well not exist.
     */
    @Transactional
    public DeviceDTO.Read resolve(UUID userId, String rawSerial) {
        String serial = normaliseSerial(rawSerial);
        Device device = deviceRepository.findByDeviceSerial(serial)
                .orElseThrow(() -> new DeviceNotFoundException(serial));
        Car car = carAccess.readableBy(userId, device.getDeviceCarId())
                .orElseThrow(() -> new DeviceNotFoundException(serial));
        return DeviceDTO.Read.from(device, car);
    }

    /** Owner-only. Idempotent: unpairing a car with no dongle is a no-op. */
    @Transactional
    public void unpair(UUID userId, UUID carId) {
        carAccess.ownedBy(userId, carId)
                .orElseThrow(() -> new CarNotFoundException(carId));
        deviceRepository.findByDeviceCarId(carId).ifPresent(deviceRepository::delete);
    }

    /**
     * Trimmed and upper-cased so a MAC-derived serial compares equal however
     * the phone's BLE stack reports it. The CHECK constraint rejects anything
     * that skipped this.
     */
    public static String normaliseSerial(String raw) {
        return raw.trim().toUpperCase();
    }
}
