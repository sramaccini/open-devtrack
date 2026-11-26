/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface VoteRepository extends JpaRepository<VoteEntity, UUID> {

    List<VoteEntity> findByIssueIdOrderByUsername(UUID issueId);

    long countByIssueId(UUID issueId);

    boolean existsByIssueIdAndUsername(UUID issueId, String username);

    /** Adds the vote unless it is already there, also when two requests add it at the same time. */
    @Modifying
    @Query(value = """
            INSERT INTO odt_issue.vote (issue_id, username, created_at) VALUES (:issueId, :username, :at)
            ON CONFLICT (issue_id, username) DO NOTHING""", nativeQuery = true)
    int vote(UUID issueId, String username, Instant at);

    @Modifying
    @Query(value = "DELETE FROM odt_issue.vote WHERE issue_id = :issueId AND username = :username", nativeQuery = true)
    int unvote(UUID issueId, String username);
}
