/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BoardRepository extends JpaRepository<BoardEntity, UUID> {

    List<BoardEntity> findByProjectIdOrderByCreatedAtAscIdAsc(UUID projectId);

    List<BoardEntity> findByProjectIdInOrderByCreatedAtAscIdAsc(Collection<UUID> projectIds);

    long countByProjectId(UUID projectId);
}
