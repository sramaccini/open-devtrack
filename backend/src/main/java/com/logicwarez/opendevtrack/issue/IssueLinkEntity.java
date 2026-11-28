/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "odt_issue", name = "issue_link")
class IssueLinkEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID fromId;
    private UUID toId;

    /** The code of its type (ADR-29), of the tenant of its issues. */
    @Column(name = "link_type")
    private String type;

    private String createdBy;
    private Instant createdAt;

    protected IssueLinkEntity() {
    }

    IssueLinkEntity(UUID fromId, UUID toId, String type, String createdBy, Instant createdAt) {
        this.fromId = fromId;
        this.toId = toId;
        this.type = type;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    UUID fromId() {
        return fromId;
    }

    UUID toId() {
        return toId;
    }

    String type() {
        return type;
    }

    /** The link becomes one of another type, when its type is removed in favour of that one (ADR-29). */
    void retype(String type) {
        this.type = type;
    }

    /** The issue at the other end, seen from {@code issueId}. */
    UUID other(UUID issueId) {
        return fromId.equals(issueId) ? toId : fromId;
    }
}
