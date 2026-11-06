/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SprintIssueRepository extends JpaRepository<SprintIssueEntity, UUID> {

    /** The issue while it is in the sprint (not gone out yet). */
    Optional<SprintIssueEntity> findBySprintIdAndIssueIdAndRemovedAtIsNull(UUID sprintId, UUID issueId);

    List<SprintIssueEntity> findBySprintIdAndRemovedAtIsNull(UUID sprintId);
}
