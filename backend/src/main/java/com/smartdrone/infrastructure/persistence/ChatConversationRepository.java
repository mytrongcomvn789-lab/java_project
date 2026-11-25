package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.smartdrone.domain.entity.ChatConversation;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversation c where c.id = :id and c.ownerId = :owner")
    Optional<ChatConversation> lockOwned(@Param("id") UUID id, @Param("owner") UUID owner);
    @Modifying
    @Query("delete from ChatConversation c where c.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
