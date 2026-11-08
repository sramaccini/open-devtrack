/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** A row of the workflow scheme of a project (ADR-20, B): the issues of the type follow the workflow. */
@Entity
@Table(schema = "odt_workflow", name = "workflow_scheme")
@IdClass(SchemeEntity.Key.class)
class SchemeEntity {

    record Key(UUID projectId, String issueType) implements Serializable {
    }

    @Id
    private UUID projectId;

    @Id
    private String issueType;

    private UUID workflowId;

    protected SchemeEntity() {
    }

    SchemeEntity(UUID projectId, String issueType, UUID workflowId) {
        this.projectId = projectId;
        this.issueType = issueType;
        this.workflowId = workflowId;
    }

    void workflow(UUID workflowId) {
        this.workflowId = workflowId;
    }

    String issueType() {
        return issueType;
    }

    UUID workflowId() {
        return workflowId;
    }
}
