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

/** A team of a tenant (ADR-31): who does the work of the issues that name it, with a lead; it gives no permission. */
@Entity
@Table(schema = "odt_access", name = "team")
class TeamEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private String name;
    private String description;
    private String lead;
    private String createdBy;
    private Instant createdAt;

    @Version
    private long version;

    protected TeamEntity() {
    }

    TeamEntity(String tenantId, String name, String description, String lead, String createdBy, Instant now) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.lead = lead;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void edit(String name, String description, String lead) {
        this.name = name;
        this.description = description;
        this.lead = lead;
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

    String lead() {
        return lead;
    }

    long version() {
        return version;
    }
}
