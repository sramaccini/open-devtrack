/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.time.Instant;

/** A user with a role in a project. */
public record ProjectMember(String username, ProjectRole role, String grantedBy, Instant grantedAt) {
}
