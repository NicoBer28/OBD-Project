package com.obd.api.telemetry;

import com.obd.api.car.Car;
import com.obd.api.car.CarRepository;
import com.obd.api.car.Coordinates;
import com.obd.api.model.ModelRepository;
import com.obd.api.support.RepositoryTest;
import com.obd.api.trip.Trip;
import com.obd.api.trip.TripRepository;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class TelemetryRepositoryTest {

    /** Seeded by V2__cars_and_models.sql - fixed id so tests can rely on it. */
    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private TelemetryRepository telemetryRepository;
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

    private UUID ownerId;
    private UUID carId;
    private final Instant noon = Instant.parse("2026-09-09T12:00:00Z");

    private UUID newCar(String name) {
        return carRepository.saveAndFlush(Car.builder()
                .carOwnerId(ownerId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName(name)
                .build()).getCarId();
    }

    @BeforeEach
    void createOwnerAndCar() {
        ownerId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace")
                .userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
        carId = newCar("Ada's Gol");
    }

    private Telemetry.TelemetryBuilder aReading(Instant recordedAt) {
        return Telemetry.builder()
                .telemetryCarId(carId)
                .telemetryRecordedAt(recordedAt)
                .telemetrySpeed(60)
                .telemetryFuelLevel(70)
                .telemetryBatteryLevel(85)
                .telemetryMileage(120_000)
                .telemetryLocation(Coordinates.builder()
                        .latitude(-34.6037).longitude(-58.3816).build());
    }

    private UUID openTrip() {
        return tripRepository.saveAndFlush(Trip.builder()
                .tripCarId(carId).tripDriverId(ownerId).build()).getTripId();
    }

    @Test
    void persistsAndReadsBackEveryMappedColumn() {
        UUID tripId = openTrip();
        Telemetry saved = telemetryRepository.saveAndFlush(aReading(noon)
                .telemetryTripId(tripId)
                .telemetryRawFrame("{\"pids\": {\"04\": \"5020\"}}")
                .build());
        entityManager.clear();

        Telemetry found = telemetryRepository.findById(saved.getTelemetryId()).orElseThrow();
        assertThat(found.getTelemetryCarId()).isEqualTo(carId);
        assertThat(found.getTelemetryTripId()).isEqualTo(tripId);
        assertThat(found.getTelemetryRecordedAt()).isEqualTo(noon);
        assertThat(found.getTelemetryReceivedAt()).isNotNull();
        assertThat(found.getTelemetrySpeed()).isEqualTo(60);
        assertThat(found.getTelemetryFuelLevel()).isEqualTo(70);
        assertThat(found.getTelemetryBatteryLevel()).isEqualTo(85);
        assertThat(found.getTelemetryMileage()).isEqualTo(120_000);
        assertThat(found.getTelemetryLocation().getLatitude()).isEqualTo(-34.6037);
        assertThat(found.getTelemetryLocation().getLongitude()).isEqualTo(-58.3816);
        // Stored as jsonb, so Postgres normalises the text; the content is what
        // matters, since this is what a better decoder will be backfilled from.
        assertThat(found.getTelemetryRawFrame()).contains("5020");
    }

    @Test
    void keepsTheTwoClocksApart() {
        Instant anHourAgo = noon.minus(1, ChronoUnit.HOURS);
        Telemetry saved = telemetryRepository.saveAndFlush(aReading(anHourAgo)
                .telemetryReceivedAt(noon)
                .build());
        entityManager.clear();

        // The phone was in a tunnel: the reading belongs in history an hour ago,
        // and only received_at explains why it turned up late.
        Telemetry found = telemetryRepository.findById(saved.getTelemetryId()).orElseThrow();
        assertThat(found.getTelemetryRecordedAt()).isEqualTo(anHourAgo);
        assertThat(found.getTelemetryReceivedAt()).isEqualTo(noon);
        assertThat(found.getTelemetryRecordedAt()).isBefore(found.getTelemetryReceivedAt());
    }

    @Test
    void storesAReadingItCouldNotDecode() {
        // Everything but the frame is null: the relay forwarded something the
        // decoder does not understand yet. Keeping it is the whole point of
        // raw_frame - the columns can be backfilled once it does.
        Telemetry saved = telemetryRepository.saveAndFlush(Telemetry.builder()
                .telemetryCarId(carId)
                .telemetryRecordedAt(noon)
                .telemetryRawFrame("{\"raw\": \"01045020\"}")
                .build());
        entityManager.clear();

        Telemetry found = telemetryRepository.findById(saved.getTelemetryId()).orElseThrow();
        assertThat(found.getTelemetrySpeed()).isNull();
        assertThat(found.getTelemetryLocation()).isNull();
        assertThat(found.getTelemetryTripId()).isNull();
        assertThat(found.getTelemetryRawFrame()).contains("01045020");
    }

    @Test
    void insertIgnoringDuplicatesStoresAReadingExactlyOnce() {
        int first = telemetryRepository.insertIgnoringDuplicates(
                carId, null, noon, -34.6, -58.3, 60, 70, 85, 120_000, null);
        int again = telemetryRepository.insertIgnoringDuplicates(
                carId, null, noon, -34.6, -58.3, 60, 70, 85, 120_000, null);

        // 1 then 0, and crucially no exception: this is how a batch survives
        // its own duplicates without aborting the transaction.
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(telemetryRepository.count()).isEqualTo(1);
        // The database clock filled in received_at.
        assertThat(telemetryRepository.findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId))
                .get().extracting(Telemetry::getTelemetryReceivedAt).isNotNull();
    }

    @Test
    void insertIgnoringDuplicatesStoresAnyJsonAsTheRawFrame() {
        // An object, and a bare string - both are valid jsonb, and the firmware
        // currently sends the latter.
        telemetryRepository.insertIgnoringDuplicates(
                carId, null, noon, null, null, null, null, null, null,
                "{\"pids\": {\"04\": \"5020\"}}");
        telemetryRepository.insertIgnoringDuplicates(
                carId, null, noon.plusSeconds(5), null, null, null, null, null, null,
                "\"01045020\"");

        var history = telemetryRepository
                .findByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId, PageRequest.of(0, 10));
        assertThat(history).extracting(Telemetry::getTelemetryRawFrame)
                .satisfiesExactly(
                        s -> assertThat(s).contains("01045020"),
                        s -> assertThat(s).contains("5020"));
    }

    @Test
    void refusesTheSameReadingTwiceForOneCar() {
        telemetryRepository.saveAndFlush(aReading(noon).build());

        // ux_telemetry_car_recorded_at. The relay retries whatever it is unsure
        // about, so this is what keeps ingestion idempotent instead of quietly
        // doubling a car's history.
        assertThatThrownBy(() -> telemetryRepository.saveAndFlush(aReading(noon).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTwoCarsToReportAtTheSameInstant() {
        UUID otherCar = newCar("Second car");
        telemetryRepository.saveAndFlush(aReading(noon).build());

        assertThat(telemetryRepository.saveAndFlush(aReading(noon)
                .telemetryCarId(otherCar).build()).getTelemetryId()).isNotNull();
    }

    @Test
    void findsTheLatestReadingByWhenItWasTakenNotWhenItArrived() {
        telemetryRepository.saveAndFlush(aReading(noon).telemetrySpeed(90).build());
        // Arrives afterwards but was taken an hour earlier - a buffer flushed
        // late. It must not become "the latest", or the cached snapshot on the
        // car would silently go backwards.
        telemetryRepository.saveAndFlush(aReading(noon.minus(1, ChronoUnit.HOURS))
                .telemetrySpeed(10).build());

        assertThat(telemetryRepository.findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId))
                .get()
                .satisfies(t -> {
                    assertThat(t.getTelemetryRecordedAt()).isEqualTo(noon);
                    assertThat(t.getTelemetrySpeed()).isEqualTo(90);
                });
    }

    @Test
    void hasNoLatestReadingForACarThatHasNeverReported() {
        assertThat(telemetryRepository
                .findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId)).isEmpty();
    }

    @Test
    void pagesACarsHistoryNewestFirst() {
        for (int minute = 0; minute < 5; minute++) {
            telemetryRepository.saveAndFlush(
                    aReading(noon.plus(minute, ChronoUnit.MINUTES)).build());
        }
        telemetryRepository.saveAndFlush(aReading(noon).telemetryCarId(newCar("Other")).build());

        var firstPage = telemetryRepository
                .findByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId, PageRequest.of(0, 2));

        assertThat(firstPage).extracting(Telemetry::getTelemetryRecordedAt)
                .containsExactly(noon.plus(4, ChronoUnit.MINUTES),
                                 noon.plus(3, ChronoUnit.MINUTES));
        assertThat(telemetryRepository
                .findByTelemetryCarIdOrderByTelemetryRecordedAtDesc(carId, PageRequest.of(0, 50)))
                .hasSize(5);
    }

    @Test
    void findsEveryReadingTakenDuringOneTrip() {
        UUID tripId = openTrip();
        telemetryRepository.saveAndFlush(aReading(noon.plusSeconds(30)).telemetryTripId(tripId).build());
        telemetryRepository.saveAndFlush(aReading(noon).telemetryTripId(tripId).build());
        // Reported while parked, outside any trip.
        telemetryRepository.saveAndFlush(aReading(noon.minusSeconds(600)).build());

        // Oldest first: this is the route, and it is only a route in order.
        assertThat(telemetryRepository.findByTelemetryTripIdOrderByTelemetryRecordedAtAsc(tripId))
                .extracting(Telemetry::getTelemetryRecordedAt)
                .containsExactly(noon, noon.plusSeconds(30));
    }

    @Test
    void keepsTheReadingWhenItsTripIsDeleted() {
        UUID tripId = openTrip();
        Telemetry saved = telemetryRepository.saveAndFlush(
                aReading(noon).telemetryTripId(tripId).build());

        tripRepository.deleteById(tripId);
        entityManager.flush();
        entityManager.clear();

        // on delete set null in V5: losing the trip must not erase where the
        // car actually was.
        Telemetry found = telemetryRepository.findById(saved.getTelemetryId()).orElseThrow();
        assertThat(found.getTelemetryTripId()).isNull();
        assertThat(found.getTelemetryLocation().getLatitude()).isEqualTo(-34.6037);
    }

    @Test
    void deletingACarRemovesItsTelemetry() {
        Telemetry saved = telemetryRepository.saveAndFlush(aReading(noon).build());

        carRepository.deleteById(carId);
        entityManager.flush();
        entityManager.clear();

        assertThat(telemetryRepository.findById(saved.getTelemetryId())).isEmpty();
    }

    @Test
    void requiresACarThatExists() {
        assertThatThrownBy(() -> telemetryRepository.saveAndFlush(
                aReading(noon).telemetryCarId(UUID.randomUUID()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeSpeed() {
        assertThatThrownBy(() -> telemetryRepository.saveAndFlush(
                aReading(noon).telemetrySpeed(-1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnOutOfRangeLongitude() {
        assertThatThrownBy(() -> telemetryRepository.saveAndFlush(aReading(noon)
                .telemetryLocation(Coordinates.builder().latitude(0.0).longitude(181.0).build())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordsWhenTheCarsCachedSnapshotWasTaken() {
        Car car = carRepository.findById(carId).orElseThrow();
        assertThat(car.getCarSnapshotAt()).isNull();

        car.setCarFuelLevel(70);
        car.setCarSnapshotAt(noon);
        carRepository.saveAndFlush(car);
        entityManager.clear();

        // Ingestion compares an incoming reading against this before refreshing
        // the snapshot, so a late upload cannot overwrite fresher values.
        assertThat(carRepository.findById(carId).orElseThrow().getCarSnapshotAt()).isEqualTo(noon);
    }
}
