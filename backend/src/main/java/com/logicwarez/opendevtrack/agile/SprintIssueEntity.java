/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** An issue in a sprint from its start (ADR-15): when it came in and went out, and how; for the reports. */
@Entity
@Table(schema = "odt_agile", name = "sprint_issue")
class SprintIssueEntity {

    static final String DONE = "done";
    static final String CARRIED = "carried";
    static final String REMOVED = "removed";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID sprintId;
    private UUID issueId;
    private Integer points;
    private Instant addedAt;
    private Instant removedAt;
    private String outcome;

    protected SprintIssueEntity() {
    }

    SprintIssueEntity(UUID sprintId, UUID issueId, Integer points, Instant addedAt) {
        this.sprintId = sprintId;
        this.issueId = issueId;
        this.points = points;
        this.addedAt = addedAt;
    }

    void out(String outcome, Instant now) {
        this.outcome = outcome;
        this.removedAt = now;
    }

    UUID issueId() {
        return issueId;
    }

    String outcome() {
        return outcome;
    }
}
