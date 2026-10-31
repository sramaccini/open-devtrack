/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "odt_issue", name = "issue")
class IssueEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private UUID projectId;
    private long number;

    @Enumerated(EnumType.STRING)
    private IssueType type;

    private String summary;
    private String description;
    private String status;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    private String assignee;
    private String reporter;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> labels;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected IssueEntity() {
    }

    IssueEntity(String tenantId, UUID projectId, long number, String status, String reporter, Instant createdAt) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.number = number;
        this.reporter = reporter;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void edit(IssueType type, String summary, String description, Priority priority, String assignee, List<String> labels,
              Instant now) {
        this.type = type;
        this.summary = summary;
        this.description = description;
        this.priority = priority;
        this.assignee = assignee;
        this.labels = labels;
        this.updatedAt = now;
    }

    /** The status after a transition of the workflow (module workflow). */
    void moveTo(String status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    UUID projectId() {
        return projectId;
    }

    long number() {
        return number;
    }

    IssueType type() {
        return type;
    }

    String summary() {
        return summary;
    }

    String description() {
        return description;
    }

    String status() {
        return status;
    }

    Priority priority() {
        return priority;
    }

    String assignee() {
        return assignee;
    }

    String reporter() {
        return reporter;
    }

    List<String> labels() {
        return labels;
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
