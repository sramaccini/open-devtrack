/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The project of a request and what the user can do in it (ADR-07): always a project of the tenant of the session,
 * else nothing (ADR-09).
 */
@Component
class Scopes {

    static final String BROWSE = "odt.issue.browse";
    static final String RANK = "odt.issue.edit";
    static final String SPRINTS = "odt.sprint.manage";
    static final String BOARDS = "odt.project.edit";
    static final String TRANSITION = "odt.issue.transition";

    static final String NOT_FOUND = "Project not found";
    static final String NOT_A_MEMBER = "Only the members of the project see its boards and its backlog";

    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;

    Scopes(Projects projects, ProjectAccess access, CurrentUser user) {
        this.projects = projects;
        this.access = access;
        this.user = user;
    }

    record Scope(ProjectRef project, Set<String> permissions) {

        boolean can(String permission) {
            return permissions.contains(permission);
        }

        UUID projectId() {
            return project.id();
        }
    }

    Optional<Scope> of(String projectKey) {
        return projects.find(projectKey).map(this::scope);
    }

    Optional<Scope> of(UUID projectId) {
        return projects.get(projectId).map(this::scope);
    }

    Scope scope(ProjectRef project) {
        return new Scope(project, access.permissions(project.id(), user.username()));
    }

    String username() {
        return user.username();
    }
}
