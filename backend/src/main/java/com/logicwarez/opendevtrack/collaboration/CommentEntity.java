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

@Entity
@Table(schema = "odt_collaboration", name = "comment")
class CommentEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID issueId;
    private String author;
    private String body;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected CommentEntity() {
    }

    CommentEntity(UUID issueId, String author, String body, Instant createdAt) {
        this.issueId = issueId;
        this.author = author;
        this.body = body;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void edit(String body, Instant now) {
        this.body = body;
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

    String body() {
        return body;
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
