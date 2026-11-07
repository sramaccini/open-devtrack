/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A message for one user (ADR-13): its kind and values, written as text when it is read. */
@Entity
@Table(schema = "odt_notification", name = "message")
class MessageEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID projectId;
    private String recipient;
    private String kind;
    private String issueKey;
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> params;

    private Instant createdAt;
    private Instant readAt;
    private boolean shown = true;
    private String emailMode;
    private Instant emailDueAt;
    private Instant emailedAt;
    private int emailAttempts;

    protected MessageEntity() {
    }

    MessageEntity(UUID projectId, String recipient, MessageKind kind, String issueKey, String actor,
                  Map<String, String> params, Instant createdAt) {
        this.projectId = projectId;
        this.recipient = recipient;
        this.kind = kind.value();
        this.issueKey = issueKey;
        this.actor = actor;
        this.params = Map.copyOf(params);
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    UUID projectId() {
        return projectId;
    }

    String recipient() {
        return recipient;
    }

    MessageKind kind() {
        return MessageKind.parse(kind);
    }

    String issueKey() {
        return issueKey;
    }

    String actor() {
        return actor;
    }

    Map<String, String> params() {
        return params == null ? Map.of() : params;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant readAt() {
        return readAt;
    }

    /** Not in the bell: the user turned the application off for the kind but wants the mail (ADR-18). */
    void hide() {
        shown = false;
    }

    /** In the outbox: {@code instant} alone, {@code daily} in the digest, from {@code due}. */
    void mail(String mode, Instant due) {
        emailMode = mode;
        emailDueAt = due;
    }

    String emailMode() {
        return emailMode;
    }

    int emailAttempts() {
        return emailAttempts;
    }

    void emailed(Instant at) {
        emailedAt = at;
    }

    /** Out of the outbox without a mail: read before its digest, or no longer for this user. */
    void unmail() {
        emailDueAt = null;
    }

    /** A failed send: again after a delay that grows with the attempts, or never after {@code max}. */
    void failed(Instant now, int max) {
        emailAttempts++;
        emailDueAt = emailAttempts >= max ? null : now.plusSeconds(60L << emailAttempts);
    }
}
