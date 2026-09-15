package com.obd.api.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.obd.api.telemetry.Telemetry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public class TelemetryDTO {

    /**
     * One batch of readings for one car. The phone relays whatever the dongle
     * sent since the last upload, so a batch is the natural unit - and one car
     * per batch keeps the access check, the trip lookup and the snapshot update
     * to exactly one each.
     */
    public record Ingest(
            // Exactly one of carId / serial. The phone that relays knows what
            // it is physically connected to - the dongle - so it may name the
            // car by serial and let the server resolve it; carId remains for
            // clients that already hold it.
            UUID carId,
            @Size(max = 64) String serial,
            // Bounded so a client cannot post an unbounded array; 500 readings
            // at one every 5 seconds is about 40 minutes of buffering.
            @NotEmpty @Size(max = 500) List<@Valid Reading> readings
    ) {
        @AssertTrue(message = "exactly one of carId or serial is required")
        public boolean isExactlyOneTarget() {
            return (carId == null) != (serial == null || serial.isBlank());
        }
    }

    public record Reading(
            // Device time, when the reading was taken - not when it is being
            // uploaded. This is what places it in history.
            @NotNull Instant recordedAt,
            @DecimalMin("-90")  @DecimalMax("90")  Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @PositiveOrZero Integer speed,
            @PositiveOrZero Integer fuelLevel,
            @PositiveOrZero Integer batteryLevel,
            @PositiveOrZero Integer mileage,
            // Whatever the device sent, verbatim - an object, an array, or a
            // bare string like "01045020" all serialise to valid jsonb.
            JsonNode raw
    ) {
        /** Half a position is no position. Either both coordinates or neither. */
        @AssertTrue(message = "latitude and longitude must be given together")
        public boolean isPositionComplete() {
            return (latitude == null) == (longitude == null);
        }

        /**
         * A device with a broken clock could stamp a reading in 2099; that row
         * would then be "the latest" for ever and block every snapshot update
         * after it. Five minutes covers real clock skew.
         */
        @AssertTrue(message = "recordedAt is in the future")
        public boolean isNotFromTheFuture() {
            return recordedAt == null
                    || recordedAt.isBefore(Instant.now().plus(5, ChronoUnit.MINUTES));
        }
    }

    /**
     * Query parameters of the history read, bound as a {@code @ModelAttribute}
     * record so that validation failures and a malformed {@code since} both
     * surface through the same 400 path as body validation.
     */
    public record Query(
            @NotNull UUID carId,
            // Exclusive cursor: readings recorded strictly after this instant.
            // The client passes back the nextSince (or latestRecordedAt) it was
            // last given, which it already holds, so re-fetching that row would
            // only ever produce a duplicate. Absent means "from the beginning".
            Instant since,
            @Min(1) @Max(500) Integer limit
    ) {
        public static final int DEFAULT_LIMIT = 100;

        public Query {
            if (since == null) since = Instant.EPOCH;
            if (limit == null) limit = DEFAULT_LIMIT;
        }
    }

    /** One stored reading. */
    public record Read(
            // Varies per reading, unlike carId, which the page carries once.
            UUID tripId,
            Instant recordedAt,
            Instant receivedAt,
            Double latitude,
            Double longitude,
            Integer speed,
            Integer fuelLevel,
            Integer batteryLevel,
            Integer mileage,
            // Stored as jsonb, read back as its text; emitted as-is so the
            // client sees the frame it sent, not a string containing JSON.
            @JsonRawValue String raw
    ) {
        public static Read from(Telemetry t) {
            var location = t.getTelemetryLocation();
            return new Read(
                    t.getTelemetryTripId(),
                    t.getTelemetryRecordedAt(),
                    t.getTelemetryReceivedAt(),
                    location == null ? null : location.getLatitude(),
                    location == null ? null : location.getLongitude(),
                    t.getTelemetrySpeed(),
                    t.getTelemetryFuelLevel(),
                    t.getTelemetryBatteryLevel(),
                    t.getTelemetryMileage(),
                    t.getTelemetryRawFrame());
        }
    }

    /**
     * A page of history, oldest first, shaped for incremental sync: the client
     * stores {@code nextSince} and keeps calling while {@code hasMore} is true.
     * Ascending order is what makes that loop correct - newest-first with a
     * limit would silently skip whatever fell between the two ends.
     */
    public record Page(
            UUID carId,
            List<Read> readings,
            // The cursor for the next call. When the page is empty this is the
            // cursor that was passed in, so the client can always store it.
            Instant nextSince,
            boolean hasMore
    ) {}

    /**
     * What the phone needs to trim its buffer and decide what to redraw. The
     * readings themselves are not echoed back - the client already has them.
     */
    public record Ingested(
            UUID carId,
            int stored,
            // Already in the database, from an earlier upload. Success, not an
            // error: a retry that finds its readings present has done its job.
            int duplicates,
            // The car's open trip these readings were stamped with; null means
            // the car is reporting with no trip open.
            UUID tripId,
            // Whether the car's cached snapshot moved. False when the whole
            // batch was older than what the car already knew.
            boolean snapshotUpdated,
            // The newest recordedAt in the batch: the client's sync cursor, and
            // the value it will later send as ?since= on the history read.
            Instant latestRecordedAt
    ) {}
}
