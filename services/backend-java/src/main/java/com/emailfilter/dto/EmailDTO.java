package com.emailfilter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    // Lombok generates isStarred(); without the annotation Jackson emits "starred".
    @JsonProperty("isStarred")
    private boolean isStarred;
    // Same trap: without this the client reads undefined and every mail looks unread.
    @JsonProperty("isRead")
    private boolean isRead;
    private boolean visto;
    private String labels;
    private Instant receivedAt;
    private Instant fetchedAt;
}
