package com.obd.api.car;

import com.obd.api.car.dto.CarDTO;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.group.*;
import com.obd.api.invitation.exception.NotAMemberException;
import com.obd.api.model.ModelRepository;
import com.obd.api.support.RepositoryTest;
import com.obd.api.trip.Trip;
import com.obd.api.trip.TripRepository;
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
 * Sharing, against a real database. Ada owns the Gol; Ada and Grace are in
 * Familia; Ada is also in Amigos; a stranger is in no group.
 */
@RepositoryTest
@Import({CarService.class, CarAccess.class, GroupAccess.class})
class CarServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private CarService carService;
    @Autowired
    private CarRepository carRepository;
    @Autowired
    private CarAccess carAccess;
    @Autowired
    private TripRepository tripRepository;
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
    private UUID familiaId;
    private UUID amigosId;
    private UUID golId;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newGroup(String name, UUID... members) {
        UUID id = groupRepository.saveAndFlush(Group.builder().groupName(name).build()).getGroupId();
        for (UUID member : members) {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .id(new GroupMemberId(id, member)).role(GroupRole.MEMBER).build());
        }
        return id;
    }

    private UUID newCar(UUID ownerId, String name) {
        return carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName(name).build()).getCarId();
    }

    @BeforeEach
    void setUp() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        strangerId = newUser("stranger@example.com");
        familiaId = newGroup("Familia", adaId, graceId);
        amigosId = newGroup("Amigos", adaId);
        golId = newCar(adaId, "Ada's Gol");
    }

    private static CarDTO.Share with(UUID groupId) {
        return new CarDTO.Share(groupId);
    }

    // --- getCar ------------------------------------------------------------------

    @Test
    void theOwnerReadsTheirCar() {
        CarDTO.Read read = carService.getCar(adaId, golId);

        assertThat(read.id()).isEqualTo(golId);
        assertThat(read.name()).isEqualTo("Ada's Gol");
        assertThat(read.model()).isNotNull();
        assertThat(read.group()).isNull();
    }

    @Test
    void aGroupMemberReadsASharedCarAStrangerNeverDoes() {
        assertThatThrownBy(() -> carService.getCar(graceId, golId))
                .isInstanceOf(CarNotFoundException.class);

        carService.share(adaId, golId, with(familiaId));

        assertThat(carService.getCar(graceId, golId).group().id()).isEqualTo(familiaId);
        // Not in Familia: same answer as for a car that does not exist.
        assertThatThrownBy(() -> carService.getCar(strangerId, golId))
                .isInstanceOf(CarNotFoundException.class);
        assertThatThrownBy(() -> carService.getCar(adaId, UUID.randomUUID()))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- share -------------------------------------------------------------------

    @Test
    void sharingMakesTheCarUsableByEveryMember() {
        assertThat(carAccess.readableBy(graceId, golId)).isEmpty();

        CarDTO.Read read = carService.share(adaId, golId, with(familiaId));

        assertThat(read.group()).isNotNull();
        assertThat(read.group().id()).isEqualTo(familiaId);
        assertThat(read.group().name()).isEqualTo("Familia");
        // The write that makes the READABLE predicate true for Grace: she can
        // now see it, and - through the same predicate - drive and upload.
        assertThat(carAccess.readableBy(graceId, golId)).isPresent();
        assertThat(carService.getCars(graceId)).extracting(CarDTO.Read::id).containsExactly(golId);
    }

    @Test
    void sharingWithTheSameGroupAgainIsANoOp() {
        carService.share(adaId, golId, with(familiaId));
        CarDTO.Read again = carService.share(adaId, golId, with(familiaId));

        assertThat(again.group().id()).isEqualTo(familiaId);
    }

    @Test
    void sharingWithAnotherGroupMovesTheCar() {
        carService.share(adaId, golId, with(familiaId));

        // One group at a time: Amigos gains it, Familia (and Grace) lose it.
        CarDTO.Read moved = carService.share(adaId, golId, with(amigosId));

        assertThat(moved.group().id()).isEqualTo(amigosId);
        assertThat(carAccess.readableBy(graceId, golId)).isEmpty();
        assertThat(carService.forGroup(adaId, familiaId)).isEmpty();
        assertThat(carService.forGroup(adaId, amigosId)).extracting(CarDTO.Read::id).containsExactly(golId);
    }

    @Test
    void aMemberMayNotShareACarTheyDoNotOwn() {
        carService.share(adaId, golId, with(familiaId));

        // Grace can use the Gol; giving it to another group is Ada's call.
        assertThatThrownBy(() -> carService.share(graceId, golId, with(familiaId)))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void anOwnerMayNotShareIntoAGroupTheyAreNotIn() {
        UUID othersGroup = newGroup("Not mine", strangerId);

        // Same answer as an unknown group: this cannot be used to probe ids.
        assertThatThrownBy(() -> carService.share(adaId, golId, with(othersGroup)))
                .isInstanceOf(NotAMemberException.class);
        assertThatThrownBy(() -> carService.share(adaId, golId, with(UUID.randomUUID())))
                .isInstanceOf(NotAMemberException.class);
        assertThat(carRepository.findById(golId).orElseThrow().getCarGroup()).isNull();
    }

    @Test
    void sharingLeavesAnOpenTripAlone() {
        carService.share(adaId, golId, with(familiaId));
        UUID tripId = tripRepository.saveAndFlush(Trip.builder()
                .tripCarId(golId).tripDriverId(graceId).build()).getTripId();

        // Ada un-shares while Grace is driving. The trip is a fact about the
        // past and present, not a permission: it stays open, stays Grace's.
        carService.unshare(adaId, golId);

        Trip trip = tripRepository.findById(tripId).orElseThrow();
        assertThat(trip.isActive()).isTrue();
        assertThat(trip.getTripDriverId()).isEqualTo(graceId);
        // ...but Grace can no longer *start* another one.
        assertThat(carAccess.readableBy(graceId, golId)).isEmpty();
    }

    // --- unshare -----------------------------------------------------------------

    @Test
    void unsharingRevokesEveryMembersAccess() {
        carService.share(adaId, golId, with(familiaId));

        carService.unshare(adaId, golId);

        assertThat(carRepository.findById(golId).orElseThrow().getCarGroup()).isNull();
        assertThat(carAccess.readableBy(graceId, golId)).isEmpty();
        assertThat(carService.getCar(adaId, golId).group()).isNull();
    }

    @Test
    void unsharingAnUnsharedCarIsANoOp() {
        carService.unshare(adaId, golId);
        assertThat(carRepository.findById(golId).orElseThrow().getCarGroup()).isNull();
    }

    @Test
    void aMemberMayNotUnshare() {
        carService.share(adaId, golId, with(familiaId));

        assertThatThrownBy(() -> carService.unshare(graceId, golId))
                .isInstanceOf(CarNotFoundException.class);
        assertThat(carAccess.readableBy(graceId, golId)).isPresent();
    }

    // --- forGroup ----------------------------------------------------------------

    @Test
    void forGroupListsTheGroupsCarsByNameForAnyMember() {
        UUID onix = newCar(adaId, "B Onix");
        UUID punto = newCar(graceId, "A Punto");
        carService.share(adaId, golId, with(familiaId));
        carService.share(adaId, onix, with(amigosId));      // elsewhere: excluded
        carService.share(graceId, punto, with(familiaId));

        assertThat(carService.forGroup(graceId, familiaId))
                .extracting(CarDTO.Read::name)
                .containsExactly("A Punto", "Ada's Gol");
    }

    @Test
    void forGroupIsEmptyNotAnErrorForAGroupWithNoCars() {
        assertThat(carService.forGroup(adaId, amigosId)).isEmpty();
    }

    @Test
    void forGroupRefusesANonMember() {
        carService.share(adaId, golId, with(familiaId));

        assertThatThrownBy(() -> carService.forGroup(strangerId, familiaId))
                .isInstanceOf(NotAMemberException.class);
    }
}
