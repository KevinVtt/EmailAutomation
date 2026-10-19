package com.emailfilter.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_contact", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "address"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailContact {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 320)
    private String address;

    @Column(name = "display_name")
    private String displayName;

    private String company;

    @Column(columnDefinition = "TEXT")
    private String topic;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sent_count", nullable = false)
    @Builder.Default
    private int sentCount = 0;

    @Column(name = "received_count", nullable = false)
    @Builder.Default
    private int receivedCount = 0;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;
}
