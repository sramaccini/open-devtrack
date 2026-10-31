/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Versions of the documents; newest first means by creation time, then by identifier (UUID v7, ordered in time). */
interface VersionRepository extends JpaRepository<VersionEntity, UUID> {

    List<VersionEntity> findByAppIdAndKindAndDocumentIdOrderByCreatedAtDescIdDesc(String appId, String kind, String documentId);

    Optional<VersionEntity> findFirstByAppIdAndKindAndDocumentIdOrderByCreatedAtDescIdDesc(String appId, String kind, String documentId);

    Optional<VersionEntity> findByAppIdAndKindAndDocumentIdAndHash(String appId, String kind, String documentId, String hash);

    @Query("select distinct v.appId from VersionEntity v order by v.appId")
    List<String> appIds();

    /** Kind and identifier of every document of an application. */
    @Query("select distinct v.kind, v.documentId from VersionEntity v where v.appId = ?1")
    List<Object[]> documents(String appId);
}
