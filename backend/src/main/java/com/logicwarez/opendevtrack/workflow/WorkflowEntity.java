/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "odt_workflow", name = "workflow")
class WorkflowEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private UUID projectId;
    private String name;

    /** Changed at each edit of the statuses or transitions (ADR-20): 412 for a change made on an older copy. */
    @Version
    private long version;

    private Instant updatedAt;

    protected WorkflowEntity() {
    }

    WorkflowEntity(String tenantId, UUID projectId, String name) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.name = name;
    }

    /** Marks the workflow changed, so that its version moves on with the change of a status or a transition. */
    void changed(Instant now) {
        this.updatedAt = now;
    }

    long version() {
        return version;
    }

    UUID id() {
        return id;
    }
}
