package com.obd.api.devicetoken;

import com.obd.api.auth.token.SecretTokens;
import com.obd.api.car.CarAccess;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.devicetoken.dto.DeviceTokenDTO;
import com.obd.api.devicetoken.exception.DeviceTokenNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    public static final String PREFIX = "obdd_";

    private final DeviceTokenRepository deviceTokenRepository;
    private final CarAccess carAccess;


    @Transactional
    public DeviceTokenDTO.Minted mint(UUID userId, UUID carId, DeviceTokenDTO.Create request) {
        carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        String raw = PREFIX + SecretTokens.mint();
        DeviceToken saved = deviceTokenRepository.saveAndFlush(DeviceToken.builder()
                .deviceTokenUserId(userId)
                .deviceTokenCarId(carId)
                .deviceTokenHash(SecretTokens.hash(raw))
                .deviceTokenLabel(request.label().trim())
                .deviceTokenCreatedAt(Instant.now())
                .build());

        return DeviceTokenDTO.Minted.of(saved, raw);
    }

    @Transactional
    public List<DeviceTokenDTO.Read> forCar(UUID userId, UUID carId) {
        carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        return deviceTokenRepository
                .findByDeviceTokenUserIdAndDeviceTokenCarIdAndDeviceTokenRevokedAtIsNullOrderByDeviceTokenCreatedAtDesc(userId, carId)
                .stream()
                .map(DeviceTokenDTO.Read::from)
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        if (deviceTokenRepository.revoke(tokenId, userId, Instant.now()) == 0) {
            // Unknown, already revoked, or somebody else's - one answer for
            // all three, so a token id is never confirmed to a stranger.
            throw new DeviceTokenNotFoundException(tokenId);
        }
    }
}
