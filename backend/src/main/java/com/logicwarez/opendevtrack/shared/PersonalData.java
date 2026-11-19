/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a module keeps about a person in a tenant (ADR-24 point 7, ASVS 8.3.2): every module that stores user names
 * implements it, and the module {@code privacy} asks each of them for the export of a user and for their removal.
 * A module reads and writes only its own tables; the subject brings the projects and the issues of the tenant.
 */
public interface PersonalData {

    /**
     * The user, in a tenant, with the projects and the issues of that tenant. {@link #projectIds()} and
     * {@link #issueIds()} are those lists as a PostgreSQL array literal, for {@code = ANY(?::uuid[])}.
     */
    record Subject(String tenantId, String username, List<UUID> projects, List<UUID> issues) {

        public Subject {
            projects = List.copyOf(projects);
            issues = List.copyOf(issues);
        }

        public String projectIds() {
            return array(projects);
        }

        public String issueIds() {
            return array(issues);
        }

        private static String array(List<UUID> ids) {
            return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        }
    }

    /** The parts of the export of this module, each a list of rows ({@code "comments"} → the comments of the user). */
    Map<String, List<Map<String, Object>>> export(Subject subject);

    /**
     * Removes the user from the data of this module in the tenant: what is only theirs (messages, preferences,
     * filters, watches, mentions, memberships) is deleted; where they are named in the work of a project (an author, an
     * assignee, a change in a history) the name becomes the pseudonym. The texts written stay as they are.
     */
    void forget(Subject subject, String pseudonym);
}
