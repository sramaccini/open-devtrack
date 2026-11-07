/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FilterRepository extends JpaRepository<FilterEntity, UUID> {

    /** The filters of the user and those shared in the tenant, by name. */
    @Query("select f from FilterEntity f where f.tenantId = :tenantId and (f.owner = :owner or f.shared = true)"
            + " order by lower(f.name), f.id")
    List<FilterEntity> visible(String tenantId, String owner);
}
