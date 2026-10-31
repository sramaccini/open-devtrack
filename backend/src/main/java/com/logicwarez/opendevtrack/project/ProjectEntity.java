/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A project of a tenant, with its key (ODT) and the counter that numbers its issues. */
@Entity
@Table(schema = "odt_project", name = "project")
class ProjectEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;

    @Column(name = "project_key")
    private String key;

    private String name;
    private String description;
    private String lead;
    private long issueSeq;
    private String createdBy;
    private Instant createdAt;

    @Version
    @Column(name = "version")
    private long version;

    protected ProjectEntity() {
    }

    ProjectEntity(String tenantId, String key, String name, String description, String lead, Instant createdAt) {
        this.tenantId = tenantId;
        this.key = key;
        this.name = name;
        this.description = description;
        this.lead = lead;
        this.createdBy = lead;
        this.createdAt = createdAt;
    }

    void edit(String name, String description, String lead) {
        this.name = name;
        this.description = description;
        this.lead = lead;
    }

    UUID id() {
        return id;
    }

    String key() {
        return key;
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

    String tenantId() {
        return tenantId;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    ProjectRef toRef() {
        return new ProjectRef(id, tenantId, key, name);
    }
}
