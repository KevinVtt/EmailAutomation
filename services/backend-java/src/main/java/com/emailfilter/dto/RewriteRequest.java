package com.emailfilter.dto;

import lombok.Data;

@Data
public class RewriteRequest {
    private String draft;
    private String originalSubject = "";
    private String originalFrom = "";
    private String originalBody = "";
    private String tone = "formal";
    private String language = "auto";
    private String customRules = "";
}
