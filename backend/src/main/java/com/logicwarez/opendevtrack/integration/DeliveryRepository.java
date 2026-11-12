/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface DeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {

    /** The deliveries due at {@code now}, the oldest first. */
    @Query(value = "SELECT id FROM odt_integration.delivery WHERE status = 'pending' AND next_at <= :now"
            + " ORDER BY next_at, id LIMIT :limit", nativeQuery = true)
    List<UUID> due(Instant now, int limit);

    /** The delivery if still due and held by no other instance, locked until the commit. */
    @Query(value = "SELECT * FROM odt_integration.delivery WHERE id = :id AND status = 'pending' AND next_at <= :now"
            + " FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<DeliveryEntity> claim(UUID id, Instant now);

    List<DeliveryEntity> findByWebhookIdOrderByCreatedAtDescIdDesc(UUID webhookId, Limit limit);

    Optional<DeliveryEntity> findFirstByWebhookIdAndLastAtNotNullOrderByLastAtDesc(UUID webhookId);

    Optional<DeliveryEntity> findByIdAndWebhookId(UUID id, UUID webhookId);

    @Modifying
    @Query("DELETE FROM DeliveryEntity d WHERE d.createdAt < :before")
    int deleteCreatedBefore(Instant before);
}
