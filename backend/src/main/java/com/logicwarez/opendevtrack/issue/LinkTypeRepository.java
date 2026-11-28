/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LinkTypeRepository extends JpaRepository<LinkTypeEntity, UUID> {

    /** The types of the tenant: the system ones first, then by code. */
    List<LinkTypeEntity> findByTenantIdOrderBySystemDescCodeAsc(String tenantId);

    Optional<LinkTypeEntity> findByTenantIdAndCode(String tenantId, String code);

    boolean existsByTenantId(String tenantId);
}
