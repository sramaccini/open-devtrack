/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "odt_issue", name = "watcher")
class WatcherEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID issueId;
    private String username;
    private Instant createdAt;

    protected WatcherEntity() {
    }

    WatcherEntity(UUID issueId, String username, Instant createdAt) {
        this.issueId = issueId;
        this.username = username;
        this.createdAt = createdAt;
    }

    String username() {
        return username;
    }

    Instant createdAt() {
        return createdAt;
    }
}
