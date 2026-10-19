package com.emailfilter.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Lo que el usuario puede editar de una ficha; los conteos son del sistema. */
@Data
public class ContactPatchDTO {
    @Size(max = 255)
    private String company;
    private String topic;
    private String notes;
}
