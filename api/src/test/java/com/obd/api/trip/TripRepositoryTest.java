package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class TripRepositoryTest {

    /** Seeded by V2__cars_and_models.sql - fixed id so tests can rely on it. */
    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private TripRepository tripRepository;
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

    private UUID adaId;
    private UUID graceId;
    private UUID carId;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User")
                .userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newCar(UUID ownerId, String name) {
        return carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName(name)
                .build()).getCarId();
    }

    @BeforeEach
    void createOwnerAndCar() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        carId = newCar(adaId, "Ada's Gol");
    }

    private Trip.TripBuilder aTrip() {
        return Trip.builder().tripCarId(carId).tripDriverId(adaId);
    }

    @Test
    void persistsAndReadsBackEveryMappedColumn() {
        Instant started = Instant.now().minus(2, ChronoUnit.HOURS);
        Trip saved = tripRepository.saveAndFlush(aTrip()
                .tripStartedAt(started)
                .tripEndedAt(started.plus(90, ChronoUnit.MINUTES))
                .tripInitialFuel(70)
                .tripFinalFuel(52)
                .tripDistance(140)
                .build());
        entityManager.clear();

        Trip found = tripRepository.findById(saved.getTripId()).orElseThrow();
        assertThat(found.getTripCarId()).isEqualTo(carId);
        assertThat(found.getTripDriverId()).isEqualTo(adaId);
        assertThat(found.getTripStartedAt()).isNotNull();
        assertThat(found.getTripEndedAt()).isNotNull();
        assertThat(found.getTripInitialFuel()).isEqualTo(70);
        assertThat(found.getTripFinalFuel()).isEqualTo(52);
        assertThat(found.getTripDistance()).isEqualTo(140);
        assertThat(found.isActive()).isFalse();
    }

    @Test
    void startsATripOpenWithNoResultYet() {
        Trip saved = tripRepository.saveAndFlush(aTrip().tripInitialFuel(70).build());
        entityManager.clear();

        Trip found = tripRepository.findById(saved.getTripId()).orElseThrow();
        assertThat(found.getTripEndedAt()).isNull();
        assertThat(found.getTripFinalFuel()).isNull();
        assertThat(found.getTripDistance()).isNull();
        // An open trip is what "this car is in use, by this driver" means -
        // there is no flag or pointer anywhere else saying so.
        assertThat(found.isActive()).isTrue();
    }

    @Test
    void findsTheActiveTripOfACar() {
        assertThat(tripRepository.findByTripCarIdAndTripEndedAtIsNull(carId)).isEmpty();

        Trip open = tripRepository.saveAndFlush(aTrip().build());

        assertThat(tripRepository.findByTripCarIdAndTripEndedAtIsNull(carId))
                .get()
                .satisfies(t -> {
                    assertThat(t.getTripId()).isEqualTo(open.getTripId());
                    // The current driver is read off the open trip, so it cannot
                    // disagree with a copy held on the car.
                    assertThat(t.getTripDriverId()).isEqualTo(adaId);
                });
    }

    @Test
    void allowsANewTripOnceThePreviousOneIsFinished() {
        Trip first = tripRepository.saveAndFlush(aTrip().build());

        first.setTripEndedAt(Instant.now());
        // Flushed before the next insert on purpose: Hibernate orders inserts
        // ahead of updates within one flush, so batching these would make the
        // second trip collide with a first that is still open.
        tripRepository.saveAndFlush(first);

        Trip second = tripRepository.saveAndFlush(aTrip().tripDriverId(graceId).build());

        assertThat(tripRepository.findByTripCarIdAndTripEndedAtIsNull(carId))
                .get().extracting(Trip::getTripId).isEqualTo(second.getTripId());
    }

    @Test
    void allowsTwoCarsToBeInUseAtTheSameTime() {
        UUID otherCar = newCar(adaId, "Second car");
        tripRepository.saveAndFlush(aTrip().build());

        assertThat(tripRepository.saveAndFlush(
                aTrip().tripCarId(otherCar).build()).getTripId()).isNotNull();
    }

    @Test
    void keepsTheTripWhenTheDriversAccountIsDeleted() {
        Trip saved = tripRepository.saveAndFlush(aTrip().tripDriverId(graceId).build());

        userRepository.deleteById(graceId);
        entityManager.flush();
        entityManager.clear();

        // on delete set null in V4: deleting an account must not rewrite the
        // car's history, only detach the driver from it.
        Trip found = tripRepository.findById(saved.getTripId()).orElseThrow();
        assertThat(found.getTripDriverId()).isNull();
        assertThat(found.getTripCarId()).isEqualTo(carId);
    }

    @Test
    void deletingACarRemovesItsTrips() {
        Trip saved = tripRepository.saveAndFlush(aTrip().build());

        carRepository.deleteById(carId);
        entityManager.flush();
        entityManager.clear();

        // on delete cascade in V4 - a trip without its car means nothing.
        assertThat(tripRepository.findById(saved.getTripId())).isEmpty();
    }

    // --- finish: the one way a trip closes --------------------------------------

    @Test
    void finishClosesAnOpenTripWithItsResult() {
        UUID id = tripRepository.saveAndFlush(aTrip().tripInitialFuel(70).build()).getTripId();
        Instant at = Instant.now();

        assertThat(tripRepository.finish(id, adaId, at, 52, 140)).isEqualTo(1);

        Trip found = tripRepository.findById(id).orElseThrow();
        assertThat(found.getTripEndedAt()).isEqualTo(at);
        assertThat(found.getTripFinalFuel()).isEqualTo(52);
        assertThat(found.getTripDistance()).isEqualTo(140);
        assertThat(found.isActive()).isFalse();
        // The car is free again.
        assertThat(tripRepository.findByTripCarIdAndTripEndedAtIsNull(carId)).isEmpty();
    }

    @Test
    void finishRefusesSomeoneWhoIsNotTheDriver() {
        UUID id = tripRepository.saveAndFlush(aTrip().build()).getTripId();

        // Grace may be the owner, a member, anyone: only the driver closes
        // their own trip.
        assertThat(tripRepository.finish(id, graceId, Instant.now(), 52, 140)).isZero();
        assertThat(tripRepository.findById(id).orElseThrow().isActive()).isTrue();
    }

    @Test
    void finishRefusesToFinishTwice() {
        UUID id = tripRepository.saveAndFlush(aTrip().build()).getTripId();
        tripRepository.finish(id, adaId, Instant.now(), 52, 140);

        // A double tap must not overwrite the first result with a second
        // fuel reading - that would silently change the expense.
        assertThat(tripRepository.finish(id, adaId, Instant.now(), 10, 999)).isZero();
        assertThat(tripRepository.findById(id).orElseThrow().getTripFinalFuel()).isEqualTo(52);
    }

    @Test
    void finishRefusesAnUnknownTrip() {
        assertThat(tripRepository.finish(UUID.randomUUID(), adaId, Instant.now(), 52, 140)).isZero();
    }

    @Test
    void finishWithNoReadingsStillClosesTheTrip() {
        UUID id = tripRepository.saveAndFlush(aTrip().tripInitialFuel(70).build()).getTripId();

        // The driver could not read the gauge. The trip ends; its expense is
        // simply unknown (fuelUsed null), not zero.
        assertThat(tripRepository.finish(id, adaId, Instant.now(), null, null)).isEqualTo(1);
        Trip found = tripRepository.findById(id).orElseThrow();
        assertThat(found.isActive()).isFalse();
        assertThat(found.getTripFinalFuel()).isNull();
    }

    @Test
    void aFinishedCarCanStartAgainImmediately() {
        UUID first = tripRepository.saveAndFlush(aTrip().build()).getTripId();
        tripRepository.finish(first, adaId, Instant.now(), 52, 140);

        // A JPQL update runs at once, so the insert that follows sees the
        // closed row - no flush-ordering trap here.
        assertThat(tripRepository.saveAndFlush(aTrip().build()).getTripId()).isNotNull();
    }

    // --- cancel ------------------------------------------------------------------

    @Test
    void cancelRemovesAnOpenTripOfTheDriver() {
        UUID id = tripRepository.saveAndFlush(aTrip().build()).getTripId();

        var removed = tripRepository.deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(id, adaId);
        entityManager.flush();

        assertThat(removed).get().extracting(Trip::getTripId).isEqualTo(id);
        assertThat(tripRepository.findById(id)).isEmpty();
    }

    @Test
    void cancelRefusesAFinishedTripAndSomeoneElsesTrip() {
        UUID finished = tripRepository.saveAndFlush(aTrip().build()).getTripId();
        tripRepository.finish(finished, adaId, Instant.now(), 52, 140);
        UUID open = tripRepository.saveAndFlush(aTrip().build()).getTripId();

        // A finished trip is history and stays; an open one is only the
        // driver's to cancel.
        assertThat(tripRepository.deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(finished, adaId)).isEmpty();
        assertThat(tripRepository.deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(open, graceId)).isEmpty();
        entityManager.flush();
        assertThat(tripRepository.count()).isEqualTo(2);
    }

    @Test
    void refusesASecondActiveTripOnTheSameCar() {
        tripRepository.saveAndFlush(aTrip().build());

        // ux_trips_one_active_per_car. This is the invariant the whole feature
        // rests on: it holds even when two requests race, which a service-level
        // check alone could not guarantee.
        assertThatThrownBy(() -> tripRepository.saveAndFlush(
                aTrip().tripDriverId(graceId).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesATripThatEndsBeforeItStarts() {
        Instant started = Instant.now();

        assertThatThrownBy(() -> tripRepository.saveAndFlush(aTrip()
                .tripStartedAt(started)
                .tripEndedAt(started.minusSeconds(60))
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAResultOnATripThatIsStillRunning() {
        // ck_trips_open_has_no_result: a fuel reading or a distance for a trip
        // that has not finished would make the derived expense a fiction.
        assertThatThrownBy(() -> tripRepository.saveAndFlush(
                aTrip().tripFinalFuel(52).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesNegativeFuel() {
        assertThatThrownBy(() -> tripRepository.saveAndFlush(
                aTrip().tripInitialFuel(-1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresACarThatExists() {
        assertThatThrownBy(() -> tripRepository.saveAndFlush(
                aTrip().tripCarId(UUID.randomUUID()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresADriverThatExists() {
        assertThatThrownBy(() -> tripRepository.saveAndFlush(
                aTrip().tripDriverId(UUID.randomUUID()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
