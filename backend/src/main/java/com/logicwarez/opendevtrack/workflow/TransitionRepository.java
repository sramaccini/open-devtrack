/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TransitionRepository extends JpaRepository<TransitionEntity, UUID> {

    List<TransitionEntity> findByWorkflowIdOrderByPosition(UUID workflowId);

    Optional<TransitionEntity> findByWorkflowIdAndCode(UUID workflowId, String code);
}
