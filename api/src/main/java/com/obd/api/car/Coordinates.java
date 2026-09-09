package com.obd.api.car;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * A WGS-84 position, stored as two plain columns on the owning table.
 *
 * Embeddable rather than its own table because a position has no identity of
 * its own - it is only ever meaningful as "where this car was". Hibernate reads
 * the whole embeddable back as null when both columns are null, which is the
 * case for a car that has never reported telemetry.
 */
@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
@EqualsAndHashCode
public class Coordinates {

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;
}
