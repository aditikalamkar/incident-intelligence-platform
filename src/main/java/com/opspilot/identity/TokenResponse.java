package com.opspilot.identity;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
