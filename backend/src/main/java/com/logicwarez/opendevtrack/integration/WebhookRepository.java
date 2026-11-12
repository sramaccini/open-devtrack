/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookRepository extends JpaRepository<WebhookEntity, UUID> {

    List<WebhookEntity> findByProjectIdOrderByCreatedAtAscIdAsc(UUID projectId);

    List<WebhookEntity> findByProjectIdAndActiveTrue(UUID projectId);

    Optional<WebhookEntity> findByIdAndProjectId(UUID id, UUID projectId);

    long countByProjectId(UUID projectId);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
