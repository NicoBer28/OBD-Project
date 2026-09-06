package com.obd.api.auth.dto;

import java.util.UUID;

public record AuthResponseDTO(
        String accessToken,
        String tokenType,
        long expireInSeconds,
        UUID userId,
        String email
){}
