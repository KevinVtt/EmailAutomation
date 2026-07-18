package com.emailfilter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class EmailDTO {
    private UUID id;
    private String provider;
    private String providerEmailId;
    private String threadId;
    private String fromAddress;
    private String fromName;
    private String toAddresses;
    private String subject;
    private String bodyPreview;
    private String bodyHtml;
    private boolean isRead;
    private boolean isStarred;
    private boolean visto;
    private String labels;
    private Instant receivedAt;
    private Instant fetchedAt;
}
