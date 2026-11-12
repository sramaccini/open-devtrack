/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** The permission scheme a project uses, when it is not the default of its tenant. */
@Entity
@Table(schema = "odt_access", name = "project_scheme")
class ProjectSchemeEntity {

    @Id
    private UUID projectId;

    private UUID schemeId;

    protected ProjectSchemeEntity() {
    }

    ProjectSchemeEntity(UUID projectId, UUID schemeId) {
        this.projectId = projectId;
        this.schemeId = schemeId;
    }

    UUID schemeId() {
        return schemeId;
    }

    void use(UUID schemeId) {
        this.schemeId = schemeId;
    }
}
