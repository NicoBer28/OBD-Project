package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.model.ModelRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.trip.exception.CannotDeleteTripException;
import com.obd.api.trip.exception.CarNotReadableException;
import com.obd.api.trip.exception.TripAlreadyEndedException;
import com.obd.api.trip.exception.TripNotFoundException;
import com.obd.api.group.*;
import com.obd.api.support.RepositoryTest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may start a trip, against a real database. This is where the access
 * rule is exercised through a caller rather than in isolation: TripService
 * never learned about groups, and must not need to.
 */
@RepositoryTest
@Import({TripService.class, CarAccess.class})
class TripServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private TripService tripService;
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
        return new TripDTO.Create(car.getCarId(), 70);
    }

    @Test
    void theOwnerMayStartATrip() {
        TripDTO.Read trip = tripService.start(adaId, startOn(adasCar));

        assertThat(trip.driverId()).isEqualTo(adaId);
        assertThat(trip.carId()).isEqualTo(adasCar.getCarId());
        assertThat(trip.active()).isTrue();
    }

    @Test
    void aGroupMemberMayStartATripOnASharedCar() {
        // Grace does not own the car. She is in the group it is shared with,
        // which is the entire point of the app - and TripService did not
        // change a line to allow it: CarAccess did.
        TripDTO.Read trip = tripService.start(graceId, startOn(adasCar));

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
                .isInstanceOf(CarNotReadableException.class);
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
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar));

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
                .isInstanceOf(CarNotReadableException.class);
    }

    // --- finish / cancel ---------------------------------------------------------

    private static TripDTO.finish result(Integer finalFuel, Integer distance) {
        return new TripDTO.finish(finalFuel, distance);
    }

    @Test
    void theDriverFinishesAndTheExpenseIsDerived() {
        TripDTO.Read open = tripService.start(graceId, new TripDTO.Create(adasCar.getCarId(), 70));

        TripDTO.Read done = tripService.finish(graceId, open.id(), result(52, 140));

        assertThat(done.active()).isFalse();
        assertThat(done.endedAt()).isNotNull();
        assertThat(done.finalFuel()).isEqualTo(52);
        assertThat(done.distance()).isEqualTo(140);
        // 70 - 52, computed on read - never stored.
        assertThat(done.fuelUsed()).isEqualTo(18);
        assertThat(tripService.active(adaId, adasCar.getCarId())).isEmpty();
    }

    @Test
    void refuellingMakesTheExpenseNegativeAndThatIsAllowed() {
        TripDTO.Read open = tripService.start(adaId, new TripDTO.Create(adasCar.getCarId(), 20));

        TripDTO.Read done = tripService.finish(adaId, open.id(), result(60, 30));

        assertThat(done.fuelUsed()).isEqualTo(-40);   // filled up on the way
    }

    @Test
    void theOwnerMayNotFinishAMembersTrip() {
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar));

        // Same answer as a trip that does not exist.
        assertThatThrownBy(() -> tripService.finish(adaId, open.id(), result(52, 140)))
                .isInstanceOf(TripNotFoundException.class);
        assertThat(tripService.active(adaId, adasCar.getCarId())).isPresent();
    }

    @Test
    void finishingTwiceIsRefused() {
        TripDTO.Read open = tripService.start(adaId, startOn(adasCar));
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
        TripDTO.Read open = tripService.start(graceId, startOn(adasCar));

        TripDTO.Read removed = tripService.delete(graceId, open.id());

        assertThat(removed.id()).isEqualTo(open.id());
        assertThat(tripRepository.findById(open.id())).isEmpty();
        assertThat(tripService.active(adaId, adasCar.getCarId())).isEmpty();
    }

    @Test
    void cancelIsRefusedForAFinishedTripSomeoneElsesTripAndAnUnknownOne() {
        TripDTO.Read finished = tripService.start(adaId, startOn(adasCar));
        tripService.finish(adaId, finished.id(), result(52, 140));
        TripDTO.Read graces = tripService.start(graceId, startOn(adasCar));

        assertThatThrownBy(() -> tripService.delete(adaId, finished.id()))
                .isInstanceOf(CannotDeleteTripException.class);
        assertThatThrownBy(() -> tripService.delete(adaId, graces.id()))
                .isInstanceOf(CannotDeleteTripException.class);
        assertThatThrownBy(() -> tripService.delete(adaId, UUID.randomUUID()))
                .isInstanceOf(CannotDeleteTripException.class);
        assertThat(tripRepository.count()).isEqualTo(2);
    }

    @Test
    void unsharingRevokesTheMembersAccess() {
        adasCar.setCarGroup(null);
        carRepository.saveAndFlush(adasCar);

        assertThatThrownBy(() -> tripService.start(graceId, startOn(adasCar)))
                .isInstanceOf(CarNotFoundException.class);
        // Ada is unaffected.
        assertThat(tripService.start(adaId, startOn(adasCar)).active()).isTrue();
    }
}
