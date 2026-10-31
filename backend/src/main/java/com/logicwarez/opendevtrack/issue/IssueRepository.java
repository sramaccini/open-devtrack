/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface IssueRepository extends JpaRepository<IssueEntity, UUID> {

    Optional<IssueEntity> findByProjectIdAndNumber(UUID projectId, long number);

    /** Issues of a project, newest first; each filter is ignored when null ({@code text}: lower case, with the %). */
    @Query("""
            SELECT i FROM IssueEntity i
            WHERE i.projectId = :projectId
              AND (:status IS NULL OR i.status = :status)
              AND (:assignee IS NULL OR i.assignee = :assignee)
              AND (:text IS NULL OR lower(i.summary) LIKE :text)
            ORDER BY i.number DESC""")
    List<IssueEntity> search(UUID projectId, String status, String assignee, String text, Limit limit);
}
