/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A group of users of a tenant (ADR-22): a member of projects with a role, like a user. */
@Entity
@Table(schema = "odt_access", name = "user_group")
class GroupEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private String name;
    private String description;
    private String createdBy;
    private Instant createdAt;

    @Version
    private long version;

    protected GroupEntity() {
    }

    GroupEntity(String tenantId, String name, String description, String createdBy, Instant now) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void edit(String name, String description) {
        this.name = name;
        this.description = description;
    }

    UUID id() {
        return id;
    }

    String tenantId() {
        return tenantId;
    }

    String name() {
        return name;
    }

    String description() {
        return description;
    }

    long version() {
        return version;
    }
}
