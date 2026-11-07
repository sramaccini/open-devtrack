/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SprintRepository extends JpaRepository<SprintEntity, UUID> {

    /** Sprints of the project in some states, the oldest first. */
    List<SprintEntity> findByProjectIdAndStateInOrderByCreatedAtAscIdAsc(UUID projectId, Collection<String> states);

    /** Every sprint of the project, the newest first. */
    List<SprintEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    List<SprintEntity> findByProjectIdInOrderByCreatedAtDesc(Collection<UUID> projectIds);

    Optional<SprintEntity> findByProjectIdAndState(UUID projectId, String state);

    long countByProjectId(UUID projectId);
}
