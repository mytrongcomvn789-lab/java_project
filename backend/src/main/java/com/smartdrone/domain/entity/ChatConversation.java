package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;

/** Account-scoped, bounded history; no credentials or full order snapshots. */
@Entity
@Table(name = "ai_chat_conversations", indexes = {
    @Index(name = "ix_chat_owner", columnList = "owner_id"),
    @Index(name = "ix_chat_expiry", columnList = "expires_at")})
public class ChatConversation {
    @Id private UUID id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, updatable = false) private UUID ownerId;
    @Column(name = "order_id", updatable = false) private UUID orderId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    private UUID lease;
    private Instant busyUntil;
    private int turns;
    @Lob @Nationalized @Column(nullable = false) private String history = "[]";

    protected ChatConversation() { }
    public ChatConversation(UUID ownerId, UUID orderId, Instant now) {
        this.id = UUID.randomUUID(); this.ownerId = ownerId; this.orderId = orderId;
        this.expiresAt = now.plusSeconds(86400);
    }
    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getOrderId() { return orderId; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getLease() { return lease; }
    public Instant getBusyUntil() { return busyUntil; }
    public int getTurns() { return turns; }
    public String getHistory() { return history; }
    public void reserve(UUID lease, Instant until) { this.lease = lease; this.busyUntil = until; }
    public void finish(String history) { this.history = history; turns++; release(); }
    public void release() { lease = null; busyUntil = null; }
}
