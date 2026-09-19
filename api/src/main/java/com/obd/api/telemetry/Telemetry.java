package com.obd.api.telemetry;

import com.obd.api.car.Coordinates;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One reading reported by a car.
 *
 * This is the authoritative history; the matching columns on {@code Car} are a
 * cache of the most recent row, so that reading one car stays a single-row
 * lookup. It is also the car's movement log: with a position and a timestamp on
 * every row, "where has this car been" is a query over this table and nothing
 * else needs to record it.
 */
@Entity
@Table(name = "telemetry")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Telemetry {

    /**
     * A generated bigint rather than a UUID, unlike every other table here: this
     * one grows without bound and a reading is addressed as "this car, at this
     * instant", never by id. See V5__telemetry.sql.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "telemetry_id")
    private Long telemetryId;

    @Column(name = "car_id", nullable = false, updatable = false)
    private UUID telemetryCarId;

    /**
     * The trip this reading was taken during, stamped at ingestion from the
     * car's open trip. Null when the car reported outside any trip.
     */
    @Column(name = "trip_id", updatable = false)
    private UUID telemetryTripId;

    /**
     * When the reading was taken. This is what every query means by "when" -
     * a phone that was offline uploads an hour-old reading with an hour-old
     * timestamp, and it belongs in history at that point, not at upload time.
     */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant telemetryRecordedAt;

    /** When it reached us. The gap to recorded_at is the relay's buffering. */
    @Column(name = "received_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant telemetryReceivedAt = Instant.now();

    @Embedded
    private Coordinates telemetryLocation;

    /**
     * Instantaneous speed. Singular, unlike the car's max/avg: those are
     * aggregates over many of these rows, not measurements in their own right.
     */
    @Column(name = "speed")
    private Integer telemetrySpeed;

    @Column(name = "fuel_level")
    private Integer telemetryFuelLevel;

    @Column(name = "battery_level")
    private Integer telemetryBatteryLevel;

    @Column(name = "mileage")
    private Integer telemetryMileage;

    /**
     * The payload exactly as the device sent it. The decoder will get better;
     * a column added later can be backfilled from these, whereas a value
     * discarded at ingestion is gone for good.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_frame")
    private String telemetryRawFrame;
}
