package com.emailfilter.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class RewriteRequest {
    @NotBlank
    private String draft;
    private String originalSubject = "";
    private String originalFrom = "";
    private String originalBody = "";
    private String tone = "formal";
    private String language = "auto";
    private String customRules = "";
}
