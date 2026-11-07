/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A saved ODQL query (ADR-16): private to its owner, or shared with the tenant; on the dashboard or not (ADR-17). */
@Entity
@Table(schema = "odt_search", name = "filter")
class FilterEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private String owner;
    private String name;
    private String query;
    private boolean shared;
    private boolean onDashboard;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected FilterEntity() {
    }

    FilterEntity(String tenantId, String owner, Instant createdAt) {
        this.tenantId = tenantId;
        this.owner = owner;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void edit(String name, String query, boolean shared, boolean onDashboard, Instant now) {
        this.name = name;
        this.query = query;
        this.shared = shared;
        this.onDashboard = onDashboard;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    String tenantId() {
        return tenantId;
    }

    String owner() {
        return owner;
    }

    String name() {
        return name;
    }

    String query() {
        return query;
    }

    boolean shared() {
        return shared;
    }

    boolean onDashboard() {
        return onDashboard;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    long version() {
        return version;
    }
}
