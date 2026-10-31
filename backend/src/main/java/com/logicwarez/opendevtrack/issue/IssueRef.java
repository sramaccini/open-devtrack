/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.UUID;

/** What the other modules know of an issue: its identifier, project and key. */
public record IssueRef(UUID id, UUID projectId, String key) {
}
