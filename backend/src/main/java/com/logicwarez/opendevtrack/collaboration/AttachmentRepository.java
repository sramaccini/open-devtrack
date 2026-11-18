/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AttachmentRepository extends JpaRepository<AttachmentEntity, UUID> {

    List<AttachmentEntity> findByIssueIdOrderByUploadedAtAscIdAsc(UUID issueId);

    long countByIssueId(UUID issueId);

    /** The bytes of the attachments of a project, for its quota. */
    @Query("select coalesce(sum(a.size), 0) from AttachmentEntity a where a.projectId = :projectId")
    long totalSizeOfProject(UUID projectId);

    boolean existsByStoreNot(String store);
}
