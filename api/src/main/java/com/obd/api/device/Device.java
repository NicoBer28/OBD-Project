package com.obd.api.device;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The OBD dongle paired to a car. Its job is identity: a phone that connects
 * to a dongle reads its serial and asks the API which car that is, instead of
 * asking the user - and gets the same answer on every phone in the family.
 */
@Entity
@Table(name = "devices")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "device_id")
    private UUID deviceId;

    // Raw id rather than an association, as Trip and Invitation do.
    @Column(name = "car_id", nullable = false)
    private UUID deviceCarId;

    /**
     * The identity the firmware exposes over GATT. Must arrive trimmed and
     * upper-cased - see {@link DeviceService#normaliseSerial}; the CHECK
     * constraint rejects anything else.
     */
    @Column(name = "serial", nullable = false)
    private String deviceSerial;

    @Column(name = "paired_at", nullable = false)
    @Builder.Default
    private Instant devicePairedAt = Instant.now();

    /** Null until the dongle has delivered readings once. */
    @Column(name = "last_seen_at")
    private Instant deviceLastSeenAt;
}
