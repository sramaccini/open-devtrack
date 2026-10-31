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
 * role holds the permissions of the one before it. Fixed in code for 1.0; permission schemes per project come later.
 */
public enum ProjectRole {

    VIEWER(Set.of("odt.issue.browse")),
    DEVELOPER(Set.of("odt.issue.browse", "odt.issue.create", "odt.issue.edit", "odt.issue.transition", "odt.issue.comment")),
    ADMIN(Set.of("odt.issue.browse", "odt.issue.create", "odt.issue.edit", "odt.issue.transition", "odt.issue.comment",
            "odt.issue.delete", "odt.project.edit", "odt.member.manage"));

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
