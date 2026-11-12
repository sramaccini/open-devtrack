/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.time.Instant;
import java.util.List;

/**
 * A user with a role in a project: {@code role} is the highest of their own ({@code own}, null when they are a member
 * only through groups) and those of the groups named in {@code groups} (ADR-22).
 */
public record ProjectMember(String username, ProjectRole role, ProjectRole own, List<String> groups, String grantedBy,
                            Instant grantedAt) {
}
