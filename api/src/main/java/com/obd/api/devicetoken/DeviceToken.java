package com.obd.api.devicetoken;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "device_tokens")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class DeviceToken {

    public static final long IDLE_DAYS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "device_token_id")
    private UUID deviceTokenId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID deviceTokenUserId;

    @Column(name = "car_id", nullable = false, updatable = false)
    private UUID deviceTokenCarId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String deviceTokenHash;

    @Column(name = "label", nullable = false, updatable = false, length = 60)
    private String deviceTokenLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant deviceTokenCreatedAt = Instant.now();

    /** Null until the first request. Updated at most once a minute. */
    @Column(name = "last_used_at")
    private Instant deviceTokenLastUsedAt;

    /** Null while live. Stamped rather than deleted, so history survives. */
    @Column(name = "revoked_at")
    private Instant deviceTokenRevokedAt;

    @Transient
    public boolean isLiveAt(Instant now) {
        return deviceTokenRevokedAt == null && now.isBefore(idleDeadline());
    }


    @Transient
    public Instant idleDeadline() {
        Instant anchor = deviceTokenLastUsedAt != null ? deviceTokenLastUsedAt : deviceTokenCreatedAt;
        return anchor.plus(IDLE_DAYS, ChronoUnit.DAYS);
    }
}
