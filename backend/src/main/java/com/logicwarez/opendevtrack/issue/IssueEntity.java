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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** The day the issue should be done, and who reviews it (ADR-19); both optional. */
    private LocalDate dueOn;
    /** The day the work on it should start, as Jira's Start date; optional, never after the due date. */
    private LocalDate startOn;
    private String reviewer;

    /** Time tracking in minutes (ADR-30): the estimates, optional, and the sum of the work logs. */
    private Integer originalEstimate;
    private Integer remainingEstimate;
    private int timeSpent;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> labels;

    /** Values of the custom fields of the project, by key (ADR-11). */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> custom = new HashMap<>();

    /** Impeded, as the flag of Jira. */
    private boolean flagged;

    /** The epic of a story, task or bug, or the issue of a subtask (ADR-28); in the same project. */
    private UUID parentId;

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

    void edit(IssueType type, String summary, String description, Priority priority, String assignee, LocalDate dueOn,
              LocalDate startOn, String reviewer, List<String> labels, Map<String, Object> custom,
              Integer originalEstimate, Integer remainingEstimate, Instant now) {
        this.type = type;
        this.summary = summary;
        this.description = description;
        this.priority = priority;
        this.assignee = assignee;
        this.dueOn = dueOn;
        this.startOn = startOn;
        this.reviewer = reviewer;
        this.labels = labels;
        this.custom = new HashMap<>(custom);
        this.originalEstimate = originalEstimate;
        this.remainingEstimate = remainingEstimate;
        this.updatedAt = now;
    }

    /** Work logged, changed or deleted: the time spent moves by {@code spent}, the remaining estimate becomes {@code remaining}. */
    void logged(int spent, Integer remaining, Instant now) {
        this.timeSpent = Math.max(0, timeSpent + spent);
        this.remainingEstimate = remaining;
        this.updatedAt = now;
    }

    /** Another member becomes the reporter. */
    void reportedBy(String reporter, Instant now) {
        this.reporter = reporter;
        this.updatedAt = now;
    }

    /** The issue in another project (ADR-27), with its number there and what fits there. */
    void movedTo(UUID projectId, long number, IssueType type, String status, String assignee, String reviewer,
                 Map<String, Object> custom, Instant now) {
        this.projectId = projectId;
        this.number = number;
        this.type = type;
        this.status = status;
        this.assignee = assignee;
        this.reviewer = reviewer;
        this.custom = new HashMap<>(custom);
        this.updatedAt = now;
    }

    /** Another parent, or none (ADR-28); the rules are those of {@link IssueEditor}. */
    void parentTo(UUID parentId, Instant now) {
        this.parentId = parentId;
        this.updatedAt = now;
    }

    /** Adds or removes the flag. */
    void flag(boolean flagged, Instant now) {
        this.flagged = flagged;
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

    LocalDate dueOn() {
        return dueOn;
    }

    LocalDate startOn() {
        return startOn;
    }

    Integer originalEstimate() {
        return originalEstimate;
    }

    Integer remainingEstimate() {
        return remainingEstimate;
    }

    int timeSpent() {
        return timeSpent;
    }

    String reviewer() {
        return reviewer;
    }

    UUID parentId() {
        return parentId;
    }

    boolean flagged() {
        return flagged;
    }

    Map<String, Object> custom() {
        return custom == null ? Map.of() : custom;
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

    String tenantId() {
        return tenantId;
    }

    long version() {
        return version;
    }
}
