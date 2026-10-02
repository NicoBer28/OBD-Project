package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.model.ModelRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.trip.exception.CarAlreadyOnATripException;
import com.obd.api.trip.exception.TripAlreadyEndedException;
import com.obd.api.trip.exception.TripEndsBeforeItStartsException;
import com.obd.api.trip.exception.TripTimeOutOfRangeException;
import com.obd.api.trip.exception.TripNotFoundException;
import com.obd.api.group.*;
import com.obd.api.devicetoken.DeviceScope;
import com.obd.api.support.RepositoryTest;
import com.obd.api.telemetry.TelemetryRepository;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may start a trip, against a real database. This is where the access
 * rule is exercised through a caller rather than in isolation: TripService
 * never learned about groups, and must not need to.
 */
@RepositoryTest
@Import({TripService.class, CarAccess.class, DeviceScope.class})
class TripServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private TripService tripService;
    @Autowired
    private TelemetryRepository telemetryRepository;
    @Autowired
    private CarRepository carRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;
    private UUID graceId;
    private UUID strangerId;
    private Car adasCar;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    @BeforeEach
    void adaOwnsACarAndSharesItWithGrace() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        strangerId = newUser("stranger@example.com");

        Group family = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build());
        for (UUID member : new UUID[]{adaId, graceId}) {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .id(new GroupMemberId(family.getGroupId(), member))
                    .role(GroupRole.MEMBER).build());
        }

        adasCar = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(adaId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Ada's Gol")
                .carGroup(family)
                .build());
    }

    private static TripDTO.Create startOn(Car car) {
        return new TripDTO.Create(car.getCarId(), 70, null, null);
    }

    @Test
    void theOwnerMayStartATrip() {
        TripDTO.Read trip = tripService.start(adaId, startOn(adasCar)).trip();

        assertThat(trip.driverId()).isEqualTo(adaId);
        assertThat(trip.carId()).isEqualTo(adasCar.getCarId());
        assertThat(trip.active()).isTrue();
    }

    @Test
    void aGroupMemberMayStartATripOnASharedCar() {
        // Grace does not own the car. She is in the group it is shared with,
        // which is the entire point of the app - and TripService did not
        // change a line to allow it: CarAccess did.
        TripDTO.Read trip = tripService.start(graceId, startOn(adasCar)).trip();

        assertThat(trip.driverId()).isEqualTo(graceId);
        assertThat(trip.carId()).isEqualTo(adasCar.getCarId());
    }

    @Test
    void aStrangerIsToldTheCarDoesNotExist() {
        assertThatThrownBy(() -> tripService.start(strangerId, startOn(adasCar)))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- reads -------------------------------------------------------------------

    @Autowired
    private TripRepository tripRepository;

    private UUID finishedTrip(UUID carId, UUID driverId) {
        Trip t = tripRepository.saveAndFlush(Trip.builder().tripCarId(carId).tripDriverId(driverId).build());
        t.setTripEndedAt(java.time.Instant.now());
        return tripRepository.saveAndFlush(t).getTripId();
    }

    @Test
    void getTripsListsOnlyTheCallersTrips() {
        UUID mine = finishedTrip(adasCar.getCarId(), adaId);
        UUID graces = finishedTrip(adasCar.getCarId(), graceId);

        assertThat(tripService.getTrips(adaId)).extracting(TripDTO.Read::id).containsExactly(mine);
        assertThat(tripService.getTrips(graceId)).extracting(TripDTO.Read::id).containsExactly(graces);
        assertThat(tripService.getTrips(strangerId)).isEmpty();
    }

    @Test
    void getCarTripsIsTheCarsWholeHistoryForAnyoneWhoMayReadIt() {
        UUID adasTrip = finishedTrip(adasCar.getCarId(), adaId);
        UUID gracesTrip = finishedTrip(adasCar.getCarId(), graceId);
        finishedTrip(carRepository.saveAndFlush(Car.builder()
                .carOwnerId(adaId).carName("Other")
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow()).build()).getCarId(), adaId);

        // The owner sees who drove their car; a member sees the same. That is
        // what a shared car's history is for.
        assertThat(tripService.getCarTrips(adaId, adasCar.getCarId()))
                .extracting(TripDTO.Read::id).containsExactlyInAnyOrder(adasTrip, gracesTrip);
        assertThat(tripService.getCarTrips(graceId, adasCar.getCarId()))
                .extracting(TripDTO.Read::driverId).containsExactlyInAnyOrder(adaId, graceId);
    }

    @Test
    void getCarTripsIsRefusedForACarTheCallerMayNotSee() {
        finishedTrip(adasCar.getCarId(), adaId);

        assertThatThrownBy(() -> tripService.getCarTrips(strangerId, adasCar.getCarId()))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void historiesAreNewestFirst() throws InterruptedException {
        UUID older = finishedTrip(adasCar.getCarId(), adaId);
        Thread.sleep(5);
        UUID newer = finishedTrip(adasCar.getCarId(), adaId);

        assertThat(tripService.getTrips(adaId)).extracting(TripDTO.Read::id).containsExactly(newer, older);
        assertThat(tripService.getCarTrips(adaId, adasCar.getCarId())).extracting(TripDTO.Read::id).containsExactly(newer, older);
    }

    @Test
    void activeReturnsTheOpenTripToAnyMember() {
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar)).trip();

        // Ada (owner) and Grace (member) both see who has the car right now.
        assertThat(tripService.active(adaId, adasCar.getCarId()))
                .get().extracting(TripDTO.Read::id).isEqualTo(open.id());
        assertThat(tripService.active(graceId, adasCar.getCarId()))
                .get().extracting(TripDTO.Read::driverId).isEqualTo(graceId);
    }

    @Test
    void activeIsEmptyForAnIdleCar() {
        finishedTrip(adasCar.getCarId(), adaId);   // history, not an open trip

        // The usual case, and not an error.
        assertThat(tripService.active(adaId, adasCar.getCarId())).isEmpty();
    }

    @Test
    void activeIsRefusedForACarTheCallerMayNotSee() {
        tripService.start(adaId, startOn(adasCar));

        assertThatThrownBy(() -> tripService.active(strangerId, adasCar.getCarId()))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- finish / cancel ---------------------------------------------------------

    private static TripDTO.finish result(Integer finalFuel, Integer km) {
        return new TripDTO.finish(finalFuel, BigDecimal.valueOf(km), null);
    }

    /** A finish that claims its own end time, as an offline sync does. */
    private static TripDTO.finish result(Integer finalFuel, Integer km, Instant endedAt) {
        return new TripDTO.finish(finalFuel, BigDecimal.valueOf(km), endedAt);
    }

    @Test
    void theDriverFinishesAndTheExpenseIsDerived() {
        TripDTO.Read open = tripService.start(graceId, new TripDTO.Create(adasCar.getCarId(), 70, null, null)).trip();

        TripDTO.Read done = tripService.finish(graceId, open.id(), result(52, 140));

        assertThat(done.active()).isFalse();
        assertThat(done.endedAt()).isNotNull();
        assertThat(done.finalFuel()).isEqualTo(52);
        assertThat(done.distanceKm()).isEqualByComparingTo("140");
        // 70 - 52, computed on read - never stored.
        assertThat(done.fuelUsed()).isEqualTo(18);
        assertThat(tripService.active(adaId, adasCar.getCarId())).isEmpty();
    }

    @Test
    void refuellingMakesTheExpenseNegativeAndThatIsAllowed() {
        TripDTO.Read open = tripService.start(adaId, new TripDTO.Create(adasCar.getCarId(), 20, null, null)).trip();

        TripDTO.Read done = tripService.finish(adaId, open.id(), result(60, 30));

        assertThat(done.fuelUsed()).isEqualTo(-40);   // filled up on the way
    }

    @Test
    void theOwnerMayNotFinishAMembersTrip() {
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar)).trip();

        // Same answer as a trip that does not exist.
        assertThatThrownBy(() -> tripService.finish(adaId, open.id(), result(52, 140)))
                .isInstanceOf(TripNotFoundException.class);
        assertThat(tripService.active(adaId, adasCar.getCarId())).isPresent();
    }

    @Test
    void finishingTwiceIsRefused() {
        TripDTO.Read open = tripService.start(adaId, startOn(adasCar)).trip();
        tripService.finish(adaId, open.id(), result(52, 140));

        assertThatThrownBy(() -> tripService.finish(adaId, open.id(), result(10, 999)))
                .isInstanceOf(TripAlreadyEndedException.class);
    }

    @Test
    void finishingAnUnknownTripIsNotFound() {
        assertThatThrownBy(() -> tripService.finish(adaId, UUID.randomUUID(), result(52, 140)))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void theDriverCancelsATripStartedByMistake() {
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar)).trip();

        tripService.delete(graceId, open.id());

        assertThat(tripRepository.findById(open.id())).isEmpty();
        assertThat(tripService.active(adaId, adasCar.getCarId())).isEmpty();
    }

    @Test
    void cancellingAFinishedTripIsAConflictHistoryIsNeverDeleted() {
        TripDTO.Read finished = tripService.start(adaId, startOn(adasCar)).trip();
        tripService.finish(adaId, finished.id(), result(52, 140));

        assertThatThrownBy(() -> tripService.delete(adaId, finished.id()))
                .isInstanceOf(TripAlreadyEndedException.class);
        assertThat(tripRepository.findById(finished.id())).isPresent();
    }

    @Test
    void cancellingSomeoneElsesTripOrAnUnknownOneIsNotFound() {
        TripDTO.Read graces = tripService.start(graceId, startOn(adasCar)).trip();

        // Same answer for both, so a trip id is never confirmed to a stranger -
        // and the owner of the car cannot cancel a trip out from under the driver.
        assertThatThrownBy(() -> tripService.delete(adaId, graces.id()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> tripService.delete(adaId, UUID.randomUUID()))
                .isInstanceOf(TripNotFoundException.class);
        assertThat(tripRepository.findById(graces.id())).isPresent();
    }

    @Test
    void unsharingRevokesTheMembersAccess() {
        adasCar.setCarGroup(null);
        carRepository.saveAndFlush(adasCar);

        assertThatThrownBy(() -> tripService.start(graceId, startOn(adasCar)))
                .isInstanceOf(CarNotFoundException.class);
        // Ada is unaffected.
        assertThat(tripService.start(adaId, startOn(adasCar)).trip().active()).isTrue();
    }

    // --- idempotency: clientTripId --------------------------------------------

    @Test
    void repeatingTheClientsTripIdReturnsTheSameTripInsteadOfAConflict() {
        UUID clientId = UUID.randomUUID();
        TripDTO.Create request = new TripDTO.Create(adasCar.getCarId(), 70, null, clientId);

        TripService.Started first = tripService.start(adaId, request);
        TripService.Started retry = tripService.start(adaId, request);

        // The case this exists for: the server created the trip and the
        // response was lost. Without the key the retry would get 409 and the
        // phone could not tell its own trip from another driver's.
        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.trip().id()).isEqualTo(first.trip().id());
        assertThat(tripRepository.count()).isEqualTo(1);
    }

    @Test
    void theSameClientTripIdFromAnotherDriverIsADifferentTrip() {
        UUID clientId = UUID.randomUUID();
        tripService.start(adaId, new TripDTO.Create(adasCar.getCarId(), 70, null, clientId));
        tripService.finish(adaId, tripService.getTrips(adaId).getFirst().id(), result(60, 10));

        // Scoped to the driver: keying on the value alone would hand Ada's
        // trip to Grace because she happened to send the same UUID.
        TripService.Started graces = tripService.start(graceId,
                new TripDTO.Create(adasCar.getCarId(), 70, null, clientId));

        assertThat(graces.created()).isTrue();
        assertThat(tripRepository.count()).isEqualTo(2);
    }

    @Test
    void anUnrelatedStartOnABusyCarStillConflicts() {
        tripService.start(graceId, startOn(adasCar));

        // The key makes a retry safe; it does not make a second driver legal.
        assertThatThrownBy(() -> tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, null, UUID.randomUUID())))
                .isInstanceOf(CarAlreadyOnATripException.class);
    }

    // --- the client's clock ---------------------------------------------------

    @Test
    void aBackdatedStartIsKeptAsGiven() {
        Instant inTheTunnel = Instant.now().minus(2, ChronoUnit.HOURS);

        TripDTO.Read trip = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, inTheTunnel, null)).trip();

        // What the phone claims is history; createdAt is when we heard it.
        assertThat(trip.startedAt()).isEqualTo(inTheTunnel);
        assertThat(trip.createdAt()).isAfter(inTheTunnel);
    }

    @Test
    void aStartInTheFutureOrTooOldIsRefused() {
        assertThatThrownBy(() -> tripService.start(adaId, new TripDTO.Create(
                adasCar.getCarId(), 70, Instant.now().plus(1, ChronoUnit.HOURS), null)))
                .isInstanceOf(TripTimeOutOfRangeException.class);

        assertThatThrownBy(() -> tripService.start(adaId, new TripDTO.Create(
                adasCar.getCarId(), 70, Instant.now().minus(31, ChronoUnit.DAYS), null)))
                .isInstanceOf(TripTimeOutOfRangeException.class);

        // A few minutes of clock skew is normal and accepted.
        assertThat(tripService.start(adaId, new TripDTO.Create(
                adasCar.getCarId(), 70, Instant.now().plus(2, ChronoUnit.MINUTES), null)).created()).isTrue();
    }

    @Test
    void aClientSuppliedEndTimeIsKept() {
        Instant startedAt = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant endedAt = startedAt.plus(30, ChronoUnit.MINUTES);
        TripDTO.Read open = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();

        TripDTO.Read done = tripService.finish(adaId, open.id(), result(52, 140, endedAt));

        assertThat(done.endedAt()).isEqualTo(endedAt);
        assertThat(done.active()).isFalse();
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        TripDTO.Read open = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();

        // A phone whose clock moved backwards between the two events. The
        // database would refuse it anyway; this makes it a 400, not a 500.
        assertThatThrownBy(() -> tripService.finish(adaId, open.id(),
                result(52, 140, startedAt.minus(10, ChronoUnit.MINUTES))))
                .isInstanceOf(TripEndsBeforeItStartsException.class);

        assertThat(tripRepository.findById(open.id()).orElseThrow().getTripEndedAt()).isNull();
    }

    @Test
    void distanceKeepsItsDecimals() {
        TripDTO.Read open = tripService.start(adaId, startOn(adasCar)).trip();

        TripDTO.Read done = tripService.finish(adaId, open.id(),
                new TripDTO.finish(52, new BigDecimal("140.75"), null));

        // The reason the column changed type: the native client sends decimals
        // and an integer truncated them silently.
        assertThat(done.distanceKm()).isEqualByComparingTo("140.75");
    }

    // --- the route, derived by time window ------------------------------------

    /** A reading for a car at an instant, with or without a position. */
    private void reading(UUID carId, Instant at, Double lat, Double lon, Integer speed, UUID tripId) {
        telemetryRepository.insertIgnoringDuplicates(carId, tripId, at, lat, lon, speed, null, null, null, null);
    }

    @Test
    void theRouteIsFoundEvenWhenNoReadingWasStampedWithTheTrip() {
        Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant endedAt = startedAt.plus(30, ChronoUnit.MINUTES);

        // The failure this exists for: the phone uploads the tunnel's readings
        // BEFORE it uploads the trip, so no trip was open at ingest and
        // trip_id stayed null on every row. Nothing ever re-stamps them.
        reading(adasCar.getCarId(), startedAt.plusSeconds(60), -34.6037, -58.3816, 10, null);
        reading(adasCar.getCarId(), startedAt.plusSeconds(120), -34.6040, -58.3820, 40, null);

        TripDTO.Read trip = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();
        tripService.finish(adaId, trip.id(), result(52, 140, endedAt));

        List<TripDTO.RoutePoint> route = tripService.route(adaId, trip.id());

        assertThat(route).hasSize(2);
        assertThat(route.getFirst().latitude()).isEqualTo(-34.6037);
        assertThat(route).extracting(TripDTO.RoutePoint::recordedAt).isSorted();
    }

    @Test
    void theRouteExcludesReadingsOutsideTheTripAndWithoutAPosition() {
        Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant endedAt = startedAt.plus(30, ChronoUnit.MINUTES);

        reading(adasCar.getCarId(), startedAt.minusSeconds(600), -34.0, -58.0, 0, null);   // parked before
        reading(adasCar.getCarId(), startedAt.plusSeconds(60), -34.6037, -58.3816, 10, null);
        // Fuel-only frame: no coordinates, so it draws nothing.
        reading(adasCar.getCarId(), startedAt.plusSeconds(90), null, null, 20, null);
        reading(adasCar.getCarId(), endedAt.plusSeconds(600), -34.9, -58.9, 0, null);      // parked after

        TripDTO.Read trip = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();
        tripService.finish(adaId, trip.id(), result(52, 140, endedAt));

        assertThat(tripService.route(adaId, trip.id()))
                .singleElement()
                .extracting(TripDTO.RoutePoint::latitude).isEqualTo(-34.6037);
    }

    @Test
    void anOpenTripsRouteRunsToNow() {
        Instant startedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        TripDTO.Read open = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();
        reading(adasCar.getCarId(), startedAt.plusSeconds(60), -34.6037, -58.3816, 10, null);

        // endedAt is null, so the window has no upper bound.
        assertThat(tripService.route(adaId, open.id())).hasSize(1);
    }

    @Test
    void anotherCarsReadingsNeverLeakIntoTheRoute() {
        Car graces = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(graceId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Grace's own")
                .build());
        Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS);

        reading(graces.getCarId(), startedAt.plusSeconds(60), -31.0, -64.0, 50, null);
        reading(adasCar.getCarId(), startedAt.plusSeconds(60), -34.6037, -58.3816, 10, null);

        TripDTO.Read trip = tripService.start(adaId,
                new TripDTO.Create(adasCar.getCarId(), 70, startedAt, null)).trip();

        assertThat(tripService.route(adaId, trip.id()))
                .singleElement()
                .extracting(TripDTO.RoutePoint::latitude).isEqualTo(-34.6037);
    }

    @Test
    void aTripNobodyMayReadHasNoRoute() {
        TripDTO.Read trip = tripService.start(adaId, startOn(adasCar)).trip();

        assertThatThrownBy(() -> tripService.route(strangerId, trip.id()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> tripService.route(adaId, UUID.randomUUID()))
                .isInstanceOf(TripNotFoundException.class);
    }
}
