/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WatcherRepository extends JpaRepository<WatcherEntity, UUID> {

    List<WatcherEntity> findByIssueIdOrderByUsername(UUID issueId);

    Optional<WatcherEntity> findByIssueIdAndUsername(UUID issueId, String username);

    boolean existsByIssueIdAndUsername(UUID issueId, String username);

    /** Adds the watcher unless it is already there, also when two requests add it at the same time. */
    @Modifying
    @Query(value = """
            INSERT INTO odt_issue.watcher (issue_id, username, created_at) VALUES (:issueId, :username, :at)
            ON CONFLICT (issue_id, username) DO NOTHING""", nativeQuery = true)
    int watch(UUID issueId, String username, Instant at);
}
