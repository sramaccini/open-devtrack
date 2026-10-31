/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import java.util.UUID;

/** What the other modules know of a project: its identifier, tenant, key and name. */
public record ProjectRef(UUID id, String tenantId, String key, String name) {
}
