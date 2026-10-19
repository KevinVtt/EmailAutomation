package com.emailfilter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailContactDTO {
    private UUID id;
    private String address;
    private String displayName;
    private String company;
    private String topic;
    private String notes;
    private int sentCount;
    private int receivedCount;
    private Instant lastActivityAt;
}
