package com.obd.api.car;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cars")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID carId;

    /**
     * Held as a raw id rather than a {@code @ManyToOne User}: nothing about a
     * car needs the owner's name or email, so a mapping would only add a join
     * and a lazy-loading trap. Nullable because deleting a user orphans their
     * cars rather than deleting them (see V2__cars_and_models.sql).
     */
    @Column(name = "owner_id")
    private UUID carOwnerId;

    /**
     * Mapped as an association, unlike the owner, because reading a car does
     * need the brand and model to display. LAZY plus {@code open-in-view=false}
     * means it must be touched inside the service transaction - mapping to a
     * DTO there is what makes that safe.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model carModel;

    @Column(nullable = false, name = "name")
    private String carName;

    @Column(name = "license_plate")
    private String carLicensePlate;

    // Snapshot of the last telemetry seen. All nullable - a car that has never
    // reported has no snapshot. The time series is its own resource; these are
    // a cache so reading one car is a single row.
    @Column(name = "mileage")
    private Integer carMileage;

    @Column(name = "fuel_level")
    private Integer carFuelLevel;

    @Column(name = "battery_level")
    private Integer carBatteryLevel;

    @Column(name = "max_speed")
    private Integer carMaxSpeed;

    @Column(name = "avg_speed")
    private Integer carAvgSpeed;

    @Embedded
    private Coordinates carLocation;

    /**
     * {@code recorded_at} of the telemetry row the snapshot above came from.
     * It is what makes the cache safe: a reading that arrives late - a phone
     * flushing an hour-old buffer - is older than this and must not overwrite
     * it. Null on a car that has never reported.
     */
    @Column(name = "snapshot_at")
    private Instant carSnapshotAt;

    // Assigned once on insert. @Builder ignores plain field initialisers, so
    // without @Builder.Default this would be null in a NOT NULL column.
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant carCreatedAt = Instant.now();
}
