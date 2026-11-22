package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.PickupChallenge;

public interface PickupChallengeRepository extends JpaRepository<PickupChallenge, UUID> {

    Optional<PickupChallenge> findFirstByOrderIdOrderBySequenceNoDesc(UUID orderId);

    /** Thu hồi đúng một challenge (không quét cả đơn, tránh thu hồi nhầm challenge vừa được cấp song song). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update PickupChallenge c set c.status = com.smartdrone.domain.enums.ChallengeStatus.REVOKED
            where c.id = :id and c.status = com.smartdrone.domain.enums.ChallengeStatus.ACTIVE""")
    int revoke(@Param("id") UUID id);

    /** Đánh dấu đã dùng NGUYÊN TỬ: hai request đồng thời thì chỉ một request thấy 1 dòng được cập nhật. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update PickupChallenge c
               set c.status = com.smartdrone.domain.enums.ChallengeStatus.USED, c.usedAt = :now, c.usedBy = :actor
             where c.id = :id
               and c.status = com.smartdrone.domain.enums.ChallengeStatus.ACTIVE
               and c.expiresAt > :now
               and c.failedAttempts < :max""")
    int consume(@Param("id") UUID id, @Param("actor") UUID actor,
                @Param("now") Instant now, @Param("max") int max);

    @Modifying
    @Query("""
            update PickupChallenge c set c.failedAttempts = c.failedAttempts + 1
            where c.id = :id and c.status = com.smartdrone.domain.enums.ChallengeStatus.ACTIVE""")
    int incrementFailures(@Param("id") UUID id);

    @Modifying
    @Query("""
            update PickupChallenge c set c.status = com.smartdrone.domain.enums.ChallengeStatus.LOCKED
            where c.id = :id and c.status = com.smartdrone.domain.enums.ChallengeStatus.ACTIVE
              and c.failedAttempts >= :max""")
    int lockIfExceeded(@Param("id") UUID id, @Param("max") int max);
}