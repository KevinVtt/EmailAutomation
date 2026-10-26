package com.emailfilter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mail nuevo ya aprobado por el usuario: el backend no lo modifica. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComposeRequest {
    // Una sola dirección: la coma y los saltos de línea abren la puerta a Bcc inyectado.
    @NotBlank
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "invalid address")
    private String to;

    // Lista separada por comas; cada dirección se valida en EmailService antes de enviar.
    @Size(max = 2000)
    @Pattern(regexp = "^[^\\r\\n]*$", message = "cc cannot contain line breaks")
    private String cc;

    @Size(max = 2000)
    @Pattern(regexp = "^[^\\r\\n]*$", message = "bcc cannot contain line breaks")
    private String bcc;

    @NotBlank
    @Size(max = 998)
    @Pattern(regexp = "^[^\\r\\n]*$", message = "subject cannot contain line breaks")
    private String subject;

    @NotBlank
    @Size(max = 50000)
    private String body;
}
