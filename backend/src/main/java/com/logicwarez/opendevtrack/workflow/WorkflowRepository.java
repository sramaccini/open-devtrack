/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {

    /** The workflows of the project, the default first, then in the order they were added. */
    List<WorkflowEntity> findByProjectIdOrderByIsDefaultDescIdAsc(UUID projectId);

    Optional<WorkflowEntity> findByProjectIdAndIsDefaultTrue(UUID projectId);

    Optional<WorkflowEntity> findByIdAndProjectId(UUID id, UUID projectId);
}
