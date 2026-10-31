/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "odt_audit", name = "issue_change")
class IssueChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID issueId;
    private String author;
    private Instant changedAt;
    private String field;

    private String oldValue;
    private String newValue;

    protected IssueChangeEntity() {
    }

    IssueChangeEntity(UUID issueId, String author, Instant changedAt, String field, String from, String to) {
        this.issueId = issueId;
        this.author = author;
        this.changedAt = changedAt;
        this.field = field;
        this.oldValue = from;
        this.newValue = to;
    }

    String author() {
        return author;
    }

    Instant changedAt() {
        return changedAt;
    }

    String field() {
        return field;
    }

    String from() {
        return oldValue;
    }

    String to() {
        return newValue;
    }
}
