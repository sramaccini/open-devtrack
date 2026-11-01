/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface IssueLinkRepository extends JpaRepository<IssueLinkEntity, UUID> {

    /** The links of an issue, from both ends, oldest first. */
    @Query("SELECT l FROM IssueLinkEntity l WHERE l.fromId = :issueId OR l.toId = :issueId ORDER BY l.createdAt, l.id")
    List<IssueLinkEntity> findByIssue(UUID issueId);

    boolean existsByFromIdAndToIdAndType(UUID fromId, UUID toId, LinkType type);
}
