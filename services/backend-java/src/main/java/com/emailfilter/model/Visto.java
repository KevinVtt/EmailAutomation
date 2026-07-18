package com.emailfilter.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "visto", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "provider_email_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visto {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider_email_id", nullable = false)
    private String providerEmailId;

    @Column(nullable = false)
    private Boolean visto = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
