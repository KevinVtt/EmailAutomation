package com.emailfilter.repository;

import com.emailfilter.model.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    Optional<ChatConversation> findFirstByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<ChatConversation> findByIdAndUserId(UUID id, UUID userId);
}
