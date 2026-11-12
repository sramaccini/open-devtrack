/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The roles a user can have in a project, each with its permissions ({@code odt.<resource>.<action>}, ADR-07). Each
 * role holds the permissions of the one before it. These are the sets of a new default permission scheme; a project
 * gets those of its scheme ({@link PermissionSchemes}, ADR-22).
 */
public enum ProjectRole {

    VIEWER(Set.of("odt.issue.browse")),
    DEVELOPER(Set.of("odt.issue.browse", "odt.issue.create", "odt.issue.edit", "odt.issue.transition", "odt.issue.comment",
            "odt.issue.attach", "odt.sprint.manage")),
    ADMIN(Set.of("odt.issue.browse", "odt.issue.create", "odt.issue.edit", "odt.issue.transition", "odt.issue.comment",
            "odt.issue.attach", "odt.sprint.manage", "odt.issue.delete", "odt.project.edit", "odt.member.manage"));

    private final Set<String> permissions;

    ProjectRole(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Set<String> permissions() {
        return permissions;
    }

    /** Name in the API: {@code viewer}, {@code developer}, {@code admin}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ProjectRole> parse(String value) {
        return Arrays.stream(values()).filter(r -> r.value().equals(value)).findFirst();
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(ProjectRole::value).toList();
    }
}
