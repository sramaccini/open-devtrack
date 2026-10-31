/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The projects as the other modules see them: always those of the tenant of the session. */
@Service
@Transactional(readOnly = true)
public class Projects {

    private final ProjectRepository projects;
    private final CurrentUser user;

    Projects(ProjectRepository projects, CurrentUser user) {
        this.projects = projects;
        this.user = user;
    }

    /** The project with this key in the tenant of the session; empty also when it belongs to another tenant. */
    public Optional<ProjectRef> find(String key) {
        return projects.findByTenantIdAndKey(user.tenantId(), key).map(ProjectEntity::toRef);
    }

    /** Reserves the next issue number of the project (ODT-1, ODT-2…), inside the transaction of the caller. */
    @Transactional
    public long nextIssueNumber(UUID projectId) {
        return projects.nextIssueNumber(projectId);
    }
}
