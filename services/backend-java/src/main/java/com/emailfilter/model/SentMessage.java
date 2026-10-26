package com.emailfilter.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sent_messages", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "provider_email_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentMessage {
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

    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    private String subject;

    @Column(name = "body_preview", columnDefinition = "TEXT")
    private String bodyPreview;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @PrePersist
    protected void onCreate() {
        if (fetchedAt == null) fetchedAt = Instant.now();
    }
}
