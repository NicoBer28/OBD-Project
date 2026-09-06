package com.obd.api.auth.dto;


public record TokenPair(AuthResponseDTO auth, String refreshToken) {}
