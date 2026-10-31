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
@Table(schema = "odt_workflow", name = "transition")
class TransitionEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID workflowId;
    private String code;
    private String fromStatus;
    private String toStatus;
    private String guardCel;
    private int position;

    protected TransitionEntity() {
    }

    TransitionEntity(UUID workflowId, String code, String fromStatus, String toStatus, int position) {
        this.workflowId = workflowId;
        this.code = code;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.position = position;
    }

    void guard(String guardCel) {
        this.guardCel = guardCel;
    }

    String code() {
        return code;
    }

    String fromStatus() {
        return fromStatus;
    }

    String toStatus() {
        return toStatus;
    }

    String guardCel() {
        return guardCel;
    }

    /** Allowed from this status: its own, or from every status when it has none. */
    boolean from(String status) {
        return fromStatus == null || fromStatus.equals(status);
    }
}
