/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import com.logicwarez.authz.session.PermissionContributor;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.access.PermissionSchemes;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The project roles in the checks of authz-library ({@code POST /authz/check}, A1): a check with the resource
 * {@code project} (its key) or {@code issue} (its key, {@code WEB-42}) of a permission that a project role gives is
 * decided by the role of the user in that project, the admin of the tenant counting as admin (ADR-07). A project of
 * another tenant, or that does not exist, is a refusal. The other checks keep the permissions of the session.
 *
 * <p>No permission is added to {@code /authz/capabilities}: they all depend on a project. The screens keep reading
 * them as {@code _perm} on rows and forms, which costs no extra request.
 */
@Component
class ProjectPermissions implements PermissionContributor {

    static final String PROJECT = "project";
    static final String ISSUE = "issue";

    private final Projects projects;
    private final ProjectAccess access;

    ProjectPermissions(Projects projects, ProjectAccess access) {
        this.projects = projects;
        this.access = access;
    }

    @Override
    public Optional<Boolean> check(SessionContext session, String permission, Resource resource) {
        if (!PermissionSchemes.CATALOGUE.contains(permission) || resource.id() == null) {
            return Optional.empty();
        }
        String key = switch (resource.type()) {
            case PROJECT -> resource.id();
            case ISSUE -> resource.id().contains("-") ? resource.id().substring(0, resource.id().lastIndexOf('-')) : "";
            default -> null;
        };
        if (key == null) {
            return Optional.empty();
        }
        return Optional.of(projects.find(key)
                .map(p -> access.permissions(p.id(), session.username()).contains(permission))
                .orElse(false));
    }
}
