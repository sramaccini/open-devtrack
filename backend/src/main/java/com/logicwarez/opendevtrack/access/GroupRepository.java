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
import org.springframework.data.jpa.repository.Query;

interface GroupRepository extends JpaRepository<GroupEntity, UUID> {

    List<GroupEntity> findByTenantIdOrderByNameAsc(String tenantId);

    Optional<GroupEntity> findByIdAndTenantId(UUID id, String tenantId);

    List<GroupEntity> findByIdIn(Collection<UUID> ids);

    @Query("select count(g) > 0 from GroupEntity g where g.tenantId = ?1 and lower(g.name) = lower(?2)"
            + " and (?3 is null or g.id <> ?3)")
    boolean nameTaken(String tenantId, String name, UUID except);
}
