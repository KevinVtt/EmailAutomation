package com.emailfilter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ChatRequest {
    @NotBlank
    private String message;

    private String conversationId;

    /** {folder, criteria, description}: la lista que el usuario está viendo. */
    private Map<String, Object> scope;
}
