/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PermissionSchemeRepository extends JpaRepository<PermissionSchemeEntity, UUID> {

    List<PermissionSchemeEntity> findByTenantIdOrderByIsDefaultDescNameAsc(String tenantId);

    Optional<PermissionSchemeEntity> findByIdAndTenantId(UUID id, String tenantId);

    Optional<PermissionSchemeEntity> findByTenantIdAndIsDefaultTrue(String tenantId);

    @Query("select count(s) > 0 from PermissionSchemeEntity s where s.tenantId = ?1 and lower(s.name) = lower(?2)"
            + " and (?3 is null or s.id <> ?3)")
    boolean nameTaken(String tenantId, String name, UUID except);
}
