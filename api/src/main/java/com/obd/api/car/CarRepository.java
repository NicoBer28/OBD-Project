package com.obd.api.car;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CarRepository extends JpaRepository<Car, UUID> {

    // Derived from the entity property (carOwnerId), not the column (owner_id).
    List<Car> findByCarOwnerId(UUID carOwnerId);

    /**
     * Refreshes the car's cached snapshot from a reading, but only if that
     * reading is newer than the one the snapshot came from. Returns 1 if the
     * snapshot moved, 0 if the reading was stale.
     *
     * The comparison is in the WHERE clause on purpose. Reading the car,
     * comparing in Java and writing back is a lost update: two batches that
     * arrive together both see the old snapshot_at, both decide they are newer,
     * and whichever commits last wins - which may be the older one. One
     * conditional UPDATE is atomic and needs no lock. Same compare-and-set as
     * RefreshTokenService uses to revoke a token family.
     *
     * coalesce keeps a value the new reading did not carry: a fuel-only frame
     * must not blank the last known position. The cost is that snapshot_at
     * means "as of, for the fields this reading had" - the position may be
     * older. The alternative makes the map pin blink on every partial frame.
     *
     * A JPQL update bypasses the persistence context, hence the flush/clear
     * flags: without them a Car loaded earlier in the same transaction shadows
     * the change with stale state.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Car c
               set c.carFuelLevel          = coalesce(:fuelLevel,    c.carFuelLevel),
                   c.carBatteryLevel       = coalesce(:batteryLevel, c.carBatteryLevel),
                   c.carMileage            = coalesce(:mileage,      c.carMileage),
                   c.carLocation.latitude  = coalesce(:latitude,     c.carLocation.latitude),
                   c.carLocation.longitude = coalesce(:longitude,    c.carLocation.longitude),
                   c.carSnapshotAt         = :recordedAt
             where c.carId = :carId
               and (c.carSnapshotAt is null or c.carSnapshotAt < :recordedAt)
            """)
    int refreshSnapshot(@Param("carId") UUID carId,
                        @Param("recordedAt") Instant recordedAt,
                        @Param("fuelLevel") Integer fuelLevel,
                        @Param("batteryLevel") Integer batteryLevel,
                        @Param("mileage") Integer mileage,
                        @Param("latitude") Double latitude,
                        @Param("longitude") Double longitude);
}
