/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Publications: one per document and environment. */
interface PublicationRepository extends JpaRepository<PublicationEntity, UUID> {

    List<PublicationEntity> findByAppIdAndKindAndDocumentId(String appId, String kind, String documentId);

    Optional<PublicationEntity> findByAppIdAndKindAndDocumentIdAndEnvironment(String appId, String kind, String documentId, String environment);
}
