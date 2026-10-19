package com.emailfilter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** La misma acción sobre varios mails, de a uno: un id que falla no cancela el resto. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkActionRequest {
    @NotBlank
    private String provider;

    @NotEmpty
    @Size(max = 100)
    private List<String> emailIds;

    @NotBlank
    @Pattern(regexp = "star|unstar|read|unread|trash", message = "unknown action")
    private String action;
}
