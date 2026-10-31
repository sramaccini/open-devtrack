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
@Table(schema = "odt_workflow", name = "status")
class StatusEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID workflowId;
    private String code;
    private String category;
    private int position;

    protected StatusEntity() {
    }

    StatusEntity(UUID workflowId, String code, String category, int position) {
        this.workflowId = workflowId;
        this.code = code;
        this.category = category;
        this.position = position;
    }

    String code() {
        return code;
    }

    String category() {
        return category;
    }

    int position() {
        return position;
    }
}
