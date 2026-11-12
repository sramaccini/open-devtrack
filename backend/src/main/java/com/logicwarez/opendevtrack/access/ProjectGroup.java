/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.time.Instant;
import java.util.UUID;

/** A group with a role in a project (ADR-22). */
public record ProjectGroup(UUID id, String name, ProjectRole role, String grantedBy, Instant grantedAt) {
}
