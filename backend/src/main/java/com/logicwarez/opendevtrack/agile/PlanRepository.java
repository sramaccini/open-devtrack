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
import org.springframework.data.jpa.repository.Query;

interface PlanRepository extends JpaRepository<PlanEntity, UUID> {

    List<PlanEntity> findByProjectId(UUID projectId);

    List<PlanEntity> findBySprintId(UUID sprintId);

    List<PlanEntity> findByIssueIdIn(Collection<UUID> issueIds);

    /** The last key of the order of the project. */
    @Query("SELECT max(p.rank) FROM PlanEntity p WHERE p.projectId = :projectId")
    Optional<String> last(UUID projectId);

    /** The first key after {@code rank}, leaving out the issue that moves. */
    @Query("SELECT min(p.rank) FROM PlanEntity p WHERE p.projectId = :projectId AND p.rank > :rank AND p.issueId <> :except")
    Optional<String> next(UUID projectId, String rank, UUID except);

    /** The last key before {@code rank}, leaving out the issue that moves. */
    @Query("SELECT max(p.rank) FROM PlanEntity p WHERE p.projectId = :projectId AND p.rank < :rank AND p.issueId <> :except")
    Optional<String> previous(UUID projectId, String rank, UUID except);

    /** One change of order at a time in a project, until the end of the transaction: two moves never take one key. */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended(CAST(:projectId AS text), 15)) AS text)",
            nativeQuery = true)
    String lock(UUID projectId);
}
