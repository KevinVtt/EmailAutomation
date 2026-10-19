package com.emailfilter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Lo que el usuario le pide al asistente para redactar el mail a un contacto. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactDraftRequest {
    @NotBlank
    private String instruction;
    private String tone;
    private String language;
}
