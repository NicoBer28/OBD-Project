package com.obd.api.car;

import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CarRepositoryTest {

    /** Seeded by V2__cars_and_models.sql - fixed id so tests can rely on it. */
    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private CarRepository carRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID ownerId;

    @BeforeEach
    void createOwner() {
        User owner = userRepository.saveAndFlush(User.builder()
                .userName("Ada")
                .userLastName("Lovelace")
                .userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER)
                .enabled(true)
                .build());
        ownerId = owner.getUserId();
    }

    private Car.CarBuilder aCar() {
        return Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Ada's Gol")
                .carLicensePlate("AB123CD")
                .carMileage(120_000);
    }

    @Test
    void seedsTheModelCatalog() {
        // POST /api/v1/cars is unusable without a catalog, so the seed rows are
        // part of the contract, not just convenient test data.
        assertThat(modelRepository.count()).isEqualTo(8);
        assertThat(modelRepository.findById(SEEDED_GOL)).get()
                .satisfies(m -> {
                    assertThat(m.getModelBrand()).isEqualTo("Volkswagen");
                    assertThat(m.getModelName()).isEqualTo("Gol");
                    assertThat(m.getModelProtocol()).isEqualTo("ISO 15765-4 (CAN)");
                });
    }

    @Test
    void persistsAndReadsBackEveryMappedColumn() {
        Car saved = carRepository.saveAndFlush(aCar()
                .carLocation(Coordinates.builder()
                        .latitude(-34.6037).longitude(-58.3816).build())
                .carFuelLevel(70)
                .carBatteryLevel(85)
                .carMaxSpeed(130)
                .carAvgSpeed(48)
                .build());

        Car found = carRepository.findById(saved.getCarId()).orElseThrow();
        assertThat(found.getCarOwnerId()).isEqualTo(ownerId);
        assertThat(found.getCarModel().getModelId()).isEqualTo(SEEDED_GOL);
        assertThat(found.getCarName()).isEqualTo("Ada's Gol");
        assertThat(found.getCarLicensePlate()).isEqualTo("AB123CD");
        assertThat(found.getCarMileage()).isEqualTo(120_000);
        assertThat(found.getCarFuelLevel()).isEqualTo(70);
        assertThat(found.getCarBatteryLevel()).isEqualTo(85);
        assertThat(found.getCarMaxSpeed()).isEqualTo(130);
        assertThat(found.getCarAvgSpeed()).isEqualTo(48);
        assertThat(found.getCarLocation().getLatitude()).isEqualTo(-34.6037);
        assertThat(found.getCarLocation().getLongitude()).isEqualTo(-58.3816);
        assertThat(found.getCarCreatedAt()).isNotNull();
    }

    @Test
    void leavesTheTelemetrySnapshotNullOnAFreshCar() {
        Car saved = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Never reported")
                .build());

        Car found = carRepository.findById(saved.getCarId()).orElseThrow();
        assertThat(found.getCarFuelLevel()).isNull();
        assertThat(found.getCarBatteryLevel()).isNull();
        assertThat(found.getCarMileage()).isNull();
        // Hibernate reads an all-null embeddable back as a null object.
        assertThat(found.getCarLocation()).isNull();
    }

    @Test
    void findsEveryCarBelongingToAnOwner() {
        carRepository.saveAndFlush(aCar().build());
        carRepository.saveAndFlush(aCar().carName("Second").carLicensePlate("ZZ999ZZ").build());

        assertThat(carRepository.findByCarOwnerId(ownerId))
                .extracting(Car::getCarName)
                .containsExactlyInAnyOrder("Ada's Gol", "Second");
        assertThat(carRepository.findByCarOwnerId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectsTheSamePlateTwiceForOneOwner() {
        carRepository.saveAndFlush(aCar().build());

        assertThatThrownBy(() -> carRepository.saveAndFlush(
                aCar().carName("Same plate again").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTwoOwnersToRegisterTheSamePlate() {
        carRepository.saveAndFlush(aCar().build());

        User other = userRepository.saveAndFlush(User.builder()
                .userName("Grace").userLastName("Hopper")
                .userEmail("grace@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build());

        // Documents the deliberate choice not to make plates globally unique:
        // one account must not be able to block another from adding a car.
        assertThat(carRepository.saveAndFlush(aCar()
                .carOwnerId(other.getUserId()).build()).getCarId()).isNotNull();
    }

    @Test
    void allowsManyCarsWithNoPlateForOneOwner() {
        carRepository.saveAndFlush(aCar().carLicensePlate(null).build());
        carRepository.saveAndFlush(aCar().carName("Also plateless").carLicensePlate(null).build());

        // The unique index is partial (`where license_plate is not null`), so
        // absent plates must never collide.
        assertThat(carRepository.findByCarOwnerId(ownerId)).hasSize(2);
    }

    @Test
    void requiresAModelThatExists() {
        // A reference to a row that was never inserted - the FK has to reject it
        // rather than the database silently storing a dangling model_id.
        Car orphan = aCar().build();
        orphan.setCarModel(entityManager.getReference(Model.class, UUID.randomUUID()));

        assertThatThrownBy(() -> carRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeMileage() {
        // Enforced by ck_cars_mileage in the migration, not by the entity.
        assertThatThrownBy(() -> carRepository.saveAndFlush(aCar().carMileage(-1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnOutOfRangeLatitude() {
        assertThatThrownBy(() -> carRepository.saveAndFlush(aCar()
                .carLocation(Coordinates.builder().latitude(91.0).longitude(0.0).build())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
