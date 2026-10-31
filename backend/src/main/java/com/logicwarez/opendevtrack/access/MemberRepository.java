/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MemberRepository extends JpaRepository<MemberEntity, MemberEntity.Key> {

    List<MemberEntity> findByProjectIdOrderByUsername(UUID projectId);

    Optional<MemberEntity> findByProjectIdAndUsername(UUID projectId, String username);

    List<MemberEntity> findByProjectIdInAndUsername(Collection<UUID> projectIds, String username);

    long countByProjectIdAndRole(UUID projectId, ProjectRole role);
}
