/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Work logged on an issue (ADR-30): by whom, from when, how many minutes, and what was done. */
@Entity
@Table(schema = "odt_collaboration", name = "worklog")
class WorkLogEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID issueId;
    private String author;
    private Instant startedAt;
    private int minutes;
    private String comment;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected WorkLogEntity() {
    }

    WorkLogEntity(UUID issueId, String author, Instant startedAt, int minutes, String comment, Instant createdAt) {
        this.issueId = issueId;
        this.author = author;
        this.startedAt = startedAt;
        this.minutes = minutes;
        this.comment = comment;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void edit(Instant startedAt, int minutes, String comment, Instant now) {
        this.startedAt = startedAt;
        this.minutes = minutes;
        this.comment = comment;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    UUID issueId() {
        return issueId;
    }

    String author() {
        return author;
    }

    Instant startedAt() {
        return startedAt;
    }

    int minutes() {
        return minutes;
    }

    String comment() {
        return comment;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    long version() {
        return version;
    }
}
