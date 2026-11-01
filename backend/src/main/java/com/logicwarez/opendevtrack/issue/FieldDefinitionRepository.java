/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FieldDefinitionRepository extends JpaRepository<FieldDefinitionEntity, UUID> {

    /** All the fields of a project, archived too, in the order of the forms. */
    List<FieldDefinitionEntity> findByProjectIdOrderByPositionAscKeyAsc(UUID projectId);

    Optional<FieldDefinitionEntity> findByProjectIdAndKey(UUID projectId, String key);

    long countByProjectId(UUID projectId);
}
