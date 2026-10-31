/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import java.time.Instant;
import java.util.UUID;

/** Published inside the transaction that creates the project, so that its listeners can set it up in the same one. */
public record ProjectCreated(UUID projectId, String tenantId, String key, String by, Instant at) {
}
