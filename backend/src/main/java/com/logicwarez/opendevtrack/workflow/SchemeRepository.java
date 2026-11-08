/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SchemeRepository extends JpaRepository<SchemeEntity, SchemeEntity.Key> {

    List<SchemeEntity> findByProjectId(UUID projectId);

    Optional<SchemeEntity> findByProjectIdAndIssueType(UUID projectId, String issueType);
}
