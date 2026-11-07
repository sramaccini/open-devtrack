/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /** The messages of the bell: those the user wants in the application. */
    List<MessageEntity> findByRecipientAndShownTrueOrderByCreatedAtDescIdDesc(String recipient, Limit limit);

    /** The outbox: the messages due to be mailed, the oldest first. */
    @Query("SELECT m FROM MessageEntity m WHERE m.emailedAt IS NULL AND m.emailDueAt <= :now ORDER BY m.createdAt, m.id")
    List<MessageEntity> due(Instant now, Limit limit);

    /** Of {@code ids}, those still to be mailed that no other sender holds, locked until the commit. */
    @Query(value = "SELECT id FROM odt_notification.message WHERE id IN (:ids) AND emailed_at IS NULL"
            + " AND email_due_at IS NOT NULL FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> claim(Collection<UUID> ids);

    /** Marks read the messages of the recipient among {@code ids}, once: those already read keep their time. */
    @Modifying
    @Query("UPDATE MessageEntity m SET m.readAt = :at WHERE m.recipient = :recipient AND m.id IN :ids AND m.readAt IS NULL")
    int markRead(String recipient, Collection<UUID> ids, Instant at);

    @Modifying
    @Query("DELETE FROM MessageEntity m WHERE m.projectId = :projectId AND m.issueKey = :issueKey")
    int deleteByIssue(UUID projectId, String issueKey);

    @Modifying
    @Query("DELETE FROM MessageEntity m WHERE m.createdAt < :before")
    int deleteOlderThan(Instant before);
}
