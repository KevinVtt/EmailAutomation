package com.emailfilter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {
    private UUID userId;
    private String email;
    private String name;
    private String accessToken;
    private String refreshToken;
    private String avatarUrl;
}
