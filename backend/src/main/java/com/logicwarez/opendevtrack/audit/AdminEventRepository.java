/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AdminEventRepository extends JpaRepository<AdminEventEntity, UUID>, JpaSpecificationExecutor<AdminEventEntity> {

    @Modifying
    @Query("DELETE FROM AdminEventEntity e WHERE e.occurredAt < :before")
    int deleteOlderThan(Instant before);
}
