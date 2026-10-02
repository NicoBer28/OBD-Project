package com.obd.api.auth.reset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "token_id")
    private UUID tokenId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID tokenUserId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant tokenCreatedAt = Instant.now();

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant tokenExpiresAt;

    /** Null while redeemable. Written only by the conditional update. */
    @Column(name = "consumed_at")
    private Instant tokenConsumedAt;

    @Transient
    public boolean isUsableAt(Instant now) {
        return tokenConsumedAt == null && now.isBefore(tokenExpiresAt);
    }
}
