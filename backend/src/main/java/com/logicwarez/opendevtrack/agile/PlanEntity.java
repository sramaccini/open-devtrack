/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** The plan of an issue (ADR-15): its place in the order of the project, its sprint (none: the backlog), its points. */
@Entity
@Table(schema = "odt_agile", name = "issue_plan")
class PlanEntity {

    @Id
    private UUID issueId;

    private UUID projectId;
    private String rank;
    private UUID sprintId;
    private Integer points;

    protected PlanEntity() {
    }

    PlanEntity(UUID issueId, UUID projectId, String rank) {
        this.issueId = issueId;
        this.projectId = projectId;
        this.rank = rank;
    }

    UUID issueId() {
        return issueId;
    }

    UUID projectId() {
        return projectId;
    }

    String rank() {
        return rank;
    }

    UUID sprintId() {
        return sprintId;
    }

    Integer points() {
        return points;
    }

    void rank(String rank) {
        this.rank = rank;
    }

    void sprint(UUID sprintId) {
        this.sprintId = sprintId;
    }

    void points(Integer points) {
        this.points = points;
    }
}
