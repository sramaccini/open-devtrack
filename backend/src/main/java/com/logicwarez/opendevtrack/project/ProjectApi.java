/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of /api/projects. {@code _perm}: what the user may do on that row (ADR-07), for the screens. */
final class ProjectApi {

    private ProjectApi() {
    }

    /** {@code permissionScheme}: the permission scheme of the project (ADR-22), its id and name. */
    record ProjectView(UUID id, String key, String name, String description, String lead, String role,
                       Instant createdAt, long version, UUID permissionScheme, String permissionSchemeName,
                       @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** The permission scheme the project uses, among those of the tenant. */
    record SchemeChoice(UUID scheme) {
    }

    record CreateProject(String key, String name, String description) {
    }

    record EditProject(String name, String description, String lead, Long version) {
    }

    /**
     * A member: {@code role} is the highest of their own ({@code ownRole}, null for a member only through groups) and
     * those of the {@code groups} they are members through (ADR-22).
     */
    record MemberView(String username, String role, String ownRole, List<String> groups, String grantedBy,
                      Instant grantedAt, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A group with a role in the project. */
    record ProjectGroupView(UUID id, String name, String role, String grantedBy, Instant grantedAt,
                           @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A group to add to the project, or the new role of one. */
    record GroupRequest(UUID group, String role) {
    }

    record MemberRequest(String username, String role) {
    }
}
