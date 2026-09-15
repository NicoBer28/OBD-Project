package com.obd.api.device;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.device.dto.DeviceDTO;
import com.obd.api.device.exception.DeviceAlreadyPairedException;
import com.obd.api.device.exception.DeviceNotFoundException;
import com.obd.api.device.exception.NoDevicePairedException;
import com.obd.api.group.*;
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
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pairing, resolving and unpairing against a real database, plus the
 * table's own rules. Ada owns the Gol and shares it with Grace; a stranger
 * is in no group.
 */
@RepositoryTest
@Import({DeviceService.class, CarAccess.class})
class DeviceServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String SERIAL = "A4:CF:12:8B:3C:7E";

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
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

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;
    private UUID graceId;
    private UUID strangerId;
    private UUID golId;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newCar(UUID ownerId, String name, Group group) {
        return carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName(name).carGroup(group).build()).getCarId();
    }

    @BeforeEach
    void adaSharesTheGolWithGrace() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        strangerId = newUser("stranger@example.com");
        Group family = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build());
        for (UUID member : new UUID[]{adaId, graceId}) {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .id(new GroupMemberId(family.getGroupId(), member)).role(GroupRole.MEMBER).build());
        }
        golId = newCar(adaId, "Ada's Gol", family);
    }

    private static DeviceDTO.Pair pair(String serial) {
        return new DeviceDTO.Pair(serial);
    }

    // --- pair ------------------------------------------------------------------

    @Test
    void theOwnerPairsADongleToTheirCar() {
        DeviceDTO.Read read = deviceService.pair(adaId, golId, pair(SERIAL));

        assertThat(read.id()).isNotNull();
        assertThat(read.serial()).isEqualTo(SERIAL);
        assertThat(read.carId()).isEqualTo(golId);
        assertThat(read.carName()).isEqualTo("Ada's Gol");
        assertThat(read.pairedAt()).isNotNull();
        assertThat(read.lastSeenAt()).isNull();
    }

    @Test
    void pairNormalisesTheSerial() {
        // However the phone's BLE stack reports it, it is one device.
        DeviceDTO.Read read = deviceService.pair(adaId, golId, pair("  a4:cf:12:8b:3c:7e "));

        assertThat(read.serial()).isEqualTo(SERIAL);
    }

    @Test
    void pairingTheSameSerialAgainIsANoOp() {
        DeviceDTO.Read first = deviceService.pair(adaId, golId, pair(SERIAL));
        DeviceDTO.Read again = deviceService.pair(adaId, golId, pair("a4:cf:12:8b:3c:7e"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.pairedAt()).isEqualTo(first.pairedAt());
        assertThat(deviceRepository.count()).isEqualTo(1);
    }

    @Test
    void pairingANewSerialReplacesTheCarsDongle() {
        DeviceDTO.Read old = deviceService.pair(adaId, golId, pair(SERIAL));
        deviceRepository.touch(SERIAL, Instant.now());

        // Dongle broke, new one plugged in: PUT semantics, one row per car.
        DeviceDTO.Read replaced = deviceService.pair(adaId, golId, pair("B1:B2:B3:B4:B5:B6"));

        assertThat(replaced.id()).isEqualTo(old.id());
        assertThat(replaced.serial()).isEqualTo("B1:B2:B3:B4:B5:B6");
        assertThat(replaced.lastSeenAt()).isNull();          // the new one has not reported
        assertThat(deviceRepository.count()).isEqualTo(1);
        assertThat(deviceRepository.findByDeviceSerial(SERIAL)).isEmpty();
    }

    @Test
    void aDonglePairedElsewhereMustBeUnpairedFirst() {
        UUID otherCar = newCar(adaId, "Ada's other car", null);
        deviceService.pair(adaId, golId, pair(SERIAL));

        // Explicit unpair-then-pair, so readings are never silently
        // re-attributed to a different car.
        assertThatThrownBy(() -> deviceService.pair(adaId, otherCar, pair(SERIAL)))
                .isInstanceOf(DeviceAlreadyPairedException.class);

        deviceService.unpair(adaId, golId);
        assertThat(deviceService.pair(adaId, otherCar, pair(SERIAL)).carId()).isEqualTo(otherCar);
    }

    @Test
    void aGroupMemberMayNotPair() {
        // Grace may drive the Gol; which hardware speaks for it is Ada's call.
        assertThatThrownBy(() -> deviceService.pair(graceId, golId, pair(SERIAL)))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void aStrangerMayNotPair() {
        assertThatThrownBy(() -> deviceService.pair(strangerId, golId, pair(SERIAL)))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- forCar ----------------------------------------------------------------

    @Test
    void aGroupMemberMayReadTheCarsDongle() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        assertThat(deviceService.forCar(graceId, golId).serial()).isEqualTo(SERIAL);
    }

    @Test
    void aCarWithNoDongleSaysSo() {
        assertThatThrownBy(() -> deviceService.forCar(adaId, golId))
                .isInstanceOf(NoDevicePairedException.class);
    }

    @Test
    void aStrangerMayNotReadTheCarsDongle() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        assertThatThrownBy(() -> deviceService.forCar(strangerId, golId))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    void aMembersPhoneResolvesTheDongleToTheCar() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        // Grace's phone connects to "OBD-C", reads the serial, and learns
        // which car it is without Ada's local setup.
        DeviceDTO.Read read = deviceService.resolve(graceId, "a4:cf:12:8b:3c:7e");

        assertThat(read.carId()).isEqualTo(golId);
        assertThat(read.carName()).isEqualTo("Ada's Gol");
    }

    @Test
    void aStrangerCannotResolveADongleTheyMayNotSee() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        // Same answer as an unknown serial: the dongle's existence is not
        // confirmed to anyone outside the family.
        assertThatThrownBy(() -> deviceService.resolve(strangerId, SERIAL))
                .isInstanceOf(DeviceNotFoundException.class);
        assertThatThrownBy(() -> deviceService.resolve(strangerId, "00:00:00:00:00:00"))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    // --- unpair ----------------------------------------------------------------

    @Test
    void theOwnerUnpairsAndTheSerialIsFreeAgain() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        deviceService.unpair(adaId, golId);

        assertThat(deviceRepository.count()).isZero();
        assertThatThrownBy(() -> deviceService.resolve(adaId, SERIAL))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void unpairingACarWithNoDongleIsANoOp() {
        deviceService.unpair(adaId, golId);   // no exception
        assertThat(deviceRepository.count()).isZero();
    }

    @Test
    void aGroupMemberMayNotUnpair() {
        deviceService.pair(adaId, golId, pair(SERIAL));

        assertThatThrownBy(() -> deviceService.unpair(graceId, golId))
                .isInstanceOf(CarNotFoundException.class);
        assertThat(deviceRepository.count()).isEqualTo(1);
    }

    // --- the table's own rules -------------------------------------------------

    @Test
    void touchRecordsWhenTheDongleLastReported() {
        deviceService.pair(adaId, golId, pair(SERIAL));
        Instant at = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(deviceRepository.touch(SERIAL, at)).isEqualTo(1);
        assertThat(deviceService.forCar(adaId, golId).lastSeenAt()).isEqualTo(at);
        // Unknown serial: nothing to touch, and no error.
        assertThat(deviceRepository.touch("NOPE", at)).isZero();
    }

    @Test
    void refusesASerialThatIsNotNormalised() {
        // ck_devices_serial_normalised: a caller that bypasses the service
        // and stores "a4:cf" would create a device resolve() can never find.
        assertThatThrownBy(() -> deviceRepository.saveAndFlush(
                Device.builder().deviceCarId(golId).deviceSerial("a4:cf:12:8b:3c:7e").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesTwoDonglesOnOneCar() {
        deviceRepository.saveAndFlush(Device.builder().deviceCarId(golId).deviceSerial(SERIAL).build());

        assertThatThrownBy(() -> deviceRepository.saveAndFlush(
                Device.builder().deviceCarId(golId).deviceSerial("B1:B2:B3:B4:B5:B6").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheCarDeletesItsDongle() {
        deviceService.pair(adaId, golId, pair(SERIAL));
        entityManager.clear();

        carRepository.deleteById(golId);
        entityManager.flush();
        entityManager.clear();

        assertThat(deviceRepository.findByDeviceSerial(SERIAL)).isEmpty();
    }
}
