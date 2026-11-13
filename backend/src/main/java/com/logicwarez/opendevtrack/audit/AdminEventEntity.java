/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A change to the configuration of a tenant or of a project (ADR-22). */
@Entity
@Table(schema = "odt_audit", name = "admin_event")
class AdminEventEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private UUID projectId;
    private String action;
    private String target;
    private String actor;
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> after;

    protected AdminEventEntity() {
    }

    AdminEventEntity(String tenantId, UUID projectId, String action, String target, String actor, Instant occurredAt,
            Map<String, Object> before, Map<String, Object> after) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.action = action;
        this.target = target;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.before = before;
        this.after = after;
    }

    UUID id() {
        return id;
    }

    UUID projectId() {
        return projectId;
    }

    String action() {
        return action;
    }

    String target() {
        return target;
    }

    String actor() {
        return actor;
    }

    Instant occurredAt() {
        return occurredAt;
    }

    Map<String, Object> before() {
        return before;
    }

    Map<String, Object> after() {
        return after;
    }
}
