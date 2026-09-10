package com.obd.api.telemetry;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.car.ModelRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.group.*;
import com.obd.api.support.RepositoryTest;
import com.obd.api.telemetry.dto.TelemetryDTO;
import com.obd.api.telemetry.dto.TelemetryDTO.Reading;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ingestion end to end against a real Postgres: the idempotent insert, trip
 * stamping, the batch fold and the snapshot guard all interact, so mocking any
 * of them would test less than this does.
 */
@RepositoryTest
@Import({TelemetryService.class, CarAccess.class})
class TelemetryServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private TelemetryRepository telemetryRepository;
    @Autowired
    private CarRepository carRepository;
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

    private final ObjectMapper json = new ObjectMapper();
    private final Instant noon = Instant.parse("2026-09-10T12:00:00Z");

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

    @BeforeEach
    void createOwnerAndCar() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        carId = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(adaId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Ada's Gol")
                .build()).getCarId();
    }

    private static Reading reading(Instant at, Integer fuel) {
        return new Reading(at, -34.6037, -58.3816, 60, fuel, 85, 120_000, null);
    }

    private TelemetryDTO.Ingest batch(Reading... readings) {
        return new TelemetryDTO.Ingest(carId, List.of(readings));
    }

    private Car car() {
        return carRepository.findById(carId).orElseThrow();
    }

    @Test
    void storesEveryReadingOfAFreshBatch() {
        var result = telemetryService.ingest(adaId, batch(
                reading(noon, 70),
                reading(noon.plusSeconds(5), 69),
                reading(noon.plusSeconds(10), 68)));

        assertThat(result.carId()).isEqualTo(carId);
        assertThat(result.stored()).isEqualTo(3);
        assertThat(result.duplicates()).isZero();
        assertThat(result.snapshotUpdated()).isTrue();
        assertThat(result.latestRecordedAt()).isEqualTo(noon.plusSeconds(10));
        assertThat(telemetryRepository.count()).isEqualTo(3);
    }

    @Test
    void countsAResentBatchAsDuplicatesAndLeavesTheSnapshotAlone() {
        var first = batch(reading(noon, 70), reading(noon.plusSeconds(5), 69));
        telemetryService.ingest(adaId, first);

        // The phone was unsure whether the upload landed and sent it again.
        var result = telemetryService.ingest(adaId, first);

        assertThat(result.stored()).isZero();
        assertThat(result.duplicates()).isEqualTo(2);
        // Nothing newer arrived, so nothing to redraw.
        assertThat(result.snapshotUpdated()).isFalse();
        assertThat(telemetryRepository.count()).isEqualTo(2);
    }

    @Test
    void storesTheNewReadingsOfAPartiallyDuplicateBatch() {
        telemetryService.ingest(adaId, batch(reading(noon, 70)));

        // Two already there, one genuinely new - the common shape of a retry
        // that overlaps the previous upload. Had the insert thrown on the first
        // duplicate, the transaction would be aborted and the new one lost.
        var result = telemetryService.ingest(adaId, batch(
                reading(noon, 70),
                reading(noon.plusSeconds(5), 69)));

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(telemetryRepository.count()).isEqualTo(2);
    }

    @Test
    void refreshesTheSnapshotFromTheNewestReading() {
        telemetryService.ingest(adaId, batch(
                reading(noon, 70),
                reading(noon.plusSeconds(10), 68)));

        Car car = car();
        assertThat(car.getCarFuelLevel()).isEqualTo(68);
        assertThat(car.getCarBatteryLevel()).isEqualTo(85);
        assertThat(car.getCarMileage()).isEqualTo(120_000);
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.6037);
        assertThat(car.getCarSnapshotAt()).isEqualTo(noon.plusSeconds(10));
    }

    @Test
    void foldsTheBatchSoAFieldTheNewestReadingLacksIsNotLost() {
        // The newest reading has no fuel level; an older one in the same batch
        // does. Taking just the newest would leave fuel_level null.
        telemetryService.ingest(adaId, batch(
                new Reading(noon, -34.6, -58.3, 60, 64, 85, 120_000, null),
                new Reading(noon.plusSeconds(10), -34.7, -58.4, 70, null, null, null, null)));

        Car car = car();
        assertThat(car.getCarFuelLevel()).isEqualTo(64);
        assertThat(car.getCarBatteryLevel()).isEqualTo(85);
        // The position, however, comes from the newest reading that had one.
        assertThat(car.getCarLocation().getLatitude()).isEqualTo(-34.7);
        assertThat(car.getCarSnapshotAt()).isEqualTo(noon.plusSeconds(10));
    }

    @Test
    void aStaleBatchDoesNotMoveTheSnapshotBackwards() {
        telemetryService.ingest(adaId, batch(reading(noon, 68)));

        // An hour-old buffer flushed after a live reading. It is stored as
        // history, but the car must not "go back" to the fuel it had then.
        var result = telemetryService.ingest(adaId, batch(
                reading(noon.minus(1, ChronoUnit.HOURS), 90)));

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.snapshotUpdated()).isFalse();
        assertThat(car().getCarFuelLevel()).isEqualTo(68);
        assertThat(car().getCarSnapshotAt()).isEqualTo(noon);
    }

    @Test
    void stampsOnlyReadingsTakenAfterTheTripStarted() {
        UUID tripId = tripRepository.saveAndFlush(Trip.builder()
                .tripCarId(carId).tripDriverId(adaId)
                .tripStartedAt(noon).build()).getTripId();

        var result = telemetryService.ingest(adaId, batch(
                reading(noon.minus(10, ChronoUnit.MINUTES), 70),  // parked, before "start"
                reading(noon.plusSeconds(30), 69)));              // on the trip

        assertThat(result.tripId()).isEqualTo(tripId);
        assertThat(telemetryRepository.findByTelemetryTripIdOrderByTelemetryRecordedAtAsc(tripId))
                .extracting(Telemetry::getTelemetryRecordedAt)
                .containsExactly(noon.plusSeconds(30));
        assertThat(telemetryRepository.count()).isEqualTo(2);
    }

    @Test
    void reportsNoTripWhenTheCarIsIdle() {
        var result = telemetryService.ingest(adaId, batch(reading(noon, 70)));

        // The signal the app uses to prompt "start a trip?".
        assertThat(result.tripId()).isNull();
        assertThat(telemetryRepository.findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId))
                .get().extracting(Telemetry::getTelemetryTripId).isNull();
    }

    @Test
    void keepsTheRawFrameVerbatim() {
        var frame = json.readTree("{\"pids\": {\"04\": \"5020\"}}");
        telemetryService.ingest(adaId, batch(
                new Reading(noon, null, null, null, null, null, null, frame)));

        Telemetry stored = telemetryRepository
                .findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId).orElseThrow();
        assertThat(stored.getTelemetryRawFrame()).contains("5020");
        // A frame the decoder could not read still gets stored - with nothing
        // to refresh the snapshot from, the car's cached values stay null.
        assertThat(car().getCarFuelLevel()).isNull();
        // ...but snapshot_at still advances: the car did report at that time.
        assertThat(car().getCarSnapshotAt()).isEqualTo(noon);
    }

    // --- history -------------------------------------------------------------

    private TelemetryDTO.Query query(Instant since, Integer limit) {
        return new TelemetryDTO.Query(carId, since, limit);
    }

    private void ingestMinutes(int... minutes) {
        for (int m : minutes) {
            telemetryService.ingest(adaId, batch(reading(noon.plus(m, ChronoUnit.MINUTES), 70 - m)));
        }
    }

    @Test
    void historyReturnsReadingsAfterTheCursorOldestFirst() {
        ingestMinutes(0, 1, 2, 3);

        var page = telemetryService.history(adaId, query(noon.plus(1, ChronoUnit.MINUTES), null));

        // Strictly after the cursor: the reading *at* the cursor is the one the
        // client was given last time, so returning it again is a duplicate.
        assertThat(page.readings()).extracting(TelemetryDTO.Read::recordedAt)
                .containsExactly(noon.plus(2, ChronoUnit.MINUTES), noon.plus(3, ChronoUnit.MINUTES));
        assertThat(page.readings()).extracting(TelemetryDTO.Read::fuelLevel).containsExactly(68, 67);
        assertThat(page.nextSince()).isEqualTo(noon.plus(3, ChronoUnit.MINUTES));
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void historyPagesTheWholeSeriesWithoutGapsOrRepeats() {
        ingestMinutes(0, 1, 2, 3, 4);

        // The client's sync loop, exactly as it would run it.
        var seen = new java.util.ArrayList<Instant>();
        Instant cursor = null;
        boolean more;
        int calls = 0;
        do {
            var page = telemetryService.history(adaId, query(cursor, 2));
            page.readings().forEach(r -> seen.add(r.recordedAt()));
            cursor = page.nextSince();
            more = page.hasMore();
            calls++;
        } while (more);

        assertThat(seen).containsExactly(
                noon, noon.plus(1, ChronoUnit.MINUTES), noon.plus(2, ChronoUnit.MINUTES),
                noon.plus(3, ChronoUnit.MINUTES), noon.plus(4, ChronoUnit.MINUTES));
        // 2 + 2 + 1: the last page knows it is last without an extra empty call.
        assertThat(calls).isEqualTo(3);
    }

    @Test
    void historyOfACarThatNeverReportedIsEmptyAndKeepsTheCursor() {
        var page = telemetryService.history(adaId, query(null, null));

        assertThat(page.readings()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        // The client can store nextSince unconditionally.
        assertThat(page.nextSince()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void historyCarriesTheTripAndTheRawFrame() {
        UUID tripId = tripRepository.saveAndFlush(Trip.builder()
                .tripCarId(carId).tripDriverId(adaId).tripStartedAt(noon).build()).getTripId();
        var frame = json.readTree("{\"pids\": {\"04\": \"5020\"}}");
        telemetryService.ingest(adaId, batch(
                new Reading(noon.plusSeconds(30), -34.6, -58.3, 60, 70, 85, 120_000, frame)));

        var read = telemetryService.history(adaId, query(null, null)).readings().getFirst();

        assertThat(read.tripId()).isEqualTo(tripId);
        assertThat(read.receivedAt()).isNotNull();
        assertThat(read.latitude()).isEqualTo(-34.6);
        assertThat(read.raw()).contains("5020");
    }

    @Test
    void historyRefusesSomeoneElsesCarAsIfItDidNotExist() {
        ingestMinutes(0);

        assertThatThrownBy(() -> telemetryService.history(graceId, query(null, null)))
                .isInstanceOf(CarNotFoundException.class);
    }

    @Test
    void aGroupMemberMayUploadForASharedCar() {
        Group family = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build());
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .id(new GroupMemberId(family.getGroupId(), graceId))
                .role(GroupRole.MEMBER).build());
        Car car = carRepository.findById(carId).orElseThrow();
        car.setCarGroup(family);
        carRepository.saveAndFlush(car);

        // Grace's phone is the one connected to the dongle in Ada's car. The
        // README limitation "only the owner's phone can relay" no longer holds.
        var result = telemetryService.ingest(graceId, batch(reading(noon, 70)));

        assertThat(result.stored()).isEqualTo(1);
        assertThat(car().getCarFuelLevel()).isEqualTo(70);
        assertThat(telemetryService.history(graceId, query(null, null)).readings()).hasSize(1);
    }

    @Test
    void refusesSomeoneElsesCarAsIfItDidNotExist() {
        assertThatThrownBy(() -> telemetryService.ingest(graceId, batch(reading(noon, 70))))
                .isInstanceOf(CarNotFoundException.class);
        assertThat(telemetryRepository.count()).isZero();
    }
}
