package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.model.ModelRepository;
import com.obd.api.car.exception.CarNotFoundException;
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
