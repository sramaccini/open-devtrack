/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WorkLogRepository extends JpaRepository<WorkLogEntity, UUID> {

    List<WorkLogEntity> findByIssueIdOrderByStartedAtAscIdAsc(UUID issueId);

    @Modifying
    @Query("DELETE FROM WorkLogEntity w WHERE w.issueId = :issueId")
    int deleteByIssue(UUID issueId);
}
