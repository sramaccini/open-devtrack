/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    protected WorkflowEntity() {
    }

    WorkflowEntity(String tenantId, UUID projectId, String name) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.name = name;
    }

    UUID id() {
        return id;
    }
}
