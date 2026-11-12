/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectSchemeRepository extends JpaRepository<ProjectSchemeEntity, UUID> {

    long countBySchemeId(UUID schemeId);
}
