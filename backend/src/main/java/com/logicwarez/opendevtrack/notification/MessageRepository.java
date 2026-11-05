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

    List<MessageEntity> findByRecipientOrderByCreatedAtDescIdDesc(String recipient, Limit limit);

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
