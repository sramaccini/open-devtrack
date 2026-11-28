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

interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    List<TeamEntity> findByTenantIdOrderByNameAsc(String tenantId);

    Optional<TeamEntity> findByIdAndTenantId(UUID id, String tenantId);

    List<TeamEntity> findByIdIn(Collection<UUID> ids);

    @Query("select t from TeamEntity t where t.tenantId = ?1 and lower(t.name) = lower(?2)")
    Optional<TeamEntity> findNamed(String tenantId, String name);

    @Query("select count(t) > 0 from TeamEntity t where t.tenantId = ?1 and lower(t.name) = lower(?2)"
            + " and (?3 is null or t.id <> ?3)")
    boolean nameTaken(String tenantId, String name, UUID except);
}
