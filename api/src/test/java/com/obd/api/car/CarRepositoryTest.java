package com.obd.api.car;

import com.obd.api.group.*;
import com.obd.api.model.Model;
import com.obd.api.model.ModelRepository;
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

import java.time.Instant;
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
                .build());

        Car found = carRepository.findById(saved.getCarId()).orElseThrow();
        assertThat(found.getCarOwnerId()).isEqualTo(ownerId);
        assertThat(found.getCarModel().getModelId()).isEqualTo(SEEDED_GOL);
        assertThat(found.getCarName()).isEqualTo("Ada's Gol");
        assertThat(found.getCarLicensePlate()).isEqualTo("AB123CD");
        assertThat(found.getCarMileage()).isEqualTo(120_000);
        assertThat(found.getCarFuelLevel()).isEqualTo(70);
        assertThat(found.getCarBatteryLevel()).isEqualTo(85);
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

    // --- READABLE: the one definition of "cars this user may use" -----------

    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private Group newGroupWith(UUID... memberIds) {
        Group group = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build());
        for (UUID memberId : memberIds) {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .id(new GroupMemberId(group.getGroupId(), memberId))
                    .role(GroupRole.MEMBER).build());
        }
        return group;
    }

    private Car shared(Car car, Group group) {
        car.setCarGroup(group);
        return carRepository.saveAndFlush(car);
    }

    @Test
    void listsOwnCarsAndCarsSharedWithMyGroupsOnce() {
        UUID grace = newUser("grace@example.com");
        UUID stranger = newUser("stranger@example.com");
        Group family = newGroupWith(ownerId, grace);
        Group otherFamily = newGroupWith(stranger);

        Car mine = carRepository.saveAndFlush(aCar().carName("A mine, unshared").carLicensePlate(null).build());
        // Owned by me *and* shared with my own group - the everyday case, and
        // the one a two-source merge would list twice.
        shared(aCar().carName("B mine, shared").carLicensePlate(null).build(), family);
        // Grace's car, shared with the family: visible to me through membership.
        shared(aCar().carOwnerId(grace).carName("C grace, shared").build(), family);
        // Grace's car kept to herself, and a stranger's car in a group I am not in.
        carRepository.saveAndFlush(aCar().carOwnerId(grace).carName("D grace, private").carLicensePlate(null).build());
        shared(aCar().carOwnerId(stranger).carName("E stranger, shared elsewhere").build(), otherFamily);

        assertThat(carRepository.findAllReadableBy(ownerId))
                .extracting(Car::getCarName)
                .containsExactly("A mine, unshared", "B mine, shared", "C grace, shared");
        assertThat(carRepository.findAllReadableBy(grace))
                .extracting(Car::getCarName)
                .containsExactly("B mine, shared", "C grace, shared", "D grace, private");
        assertThat(carRepository.findAllReadableBy(stranger))
                .extracting(Car::getCarName)
                .containsExactly("E stranger, shared elsewhere");
        assertThat(carRepository.findAllReadableBy(mine.getCarOwnerId())).hasSize(3);
    }

    @Test
    void perCarCheckAgreesWithTheList() {
        UUID grace = newUser("grace@example.com");
        UUID stranger = newUser("stranger@example.com");
        Group family = newGroupWith(ownerId, grace);
        Car car = shared(aCar().build(), family);

        // Same predicate as the list, so these can never drift apart.
        assertThat(carRepository.findReadableBy(ownerId, car.getCarId())).isPresent();
        assertThat(carRepository.findReadableBy(grace, car.getCarId())).isPresent();
        assertThat(carRepository.findReadableBy(stranger, car.getCarId())).isEmpty();
        assertThat(carRepository.findReadableBy(ownerId, UUID.randomUUID())).isEmpty();
    }

    @Test
    void unsharingACarRemovesItFromTheMembersList() {
        UUID grace = newUser("grace@example.com");
        Group family = newGroupWith(ownerId, grace);
        Car car = shared(aCar().build(), family);
        assertThat(carRepository.findReadableBy(grace, car.getCarId())).isPresent();

        car.setCarGroup(null);
        carRepository.saveAndFlush(car);

        assertThat(carRepository.findReadableBy(grace, car.getCarId())).isEmpty();
        // The owner, of course, keeps it.
        assertThat(carRepository.findReadableBy(ownerId, car.getCarId())).isPresent();
    }

    @Test
    void deletingAGroupUnsharesItsCarsInsteadOfDeletingThem() {
        UUID grace = newUser("grace@example.com");
        Group family = newGroupWith(ownerId, grace);
        Car car = shared(aCar().build(), family);
        // Detach first: a managed Car still pointing at the Group would make
        // Hibernate refuse the flush. In production the group is deleted in a
        // request that never loaded the cars; this mirrors that.
        entityManager.clear();

        groupRepository.deleteById(family.getGroupId());
        entityManager.flush();
        entityManager.clear();

        // on delete set null in V7: the car and its history belong to the
        // owner, not to the group.
        Car found = carRepository.findById(car.getCarId()).orElseThrow();
        assertThat(found.getCarGroup()).isNull();
        assertThat(carRepository.findReadableBy(grace, car.getCarId())).isEmpty();
    }

    // --- refreshSnapshot: the compare-and-set behind telemetry ingestion -----

    private static final Instant NOON = Instant.parse("2026-09-10T12:00:00Z");

    private Car reload(UUID id) {
        return carRepository.findById(id).orElseThrow();
    }

    @Test
    void refreshSnapshotSetsTheFirstReadingOnACarThatNeverReported() {
        UUID id = carRepository.saveAndFlush(aCar().build()).getCarId();

        int moved = carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3);

        assertThat(moved).isEqualTo(1);
        Car car = reload(id);
        assertThat(car.getCarFuelLevel()).isEqualTo(70);
        assertThat(car.getCarBatteryLevel()).isEqualTo(85);
        assertThat(car.getCarMileage()).isEqualTo(120_500);
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.6);
        assertThat(car.getCarSnapshotAt()).isEqualTo(NOON);
    }

    @Test
    void refreshSnapshotReplacesWithANewerReading() {
        UUID id = carRepository.saveAndFlush(aCar().build()).getCarId();
        carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3);

        int moved = carRepository.refreshSnapshot(id, NOON.plusSeconds(5), 69, 84, 120_501, -34.7, -58.4);

        assertThat(moved).isEqualTo(1);
        Car car = reload(id);
        assertThat(car.getCarFuelLevel()).isEqualTo(69);
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.7);
        assertThat(car.getCarSnapshotAt()).isEqualTo(NOON.plusSeconds(5));
    }

    @Test
    void refreshSnapshotIgnoresAnOlderReading() {
        UUID id = carRepository.saveAndFlush(aCar().build()).getCarId();
        carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3);

        // The one that matters: a late buffer flush must not roll the car back.
        int moved = carRepository.refreshSnapshot(id, NOON.minusSeconds(3600), 90, 99, 120_000, 0.0, 0.0);

        assertThat(moved).isZero();
        Car car = reload(id);
        assertThat(car.getCarFuelLevel()).isEqualTo(70);
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.6);
        assertThat(car.getCarSnapshotAt()).isEqualTo(NOON);
    }

    @Test
    void refreshSnapshotIgnoresTheSameInstant() {
        UUID id = carRepository.saveAndFlush(aCar().build()).getCarId();
        carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3);

        // A retried batch: strictly newer or nothing, so a replay is a no-op.
        assertThat(carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3)).isZero();
    }

    @Test
    void refreshSnapshotKeepsFieldsAPartialReadingLacks() {
        UUID id = carRepository.saveAndFlush(aCar().build()).getCarId();
        carRepository.refreshSnapshot(id, NOON, 70, 85, 120_500, -34.6, -58.3);

        // A fuel-only frame, no GPS fix: the pin must not vanish from the map.
        int moved = carRepository.refreshSnapshot(id, NOON.plusSeconds(5), 65, null, null, null, null);

        assertThat(moved).isEqualTo(1);
        Car car = reload(id);
        assertThat(car.getCarFuelLevel()).isEqualTo(65);
        assertThat(car.getCarBatteryLevel()).isEqualTo(85);
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.6);
        assertThat(car.getCarSnapshotAt()).isEqualTo(NOON.plusSeconds(5));
    }

    @Test
    void rejectsAnOutOfRangeLatitude() {
        assertThatThrownBy(() -> carRepository.saveAndFlush(aCar()
                .carLocation(Coordinates.builder().latitude(91.0).longitude(0.0).build())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
