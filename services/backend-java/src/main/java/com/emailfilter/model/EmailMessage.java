package com.emailfilter.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_messages", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "provider_email_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_email_id", nullable = false)
    private String providerEmailId;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    private String subject;

    @Column(name = "body_preview", columnDefinition = "TEXT")
    private String bodyPreview;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "is_read")
    private boolean isRead;

    @Column(name = "is_starred")
    private boolean isStarred;

    @Column(columnDefinition = "TEXT")
    private String labels;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @PrePersist
    protected void onCreate() {
        if (fetchedAt == null) fetchedAt = Instant.now();
    }
}
