/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every query takes the tenant: a project of another tenant does not exist (ADR-09). */
interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findByTenantIdOrderByKey(String tenantId);

    Optional<ProjectEntity> findByTenantIdAndKey(String tenantId, String key);

    boolean existsByTenantIdAndKey(String tenantId, String key);

    /** The next issue number of the project, in one statement: two issues never get the same number. */
    @Query(value = "UPDATE odt_project.project SET issue_seq = issue_seq + 1 WHERE id = :id RETURNING issue_seq", nativeQuery = true)
    long nextIssueNumber(UUID id);
}
