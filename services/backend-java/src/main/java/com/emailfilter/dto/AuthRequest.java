package com.emailfilter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuthRequest {
    @NotBlank
    private String authorizationCode;

    private String provider;
}
