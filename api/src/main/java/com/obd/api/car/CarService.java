package com.obd.api.car;

import com.obd.api.car.dto.CarDTO;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.car.exception.LicensePlateAlreadyRegisteredException;
import com.obd.api.car.exception.ModelNotFoundException;
import com.obd.api.group.Group;
import com.obd.api.group.GroupAccess;
import com.obd.api.group.GroupRepository;
import com.obd.api.model.Model;
import com.obd.api.model.ModelRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CarService {

    private final CarRepository carRepository;
    private final ModelRepository modelRepository;
    private final GroupRepository groupRepository;
    private final CarAccess carAccess;
    private final GroupAccess groupAccess;

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

    @Transactional
    public List<CarDTO.Read> getCars(UUID userId) {
        return carAccess.allReadableBy(userId).stream()
                .map(CarDTO.Read::from)
                .toList();
    }

    @Transactional
    public CarDTO.Read getCar(UUID ownerId, UUID carId){
        return carAccess.readableBy(ownerId, carId).map(CarDTO.Read::from).orElseThrow(() -> new CarNotFoundException(carId));
    }

    @Transactional
    public CarDTO.Read share(UUID userId, UUID carId, CarDTO.Share request) {
        Car car = carAccess.ownedBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        groupAccess.requireMember(userId, request.groupId());

        Group group = groupRepository.getReferenceById(request.groupId());
        car.setCarGroup(group);
        return CarDTO.Read.from(carRepository.saveAndFlush(car));
    }

    @Transactional
    public void unshare(UUID userId, UUID carId) {
        Car car = carAccess.ownedBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));
        car.setCarGroup(null);
        carRepository.saveAndFlush(car);
    }

    @Transactional
    public List<CarDTO.Read> forGroup(UUID userId, UUID groupId) {
        groupAccess.requireMember(userId, groupId);
        return carRepository.findByCarGroupGroupIdOrderByCarName(groupId).stream().map(CarDTO.Read::from).toList();
    }


    private static String normalisePlate(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
