/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface IssueChangeRepository extends JpaRepository<IssueChangeEntity, UUID> {

    /** Newest first; the identifiers are UUIDv7, so they also keep the order of the fields of one change. */
    List<IssueChangeEntity> findByIssueIdOrderByChangedAtDescIdAsc(UUID issueId, Limit limit);
}
