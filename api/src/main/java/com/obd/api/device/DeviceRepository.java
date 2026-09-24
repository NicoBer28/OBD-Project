package com.obd.api.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /** Serial must already be normalised (trimmed, upper-cased). */
    Optional<Device> findByDeviceSerial(String deviceSerial);

    Optional<Device> findByDeviceCarId(UUID deviceCarId);

    /**
     * Records that the device just delivered readings. Server clock, one
     * UPDATE, no read-modify-write: ingestion calls this once per batch and
     * two concurrent batches simply both write "now".
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Device d set d.deviceLastSeenAt = :now where d.deviceSerial = :serial")
    int touch(@Param("serial") String serial, @Param("now") Instant now);
}
